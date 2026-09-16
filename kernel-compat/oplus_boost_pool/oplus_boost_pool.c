// SPDX-License-Identifier: GPL-2.0-only
/*
 * Copyright (C) 2018-2020 Oplus. All rights reserved.
 *
 * OPlus dma-buf camera boost pool, ported to the Lenovo TB710FU vendor kernel.
 *
 * 为什么需要它
 * ------------
 * ColorOS 的 qcom_dma_heaps 里编进了 OPlus 的 boost pool
 * （vendor/oplus/kernel/mm/mm_boost_pool/oplus_boost_pool.c，移植源 OPD2513 与
 * 一加 Pad 2 的开源树里逐字节相同）：给 qcom,system heap 常备一池清零页，
 * 相机 HAL 开会话时把自己的 pid 写进 /proc/boost_pool/camera_pid，
 * 相机缓冲直接从池里拿，不进 buddy、不触发直接回收；缓冲释放时先回填池子。
 *
 * 对照机 PKX110 实测（亮屏人脸解锁）：池 559MB -> 0.3s 内降到 401MB，
 * 3.3s 会话结束后回到 564MB；同一窗口里手机直接回收 185 次 / 0.42s。
 * 平板的 qcom_dma_heaps 是联想 vendor_boot 里的高通原版，没有这个池：
 * 同一窗口直接回收 1512 次 / 4.9s，其中相机 provider 独占 ~450 次，
 * SystemUI RenderThread、SurfaceFlinger 的合成都被拖进回收，锁屏动画卡顿。
 *
 * 移植方式
 * --------
 * 池本身（数据结构、预填/补水线程、shrinker、/proc/boost_pool/ 接口、
 * camera_pid 语义、按内存档位的默认大小）按原厂源码原样保留。
 *
 * 原厂在 qcom_dma_heaps 里只有两处调用，这里用 kprobe 在函数入口接管：
 *   1. system_qcom_sg_buffer_alloc() 的分配循环调用
 *      qcom_sys_heap_alloc_largest_available(pools, size, max_order, movable)
 *      -> 先按 dynamic_boost_pool_alloc_pack() 的规则从池里给页，给到了就
 *         直接带返回值返回（arm64 kprobe override，与 bpf_override_return 同法）。
 *   2. system_heap_buf_free() 的正常（已清零）分支调用
 *      dynamic_page_pool_free(pool_list[j], page)
 *      -> 先按 dynamic_boost_pool_free() 的规则回填池子。
 * 两处都同时校验：参数里的池指针属于 qcom,system heap，且返回地址正是
 * 上述函数里对被挂函数的 BL 调用点（加载时反汇编扫描得出）。secure heap、
 * 预取线程等其它调用者一律放行给原函数。任一校验在加载时失败则拒绝加载。
 *
 * 与原厂的差异（仅此三处）
 * ------------------------
 *   a. alloc_pack 的 camera_pid 门槛原厂每次分配判一次，这里每取一块页判一次
 *      （非相机进程取到 camera_pages 线即止，比原厂更保守）；取完后把
 *      max_order 复位到最高阶，与原厂一致。
 *   b. 联想相机 HAL 不会写 camera_pid。原厂 HAL 在会话开始写入自己的 tgid、
 *      结束写 1；provider 只在会话里分配，因此等价于"始终认 provider"：
 *      组长 comm 等于 camera_comm 参数的进程第一次分配时自动绑定。
 *      手工写 /proc/boost_pool/camera_pid 仍然有效。
 *   c. 线程可停止，模块可卸载（卸载时池页全部还给 buddy）。
 */
#define pr_fmt(fmt) "boostpool: " fmt

#include <asm/page.h>
#include <asm/ptrace.h>
#include <linux/dma-heap.h>
#include <linux/dma-mapping.h>
#include <linux/err.h>
#include <linux/highmem.h>
#include <linux/kallsyms.h>
#include <linux/kprobes.h>
#include <linux/scatterlist.h>
#include <linux/seq_file.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/sizes.h>
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/kthread.h>
#include <linux/kernel.h>
#include <linux/version.h>
#include <linux/proc_fs.h>
#include <linux/vmstat.h>
#include <linux/oom.h>
#include <linux/poll.h>
#include <linux/mm.h>
#include <linux/sched.h>
#include <linux/swap.h>
#include <uapi/linux/sched/types.h>

/* ---- qcom_dynamic_page_pool.h（高通 heaps 头文件摘录，只用于本模块自己的池） ---- */

#define HIGH_ORDER_GFP  (((GFP_HIGHUSER | __GFP_ZERO | __GFP_NOWARN \
				| __GFP_NORETRY) & ~__GFP_RECLAIM) \
				| __GFP_COMP)
#define LOW_ORDER_GFP (GFP_HIGHUSER | __GFP_ZERO | __GFP_COMP)

static gfp_t order_flags[] = {HIGH_ORDER_GFP, HIGH_ORDER_GFP,
			      LOW_ORDER_GFP};
static const unsigned int orders[] = {9, 4, 0};
#define NUM_ORDERS ARRAY_SIZE(orders)

enum dynamic_pool_callback_ret {
	DYNAMIC_POOL_SUCCESS,
	DYNAMIC_POOL_FAILURE,
};

struct dynamic_page_pool;

typedef enum dynamic_pool_callback_ret (*prerelease_callback)(struct dynamic_page_pool *pool,
							      struct list_head *pages,
							      int num_pages);

struct dynamic_page_pool {
	int high_count;
	int low_count;
	atomic_t count;
	struct list_head high_items;
	struct list_head low_items;
	ktime_t last_low_watermark_ktime;
	struct task_struct *refill_worker;
	spinlock_t lock;
	gfp_t gfp_mask;
	unsigned int order;
	struct list_head list;
	int vmid;
	prerelease_callback prerelease_callback;
};

/* qcom_dynamic_page_pool.c */
static int dynamic_page_pool_total(struct dynamic_page_pool *pool, bool high)
{
	int count = pool->low_count;

	if (high)
		count += pool->high_count;

	return count << pool->order;
}

/* ---- oplus_boost_pool.h ---- */

#define LOWORDER_WATER_MASK (64*4)

struct dynamic_boost_pool {
	char *name;
	struct list_head list;
	int sf_pages, camera_pages;
	int low, high, origin;
	pid_t camera_pid;
	struct task_struct *tsk, *prefill_tsk;
	unsigned int wait_flag, prefill_wait_flag;
	wait_queue_head_t waitq, prefill_waitq;
	bool force_stop, prefill;
	struct mutex prefill_mutex;
	struct dynamic_page_pool **pools;
	/* 移植新增：供 proc 节点移除 */
	struct proc_dir_entry *proc_info, *proc_pages, *proc_stat, *proc_cpu, *proc_pid;
};

/* ---- oplus_boost_pool.c ---- */

#define MAX_BOOST_POOL_HIGH (1024 * 256)

#define K(x) ((x) << (PAGE_SHIFT-10))
#define M(x) (K(x) >> 10)
#define PAGES(x) (x >> PAGE_SHIFT)

static atomic64_t boost_pool_pages = ATOMIC64_INIT(0);

static LIST_HEAD(boost_pool_list);
static DEFINE_MUTEX(boost_pool_list_lock);

#define DEFINE_BOOST_POOL_PROC_RW_ATTRIBUTE(__name)			\
static int __name ## _open(struct inode *inode, struct file *file)	\
{									\
	struct dynamic_boost_pool *data = pde_data(inode);		\
	return single_open(file, __name ## _show, data);		\
}									\
									\
static const struct proc_ops __name ## _proc_ops = {			\
	.proc_open	= __name ## _open,				\
	.proc_read	= seq_read,					\
	.proc_write	= __name ## _write,				\
	.proc_lseek	= seq_lseek,					\
	.proc_release	= single_release,				\
}

static bool boost_pool_enable = true;

static void boost_page_pool_add(struct dynamic_page_pool *pool, struct page *page)
{
	unsigned long flags;
	spin_lock_irqsave(&pool->lock, flags);
	if (PageHighMem(page)) {
		list_add_tail(&page->lru, &pool->high_items);
		pool->high_count++;
	} else {
		list_add_tail(&page->lru, &pool->low_items);
		pool->low_count++;
	}

	spin_unlock_irqrestore(&pool->lock, flags);
	atomic_inc(&pool->count);
	atomic64_add(1 << pool->order, &boost_pool_pages);
}

static struct page *boost_page_pool_remove(struct dynamic_page_pool *pool, bool high)
{
	struct page *page;

	if (high) {
		BUG_ON(!pool->high_count);
		page = list_first_entry(&pool->high_items, struct page, lru);
		pool->high_count--;
	} else {
		BUG_ON(!pool->low_count);
		page = list_first_entry(&pool->low_items, struct page, lru);
		pool->low_count--;
	}

	atomic_dec(&pool->count);
	list_del(&page->lru);
	atomic64_sub(1 << pool->order, &boost_pool_pages);
	return page;
}

static void boost_page_pool_free(struct dynamic_page_pool *pool, struct page *page)
{
	BUG_ON(pool->order != compound_order(page));

	boost_page_pool_add(pool, page);
}

static struct dynamic_page_pool *dynamic_page_pool_create_new(gfp_t gfp_mask, unsigned int order)
{
	struct dynamic_page_pool *pool = kmalloc(sizeof(*pool), GFP_KERNEL);

	if (!pool)
		return NULL;
	pool->high_count = 0;
	pool->low_count = 0;
	INIT_LIST_HEAD(&pool->low_items);
	INIT_LIST_HEAD(&pool->high_items);
	pool->gfp_mask = gfp_mask | __GFP_COMP;
	pool->order = order;
	spin_lock_init(&pool->lock);

	return pool;
}

static void dynamic_page_pool_destroy_new(struct dynamic_page_pool *pool)
{
	struct page *page, *tmp;
	LIST_HEAD(pages);
	int num_pages = 0;
	int ret = DYNAMIC_POOL_SUCCESS;
	unsigned long flags;

	spin_lock_irqsave(&pool->lock, flags);
	while (true) {
		if (pool->low_count)
			page = boost_page_pool_remove(pool, false);
		else if (pool->high_count)
			page = boost_page_pool_remove(pool, true);
		else
			break;

		list_add(&page->lru, &pages);
		num_pages++;
	}
	spin_unlock_irqrestore(&pool->lock, flags);

	if (num_pages && pool->prerelease_callback)
		ret = pool->prerelease_callback(pool, &pages, num_pages);

	if (ret != DYNAMIC_POOL_SUCCESS) {
		pr_err("Failed to reclaim pages when destroying the pool!\n");
		return;
	}

	list_for_each_entry_safe(page, tmp, &pages, lru) {
		list_del(&page->lru);
		__free_pages(page, pool->order);
	}

	kfree(pool);
}

static struct dynamic_page_pool **dynamic_page_pool_create_pools_new(int vmid,
							  prerelease_callback callback)
{
	struct dynamic_page_pool **pool_list;
	int i;
	int ret;

	pool_list = kmalloc_array(NUM_ORDERS, sizeof(*pool_list), GFP_KERNEL);
	if (!pool_list)
		return ERR_PTR(-ENOMEM);

	for (i = 0; i < NUM_ORDERS; i++) {
		pool_list[i] = dynamic_page_pool_create_new(order_flags[i],
							orders[i]);
		/* 移植：原厂先写字段再判空，这里先判空 */
		if (IS_ERR_OR_NULL(pool_list[i])) {
			int j;

			pr_err("%s: page pool creation failed for the order %u pool!\n",
			       __func__, orders[i]);
			for (j = 0; j < i; j++)
				dynamic_page_pool_destroy_new(pool_list[j]);

			ret = -ENOMEM;
			goto free_pool_arr;
		}
		pool_list[i]->vmid = vmid;
		pool_list[i]->prerelease_callback = callback;
		atomic_set(&pool_list[i]->count, 0);
		pool_list[i]->last_low_watermark_ktime = 0;
	}

	return pool_list;

free_pool_arr:
	kfree(pool_list);

	return ERR_PTR(ret);
}

static void dynamic_page_pool_release_pools_new(struct dynamic_page_pool **pool_list)
{
	int i;

	for (i = 0; i < NUM_ORDERS; i++)
		dynamic_page_pool_destroy_new(pool_list[i]);

	kfree(pool_list);
}

static inline unsigned int order_to_size(int order)
{
	return PAGE_SIZE << order;
}

static int dynamic_boost_pool_nr_pages(struct dynamic_boost_pool *pool)
{
	int i;
	int count = 0;

	if (unlikely(NULL == pool)) {
		pr_err("%s: pool is NULL!\n", __func__);
		return 0;
	}

	for (i = 0; i < NUM_ORDERS; i++)
		count += dynamic_page_pool_total(pool->pools[i], 1);

	return count;
}

static int dynamic_boost_page_pool_refill(struct dynamic_page_pool *pool)
{
	struct page *page;
	gfp_t gfp_refill = pool->gfp_mask;

	if (NULL == pool) {
		pr_err("%s: pool is NULL!\n", __func__);
		return -ENOENT;
	}

	page = alloc_pages(gfp_refill, pool->order);
	if (NULL == page)
		return -ENOMEM;

	boost_page_pool_free(pool, page);
	return 0;
}

static int dynamic_boost_pool_kworkthread(void *p)
{
	int i;
	struct dynamic_boost_pool *boost_pool;
	int ret;

	if (NULL == p) {
		pr_err("%s: p is NULL!\n", __func__);
		return 0;
	}

	boost_pool = (struct dynamic_boost_pool *)p;

	while (!kthread_should_stop()) {
		ret = wait_event_interruptible(boost_pool->waitq,
					       (boost_pool->wait_flag == 1) ||
					       kthread_should_stop());
		if (ret < 0 || kthread_should_stop())
			continue;

		boost_pool->wait_flag = 0;

		for (i = 0; i < NUM_ORDERS; i++) {
			while (!boost_pool->force_stop && !kthread_should_stop() &&
			       dynamic_boost_pool_nr_pages(boost_pool) < boost_pool->low) {
				if (dynamic_boost_page_pool_refill(boost_pool->pools[i]) < 0)
					break;
			}
		}
	}

	return 0;
}

static int dynamic_boost_pool_prefill_kworkthread(void *p)
{
	int i;
	struct dynamic_boost_pool *pool;
	u64 timeout_jiffies;
	int ret;
	unsigned long begin;

	if (NULL == p) {
		pr_err("%s: p is NULL!\n", __func__);
		return 0;
	}

	pool = (struct dynamic_boost_pool *)p;
	while (!kthread_should_stop()) {
		ret = wait_event_interruptible(pool->prefill_waitq,
					       (pool->prefill_wait_flag == 1) ||
					       kthread_should_stop());
		if (ret < 0 || kthread_should_stop())
			continue;

		pool->prefill_wait_flag = 0;

		mutex_lock(&pool->prefill_mutex);
		timeout_jiffies = get_jiffies_64() + 2 * HZ;
		begin = jiffies;

		pr_info("prefill start >>>>> nr_page: %dMib high: %dMib.\n",
			M(dynamic_boost_pool_nr_pages(pool)), M(pool->high));

		for (i = 0; i < NUM_ORDERS; i++) {
			while (!pool->force_stop && !kthread_should_stop() &&
			       dynamic_boost_pool_nr_pages(pool) < pool->high) {
				/* support timeout to limit alloc pages. */
				if (time_after64(get_jiffies_64(), timeout_jiffies)) {
					pr_warn("prefill timeout.\n");
					break;
				}

				if (dynamic_boost_page_pool_refill(pool->pools[i]) < 0)
					break;
			}
		}

		pr_info("prefill end <<<<< nr_page: %dMib high:%dMib use %dms\n",
			M(dynamic_boost_pool_nr_pages(pool)), M(pool->high),
			jiffies_to_msecs(jiffies - begin));

		pool->high = max(dynamic_boost_pool_nr_pages(pool), pool->low);
		pool->prefill = false;
		mutex_unlock(&pool->prefill_mutex);
	}

	return 0;
}

static struct page *dynamic_boost_pool_alloc(struct dynamic_boost_pool *pool,
				      unsigned long size,
				      unsigned int max_order)
{
	int i;
	unsigned long flags;
	struct page *page = NULL;

	if (NULL == pool) {
		pr_err("%s: pool is NULL!\n", __func__);
		return NULL;
	}

	for (i = 0; i < NUM_ORDERS; i++) {
		if (size < order_to_size(orders[i]))
			continue;
		if (max_order < orders[i])
			continue;

		spin_lock_irqsave(&pool->pools[i]->lock, flags);
		if (pool->pools[i]->high_count)
			page = boost_page_pool_remove(pool->pools[i], true);
		else if (pool->pools[i]->low_count)
			page = boost_page_pool_remove(pool->pools[i], false);
		spin_unlock_irqrestore(&pool->pools[i]->lock, flags);

		if (!page)
			continue;
		return page;
	}
	return NULL;
}

static void dynamic_boost_pool_dec_high(struct dynamic_boost_pool *pool, int nr_pages)
{
	if (pool->prefill)
		return;

	if (unlikely(nr_pages < 0))
		return;

	pool->high = max(pool->low, pool->high - nr_pages);

	return;
}

static void dynamic_boost_pool_wakeup_process(struct dynamic_boost_pool *pool)
{
	if (!boost_pool_enable)
		return;

	if (NULL == pool) {
		pr_err("%s: boost_pool is NULL!\n", __func__);
		return;
	}

	/* if set force_stop, we can get page from ion_free instead of alloc pages */
	/* from system. it can reduce the system loading */
	if (!pool->force_stop && !pool->prefill) {
		pool->wait_flag = 1;
		wake_up_interruptible(&pool->waitq);
	}
}

static int dynamic_page_pool_do_shrink(struct dynamic_page_pool *pool, gfp_t gfp_mask,
				       int nr_to_scan)
{
	int freed = 0;
	bool high;
	struct page *page, *tmp;
	LIST_HEAD(pages);
	int ret = DYNAMIC_POOL_SUCCESS;
	unsigned long flags;

	if (current_is_kswapd())
		high = true;
	else
		high = !!(gfp_mask & __GFP_HIGHMEM);

	if (nr_to_scan == 0)
		return dynamic_page_pool_total(pool, high);

	while (freed < nr_to_scan) {
		spin_lock_irqsave(&pool->lock, flags);
		if (pool->low_count) {
			page = boost_page_pool_remove(pool, false);
		} else if (high && pool->high_count) {
			page = boost_page_pool_remove(pool, true);
		} else {
			spin_unlock_irqrestore(&pool->lock, flags);
			break;
		}
		spin_unlock_irqrestore(&pool->lock, flags);
		list_add(&page->lru, &pages);
		freed += (1 << pool->order);
	}

	if (freed && pool->prerelease_callback)
		ret = pool->prerelease_callback(pool, &pages, freed >> pool->order);

	if (ret != DYNAMIC_POOL_SUCCESS) {
		pr_err("Failed to reclaim secure page pool pages!\n");
		return 0;
	}

	list_for_each_entry_safe(page, tmp, &pages, lru) {
		list_del(&page->lru);
		__free_pages(page, pool->order);
	}

	return freed;
}

static void dynamic_boost_pool_all_free(struct dynamic_boost_pool *pool, gfp_t gfp_mask,
				int nr_to_scan)
{
	int i;

	if (NULL == pool) {
		pr_err("%s: boost_pool is NULL!\n", __func__);
		return;
	}

	for (i = 0; i < NUM_ORDERS; i++)
		dynamic_page_pool_do_shrink(pool->pools[i], gfp_mask, nr_to_scan);
}

static int dynamic_boost_pool_free(struct dynamic_boost_pool *pool, struct page *page,
		    int index)
{
	if (!boost_pool_enable) {
		dynamic_boost_pool_all_free(pool, __GFP_HIGHMEM, MAX_BOOST_POOL_HIGH);
		return -1;
	}

	if ((NULL == pool) || (NULL == page))
		return -1;

	if (dynamic_boost_pool_nr_pages(pool) > pool->low)
		return -1;

	boost_page_pool_free(pool->pools[index], page);
	return 0;
}

static int dynamic_boost_pool_do_shrink(struct dynamic_boost_pool *boost_pool,
				gfp_t gfp_mask, int nr_to_scan)
{
	int nr_max_free;
	int nr_to_free;
	int nr_freed;
	int nr_total = 0;
	int only_scan = 0;
	int i;

	if (NULL == boost_pool) {
		pr_err("%s: boostpool is NULL!\n", __func__);
		return 0;
	}

	if (boost_pool->tsk->pid == current->pid ||
	    boost_pool->prefill_tsk->pid == current->pid)
		return 0;

	if (!nr_to_scan) {
		only_scan = 1;
	} else {
		nr_max_free = dynamic_boost_pool_nr_pages(boost_pool) -
			(boost_pool->high + LOWORDER_WATER_MASK);
		nr_to_free = min(nr_max_free, nr_to_scan);
		if (nr_to_free <= 0)
			return 0;
	}

	for (i = 0; i < NUM_ORDERS; i++) {
		if (only_scan) {
			nr_total += dynamic_page_pool_do_shrink(boost_pool->pools[i],
								gfp_mask, nr_to_scan);
		} else {
			nr_freed = dynamic_page_pool_do_shrink(boost_pool->pools[i],
							       gfp_mask, nr_to_free);
			nr_to_free -= nr_freed;
			nr_total += nr_freed;
			if (nr_to_free <= 0)
				break;
		}
	}

	return nr_total;
}

static int dynamic_boost_pool_shrink(gfp_t gfp_mask, int nr_to_scan)
{
	struct dynamic_boost_pool *boost_pool;
	int nr_total = 0;
	int nr_freed;
	int only_scan = 0;

	if (!mutex_trylock(&boost_pool_list_lock))
		return 0;

	if (!nr_to_scan)
		only_scan = 1;

	list_for_each_entry(boost_pool, &boost_pool_list, list) {
		if (only_scan) {
			nr_total += dynamic_boost_pool_do_shrink(boost_pool,
								 gfp_mask,
								 nr_to_scan);
		} else {
			nr_freed = dynamic_boost_pool_do_shrink(boost_pool,
								gfp_mask,
								nr_to_scan);
			nr_to_scan -= nr_freed;
			nr_total += nr_freed;
			if (nr_to_scan <= 0)
				break;
		}
	}
	mutex_unlock(&boost_pool_list_lock);

	return nr_total;
}

static unsigned long dynamic_boost_pool_shrink_count(struct shrinker *shrinker,
						    struct shrink_control *sc)
{
	return dynamic_boost_pool_shrink(sc->gfp_mask, 0);
}

static unsigned long dynamic_boost_pool_shrink_scan(struct shrinker *shrinker,
						   struct shrink_control *sc)
{
	int to_scan = sc->nr_to_scan;

	if (to_scan == 0)
		return 0;

	return dynamic_boost_pool_shrink(sc->gfp_mask, to_scan);
}

static int dynamic_boost_pool_proc_show(struct seq_file *s, void *v)
{
	struct dynamic_boost_pool *boost_pool = s->private;
	int i;

	seq_printf(s, "Name:%s: %dMib, prefill: %d origin: %dMib low: %dMib high: %dMib\n",
		   boost_pool->name,
		   M(dynamic_boost_pool_nr_pages(boost_pool)),
		   boost_pool->prefill,
		   M(boost_pool->origin),
		   M(boost_pool->low),
		   M(boost_pool->high));

	for (i = 0; i < NUM_ORDERS; i++) {
		struct dynamic_page_pool *pool = boost_pool->pools[i];

		seq_printf(s, "%d order %u highmem pages in boost pool = %lu total\n",
			   pool->high_count, pool->order,
			   (PAGE_SIZE << pool->order) * pool->high_count);
		seq_printf(s, "%d order %u lowmem pages in boost pool = %lu total\n",
			   pool->low_count, pool->order,
			   (PAGE_SIZE << pool->order) * pool->low_count);
	}
	return 0;
}

static int dynamic_boost_pool_proc_open(struct inode *inode, struct file *file)
{
	struct dynamic_boost_pool *data = pde_data(inode);
	return single_open(file, dynamic_boost_pool_proc_show, data);
}

static ssize_t dynamic_boost_pool_proc_write(struct file *file,
				     const char __user *buf,
				     size_t count, loff_t *ppos)
{
	char buffer[13];
	int err, nr_pages;
	struct dynamic_boost_pool *boost_pool = pde_data(file_inode(file));

	if (IS_ERR_OR_NULL(boost_pool)) {
		pr_err("%s: boost pool is NULL.\n", current->comm);
		return -EFAULT;
	}

	memset(buffer, 0, sizeof(buffer));
	if (count > sizeof(buffer) - 1)
		count = sizeof(buffer) - 1;
	if (copy_from_user(buffer, buf, count))
		return -EFAULT;

	err = kstrtoint(strstrip(buffer), 0, &nr_pages);
	if(err)
		return err;

	if (nr_pages == 0) {
		pr_info("%s: reset flag.\n", current->comm);
		boost_pool->high = boost_pool->low = boost_pool->origin;
		boost_pool->force_stop = false;
		return count;
	}

	if (nr_pages == -1) {
		pr_info("%s: force stop.\n", current->comm);
		boost_pool->force_stop = true;
		return count;
	}

	if (nr_pages < 0 || nr_pages >= MAX_BOOST_POOL_HIGH ||
	    nr_pages <= boost_pool->low)
		return -EINVAL;

	if (mutex_trylock(&boost_pool->prefill_mutex)) {
		long mem_avail = si_mem_available();

		pr_info("%s: set high wm => %dMib. current avail => %ldMib\n",
			current->comm, M(nr_pages), M(mem_avail));

		boost_pool->prefill = true;
		boost_pool->force_stop = false;
		boost_pool->high = nr_pages;

		boost_pool->prefill_wait_flag = 1;
		wake_up_interruptible(&boost_pool->prefill_waitq);
		mutex_unlock(&boost_pool->prefill_mutex);
	} else {
		pr_err("%s: prefill already running. \n", current->comm);
		return -EBUSY;
	}

	return count;
}

static const struct proc_ops dynamic_boost_pool_proc_ops = {
	.proc_open	= dynamic_boost_pool_proc_open,
	.proc_read	= seq_read,
	.proc_write	= dynamic_boost_pool_proc_write,
	.proc_lseek	= seq_lseek,
	.proc_release	= single_release,
};

static int dynamic_boost_pool_low_proc_show(struct seq_file *s, void *v)
{
	struct dynamic_boost_pool *boost_pool = s->private;

	seq_printf(s, "low %dMib.\n", M(boost_pool->camera_pages));

	return 0;
}

static int dynamic_boost_pool_pages_proc_open(struct inode *inode, struct file *file)
{
	struct dynamic_boost_pool *data = pde_data(inode);
	return single_open(file, dynamic_boost_pool_low_proc_show, data);
}

static ssize_t dynamic_boost_pool_pages_proc_write(struct file *file,
						 const char __user *buf,
						 size_t count, loff_t *ppos)
{
	char buffer[13];
	int err, nr_pages;
	struct dynamic_boost_pool *boost_pool = pde_data(file_inode(file));

	if (IS_ERR_OR_NULL(boost_pool)) {
		pr_err("%s: boost pool is NULL.\n", current->comm);
		return -EFAULT;
	}

	memset(buffer, 0, sizeof(buffer));
	if (count > sizeof(buffer) - 1)
		count = sizeof(buffer) - 1;
	if (copy_from_user(buffer, buf, count))
		return -EFAULT;

	err = kstrtoint(strstrip(buffer), 0, &nr_pages);
	if(err)
		return err;

	if (nr_pages <= 0 || nr_pages >= MAX_BOOST_POOL_HIGH)
		return -EINVAL;

	boost_pool->camera_pages = nr_pages;
	nr_pages = boost_pool->camera_pages + boost_pool->sf_pages;
	boost_pool->origin = nr_pages;
	boost_pool->high = nr_pages;
	boost_pool->low = nr_pages;
	dynamic_boost_pool_wakeup_process(boost_pool);
	return count;
}

static const struct proc_ops dynamic_boost_pool_pages_proc_ops = {
	.proc_open	= dynamic_boost_pool_pages_proc_open,
	.proc_read	= seq_read,
	.proc_write	= dynamic_boost_pool_pages_proc_write,
	.proc_lseek	= seq_lseek,
	.proc_release	= single_release,
};

static int dynamic_boost_pool_stat_proc_show(struct seq_file *s, void *v)
{
	struct dynamic_boost_pool *boost_pool = s->private;

	seq_printf(s, "%d,%d,%ld\n",
		   M(dynamic_boost_pool_nr_pages(boost_pool)),
		   boost_pool->prefill, M(si_mem_available()));

	return 0;
}

static int dynamic_boost_pool_stat_proc_open(struct inode *inode, struct file *file)
{
	struct dynamic_boost_pool *data = pde_data(inode);
	return single_open(file, dynamic_boost_pool_stat_proc_show, data);
}

static const struct proc_ops dynamic_boost_pool_stat_proc_ops = {
	.proc_open	= dynamic_boost_pool_stat_proc_open,
	.proc_read	= seq_read,
	.proc_lseek	= seq_lseek,
	.proc_release	= single_release,
};

static struct shrinker boost_pool_shrinker = {
	.count_objects = dynamic_boost_pool_shrink_count,
	.scan_objects = dynamic_boost_pool_shrink_scan,
	.seeks = DEFAULT_SEEKS,
	.batch = 0,
};

static bool shrinker_registered;

static int dynamic_boost_pool_init_shrinker(void)
{
	int ret;

	if (shrinker_registered)
		return 0;

	ret = register_shrinker(&boost_pool_shrinker, "boost_pool_shrinker");
	if (ret)
		return ret;

	shrinker_registered = true;
	return 0;
}

static inline void set_cpumask(int end_cpu, struct cpumask *mask)
{
	int i;

	cpumask_clear(mask);
	for (i = 0; i <= end_cpu; i++)
		cpumask_set_cpu(i, mask);
}

/* limit max cpu here. in gki kernel CONFIG_NR_CPUS=32. */
#define MAX_SUPPORT_CPUS (8)
static ssize_t cpu_write(struct file *file, const char __user *buf,
			 size_t count, loff_t *ppos)
{
	char buffer[13];
	int err, cpu, i;
	struct cpumask cpu_mask = { CPU_BITS_NONE };
	struct dynamic_boost_pool *boost_pool = pde_data(file_inode(file));

	if (boost_pool == NULL)
		return -EFAULT;

	memset(buffer, 0, sizeof(buffer));
	if (count > sizeof(buffer) - 1)
		count = sizeof(buffer) - 1;
	if (copy_from_user(buffer, buf, count))
		return -EFAULT;
	err = kstrtoint(strstrip(buffer), 0, &cpu);
	if (err)
		return err;

	if (cpu < 0 || cpu >= MAX_SUPPORT_CPUS)
		return -EINVAL;

	for (i = 0; i <= cpu; i++)
		cpumask_set_cpu(i, &cpu_mask);

	set_cpus_allowed_ptr(boost_pool->prefill_tsk, &cpu_mask);

	pr_info("%s:%d set %s cpu [0-%d]\n",
		current->comm, current->tgid,
		boost_pool->prefill_tsk->comm, cpu);
	return count;
}

static int cpu_show(struct seq_file *s, void *unused)
{
	struct dynamic_boost_pool *boost_pool = s->private;

	seq_printf(s, "%*pbl\n",
		   cpumask_pr_args(boost_pool->prefill_tsk->cpus_ptr));
	return 0;
}
DEFINE_BOOST_POOL_PROC_RW_ATTRIBUTE(cpu);

static ssize_t camera_pid_write(struct file *file, const char __user *buf,
				size_t count, loff_t *ppos)
{
	char buffer[13];
	struct task_struct *task;
	int err, pid;
	struct dynamic_boost_pool *boost_pool = pde_data(file_inode(file));

	if (boost_pool == NULL)
		return -EFAULT;

	memset(buffer, 0, sizeof(buffer));
	if (count > sizeof(buffer) - 1)
		count = sizeof(buffer) - 1;
	if (copy_from_user(buffer, buf, count))
		return -EFAULT;
	err = kstrtoint(strstrip(buffer), 0, &pid);
	if (err)
		return err;

	if (pid == 0) {
		boost_pool->camera_pid = 0;
		pr_info("reset camera_pid\n");
		return count;
	}

	rcu_read_lock();
	task = find_task_by_vpid(pid);
	if (task != NULL) {
		pr_info("%s:%d set camera_pid %s:%d\n",
			current->comm, current->tgid,
			task->comm, task->tgid);
		boost_pool->camera_pid = task->tgid;
	}
	rcu_read_unlock();

	if (!task)
		return -EINVAL;

	return count;
}

static int camera_pid_show(struct seq_file *s, void *unused)
{
	struct dynamic_boost_pool *boost_pool = s->private;

	seq_printf(s, "%d\n", boost_pool->camera_pid);
	return 0;
}
DEFINE_BOOST_POOL_PROC_RW_ATTRIBUTE(camera_pid);

static void dynamic_boost_pool_destroy(struct dynamic_boost_pool *pool)
{
	mutex_lock(&boost_pool_list_lock);
	if (!list_empty(&pool->list))
		list_del_init(&pool->list);
	mutex_unlock(&boost_pool_list_lock);

	dynamic_page_pool_release_pools_new(pool->pools);
	return;
}

static void dynamic_boost_pool_remove_proc(struct dynamic_boost_pool *boost_pool)
{
	proc_remove(boost_pool->proc_pid);
	proc_remove(boost_pool->proc_cpu);
	proc_remove(boost_pool->proc_stat);
	proc_remove(boost_pool->proc_pages);
	proc_remove(boost_pool->proc_info);
}

static struct dynamic_boost_pool *dynamic_boost_pool_create(int sf_pages,
							    int camera_pages,
							    struct proc_dir_entry *root_dir,
							    char *name)
{
	struct task_struct *tsk;
	struct dynamic_boost_pool *boost_pool;
	char buf[128];
	struct cpumask mask;
	int end_cpu = 3;
	int ret = 0;
	int nr_pages;

	if (NULL == root_dir) {
		pr_err("%s: boost_pool dir not exits.\n", __func__);
		return NULL;
	}

	ret = dynamic_boost_pool_init_shrinker();
	if (ret) {
		pr_err("%s: boost_pool init shrinker failed.\n", __func__);
		return NULL;
	}

	boost_pool = kzalloc(sizeof(struct dynamic_boost_pool) +
			     sizeof(struct dynamic_page_pool *) * NUM_ORDERS,
			     GFP_KERNEL);

	if (NULL == boost_pool) {
		pr_err("%s: boost_pool is NULL!\n", __func__);
		return NULL;
	}
	INIT_LIST_HEAD(&boost_pool->list);

	boost_pool->pools = dynamic_page_pool_create_pools_new(0, NULL);
	if (IS_ERR(boost_pool->pools)) {
		kfree(boost_pool);
		return NULL;
	}

	boost_pool->sf_pages = sf_pages;
	boost_pool->camera_pages = camera_pages;
	/* PKX110 keeps 1 while no camera session owns the reserve. */
	boost_pool->camera_pid = 1;
	nr_pages = sf_pages + camera_pages;
	boost_pool->origin = nr_pages;
	boost_pool->high = nr_pages;
	boost_pool->low = nr_pages;

	boost_pool->name = name;

	boost_pool->proc_info = proc_create_data(name, 0666,
				     root_dir,
				     &dynamic_boost_pool_proc_ops,
				     boost_pool);
	if (IS_ERR_OR_NULL(boost_pool->proc_info)) {
		pr_err("Unable to initialise /proc/boost_pool/%s\n",
			name);
		goto destroy_pools;
	}

	snprintf(buf, 128, "%s_pages", name);
	boost_pool->proc_pages = proc_create_data(buf, 0666, root_dir,
					     &dynamic_boost_pool_pages_proc_ops,
					     boost_pool);
	if (IS_ERR_OR_NULL(boost_pool->proc_pages)) {
		pr_err("Unable to initialise /proc/boost_pool/%s_low\n",
			name);
		goto destroy_proc;
	}

	snprintf(buf, 128, "%s_stat", name);
	boost_pool->proc_stat = proc_create_data(buf, 0444,
						 root_dir,
						 &dynamic_boost_pool_stat_proc_ops,
						 boost_pool);
	if (IS_ERR_OR_NULL(boost_pool->proc_stat)) {
		pr_info("Unable to initialise /proc/boost_pool/%s_stat\n",
			name);
		goto destroy_proc;
	}

	snprintf(buf, 128, "%s_cpu", name);
	boost_pool->proc_cpu = proc_create_data(buf, 0666, root_dir, &cpu_proc_ops,
				    boost_pool);
	if (!boost_pool->proc_cpu) {
		pr_err("create proc_fs cpu failed\n");
		goto destroy_proc;
	}

	snprintf(buf, 128, "%s_pid", name);
	boost_pool->proc_pid = proc_create_data(buf, 0666, root_dir, &camera_pid_proc_ops,
				    boost_pool);
	if (!boost_pool->proc_pid) {
		pr_err("create proc_fs cpu failed\n");
		goto destroy_proc;
	}

	init_waitqueue_head(&boost_pool->waitq);
	tsk = kthread_run(dynamic_boost_pool_kworkthread, boost_pool,
			  "bp_%s", name);

	if (IS_ERR_OR_NULL(tsk)) {
		pr_err("%s: kthread_create failed!\n", __func__);
		goto destroy_proc;
	}
	boost_pool->tsk = tsk;
	/* FIXME, we should not use magic number.. */
	set_cpumask(end_cpu, &mask);
	set_cpus_allowed_ptr(tsk, &mask);

	mutex_init(&boost_pool->prefill_mutex);
	init_waitqueue_head(&boost_pool->prefill_waitq);
	tsk = kthread_run(dynamic_boost_pool_prefill_kworkthread, boost_pool,
			  "bp_prefill_%s", name);
	if (IS_ERR_OR_NULL(tsk)) {
		pr_err("%s: kthread_create failed!\n", __func__);
		goto stop_tsk;
	}
	boost_pool->prefill_tsk = tsk;
	set_cpus_allowed_ptr(tsk, &mask);

	dynamic_boost_pool_wakeup_process(boost_pool);

	mutex_lock(&boost_pool_list_lock);
	list_add(&boost_pool->list, &boost_pool_list);
	mutex_unlock(&boost_pool_list_lock);
	return boost_pool;

stop_tsk:
	kthread_stop(boost_pool->tsk);
destroy_proc:
	dynamic_boost_pool_remove_proc(boost_pool);
destroy_pools:
	dynamic_boost_pool_destroy(boost_pool);

	kfree(boost_pool);
	boost_pool = NULL;
	return NULL;
}

static struct proc_dir_entry *boost_root_dir;

static struct dynamic_boost_pool *dynamic_boost_pool_create_pack(void)
{
	struct dynamic_boost_pool *boost_pool = NULL;

	boost_root_dir = proc_mkdir("boost_pool", NULL);
	if (!IS_ERR_OR_NULL(boost_root_dir)) {
		int sf_pages = PAGES(SZ_32M), camera_pages = 0;

		/* SZ_4G is ULL */
		if (totalram_pages() > PAGES(SZ_4G)) {
			sf_pages = PAGES(SZ_64M);
			camera_pages = PAGES(SZ_128M);
		}

		boost_pool = dynamic_boost_pool_create(sf_pages, camera_pages,
						       boost_root_dir, "camera");
		if (NULL == boost_pool)
			pr_err("%s: create boost_pool camera failed!\n", __func__);
	} else {
		pr_err("%s: create boost_root_dir boost_pool failed.\n", __func__);
	}

	return boost_pool;
}

/* ================= 移植层：接入联想 vendor_boot 里的 qcom_dma_heaps ================= */

static char camera_comm[TASK_COMM_LEN] = "ider-service_64";
module_param_string(camera_comm, camera_comm, sizeof(camera_comm), 0644);
MODULE_PARM_DESC(camera_comm, "group-leader comm of the camera provider HAL, bound as camera_pid on its first allocation (empty = off)");

static atomic64_t served_pages = ATOMIC64_INIT(0);
static atomic64_t refilled_pages = ATOMIC64_INIT(0);

static struct dynamic_boost_pool *camera_boost_pool;
static struct dma_heap *system_heap;
static struct dynamic_page_pool **sys_pools;	/* 联想 qcom_system_heap::pool_list */

#define MAX_CALL_SITES 4
static unsigned long alloc_sites[MAX_CALL_SITES];
static unsigned long free_sites[MAX_CALL_SITES];
static int nr_alloc_sites, nr_free_sites;

/* 原厂 alloc_pack 结束时把 max_order 复位到最高阶；记下本轮拿过池页的 tgid。 */
#define SERVED_SLOTS 8
static pid_t served_tgids[SERVED_SLOTS];
static unsigned int served_next;
static DEFINE_SPINLOCK(served_lock);

static void note_served(pid_t tgid)
{
	unsigned long flags;
	int i;

	spin_lock_irqsave(&served_lock, flags);
	for (i = 0; i < SERVED_SLOTS; i++) {
		if (served_tgids[i] == tgid)
			goto out;
	}
	served_tgids[served_next++ % SERVED_SLOTS] = tgid;
out:
	spin_unlock_irqrestore(&served_lock, flags);
}

static bool take_served(pid_t tgid)
{
	unsigned long flags;
	bool found = false;
	int i;

	spin_lock_irqsave(&served_lock, flags);
	for (i = 0; i < SERVED_SLOTS; i++) {
		if (served_tgids[i] == tgid) {
			served_tgids[i] = 0;
			found = true;
			break;
		}
	}
	spin_unlock_irqrestore(&served_lock, flags);
	return found;
}

static bool is_call_site(unsigned long lr, const unsigned long *sites, int nr)
{
	int i;

	for (i = 0; i < nr; i++) {
		if (sites[i] == lr)
			return true;
	}
	return false;
}

/* 代替联想 HAL 写 camera_pid（原厂 HAL 会话开始写自己的 tgid） */
static void bind_camera_pid(struct dynamic_boost_pool *pool)
{
	struct task_struct *leader = current->group_leader;

	if (!camera_comm[0] || pool->camera_pid == current->tgid)
		return;
	if (strncmp(leader->comm, camera_comm, TASK_COMM_LEN))
		return;
	WRITE_ONCE(pool->camera_pid, current->tgid);
	pr_info_ratelimited("auto set camera_pid %s:%d\n", leader->comm, current->tgid);
}

/*
 * qcom_sys_heap_alloc_largest_available(pools, size, max_order, movable)
 * 对应原厂 dynamic_boost_pool_alloc_pack()。
 */
static int alloc_pre_handler(struct kprobe *p, struct pt_regs *regs)
{
	struct dynamic_boost_pool *pool = READ_ONCE(camera_boost_pool);
	unsigned long lr = procedure_link_pointer(regs);
	struct page *page;

	if (!pool || (void *)regs->regs[0] != sys_pools ||
	    !is_call_site(lr, alloc_sites, nr_alloc_sites))
		return 0;

	bind_camera_pid(pool);

	/*
	 * Preserve the camera reserve while idle.  A camera session owner may drain
	 * it; all other qcom,system clients may use only the SF headroom above it.
	 */
	if (READ_ONCE(pool->camera_pid) != current->tgid &&
	    dynamic_boost_pool_nr_pages(pool) < pool->camera_pages)
		goto miss;

	page = dynamic_boost_pool_alloc(pool, regs->regs[1], (unsigned int)regs->regs[2]);
	if (!page)
		goto miss;

	dynamic_boost_pool_dec_high(pool, 1 << compound_order(page));
	atomic64_add(1 << compound_order(page), &served_pages);
	note_served(current->tgid);

	regs_set_return_value(regs, (unsigned long)page);
	instruction_pointer_set(regs, lr);
	return 1;

miss:
	if ((unsigned int)regs->regs[2] < orders[0] && take_served(current->tgid))
		regs->regs[2] = orders[0];
	return 0;
}

/*
 * dynamic_page_pool_free(pool_list[j], page)，只来自 system_heap_buf_free 的
 * DF_NORMAL 分支（页已清零）。对应原厂 dynamic_boost_pool_free()。
 */
static int free_pre_handler(struct kprobe *p, struct pt_regs *regs)
{
	struct dynamic_boost_pool *pool = READ_ONCE(camera_boost_pool);
	unsigned long lr = procedure_link_pointer(regs);
	struct dynamic_page_pool *target = (void *)regs->regs[0];
	struct page *page = (struct page *)regs->regs[1];
	int j;

	if (!pool || !page || !is_call_site(lr, free_sites, nr_free_sites))
		return 0;

	for (j = 0; j < NUM_ORDERS; j++) {
		if (compound_order(page) == orders[j])
			break;
	}
	if (j == NUM_ORDERS || target != sys_pools[j])
		return 0;

	if (dynamic_boost_pool_free(pool, page, j))
		return 0;

	atomic64_add(1 << orders[j], &refilled_pages);
	instruction_pointer_set(regs, lr);
	return 1;
}

static struct kprobe alloc_kp = {
	.symbol_name = "qcom_sys_heap_alloc_largest_available",
	.pre_handler = alloc_pre_handler,
};

static struct kprobe free_kp = {
	.symbol_name = "dynamic_page_pool_free",
	.pre_handler = free_pre_handler,
};

static unsigned long resolve_symbol(const char *name)
{
	struct kprobe kp = { .symbol_name = name };
	unsigned long addr;

	if (register_kprobe(&kp))
		return 0;
	addr = (unsigned long)kp.addr;
	unregister_kprobe(&kp);
	return addr;
}

/* "name+0x0/0x2c0 [qcom_dma_heaps]" -> 0x2c0 */
static unsigned long symbol_size(unsigned long addr)
{
	char buf[KSYM_SYMBOL_LEN];
	char *slash;
	unsigned long size;

	sprint_symbol(buf, addr);
	slash = strchr(buf, '/');
	if (!slash)
		return 0;
	if (sscanf(slash + 1, "%lx", &size) != 1)
		return 0;
	return size;
}

/* 在 caller 函数体里找所有 "bl callee"，记录返回地址 */
static int find_call_sites(const char *caller_name, unsigned long callee,
			   unsigned long *sites)
{
	unsigned long caller = resolve_symbol(caller_name);
	unsigned long size, pc;
	int nr = 0;

	if (!caller)
		return -ENOENT;
	size = symbol_size(caller);
	if (!size || size > SZ_16K)
		return -EINVAL;

	for (pc = caller; pc + 4 <= caller + size; pc += 4) {
		u32 insn = le32_to_cpu(*(__le32 *)pc);
		s64 off;

		if ((insn & 0xfc000000) != 0x94000000)
			continue;
		off = sign_extend64(insn & 0x03ffffff, 25) * 4;
		if (pc + off != callee)
			continue;
		if (nr == MAX_CALL_SITES)
			return -E2BIG;
		sites[nr++] = pc + 4;
	}
	return nr;
}

static int bind_qcom_system_heap(void)
{
	struct { int uncached; struct dynamic_page_pool **pool_list; } *sys_heap;
	unsigned long alloc_fn, free_fn;
	int i, ret;

	system_heap = dma_heap_find("qcom,system");
	if (!system_heap) {
		pr_err("qcom,system heap not found\n");
		return -ENODEV;
	}
	sys_heap = dma_heap_get_drvdata(system_heap);
	if (!sys_heap || !virt_addr_valid(sys_heap) ||
	    !virt_addr_valid(sys_heap->pool_list)) {
		pr_err("unexpected qcom_system_heap layout\n");
		return -EINVAL;
	}
	sys_pools = sys_heap->pool_list;
	for (i = 0; i < NUM_ORDERS; i++) {
		if (!virt_addr_valid(sys_pools[i])) {
			pr_err("unexpected pool_list[%d]\n", i);
			return -EINVAL;
		}
	}

	alloc_fn = resolve_symbol(alloc_kp.symbol_name);
	free_fn = resolve_symbol(free_kp.symbol_name);
	if (!alloc_fn || !free_fn) {
		pr_err("qcom_dma_heaps symbols not found\n");
		return -ENOENT;
	}

	ret = find_call_sites("system_qcom_sg_buffer_alloc", alloc_fn, alloc_sites);
	if (ret <= 0) {
		pr_err("alloc call site not found (%d)\n", ret);
		return ret ? ret : -ENOENT;
	}
	nr_alloc_sites = ret;

	ret = find_call_sites("system_heap_buf_free", free_fn, free_sites);
	if (ret <= 0) {
		pr_err("free call site not found (%d)\n", ret);
		return ret ? ret : -ENOENT;
	}
	nr_free_sites = ret;

	pr_info("bound qcom,system: alloc sites %d, free sites %d\n",
		nr_alloc_sites, nr_free_sites);
	return 0;
}

static void boost_pool_teardown(struct dynamic_boost_pool *pool)
{
	if (pool->prefill_tsk)
		kthread_stop(pool->prefill_tsk);
	if (pool->tsk)
		kthread_stop(pool->tsk);
	dynamic_boost_pool_remove_proc(pool);
	dynamic_boost_pool_destroy(pool);
	kfree(pool);
}

static void boost_pool_unregister_shrinker(void)
{
	if (!shrinker_registered)
		return;

	unregister_shrinker(&boost_pool_shrinker);
	shrinker_registered = false;
}

static int __init oplus_boost_pool_init(void)
{
	struct dynamic_boost_pool *pool;
	int ret;

	ret = bind_qcom_system_heap();
	if (ret)
		goto put_heap;

	pool = dynamic_boost_pool_create_pack();
	if (!pool) {
		ret = -ENOMEM;
		goto remove_dir;
	}

	ret = register_kprobe(&free_kp);
	if (ret) {
		pr_err("register free kprobe failed %d\n", ret);
		goto destroy_pool;
	}
	ret = register_kprobe(&alloc_kp);
	if (ret) {
		pr_err("register alloc kprobe failed %d\n", ret);
		unregister_kprobe(&free_kp);
		goto destroy_pool;
	}

	WRITE_ONCE(camera_boost_pool, pool);
	pr_info("camera pool origin %dMib (sf %dMib + camera %dMib), camera_comm=%s\n",
		M(pool->origin), M(pool->sf_pages), M(pool->camera_pages), camera_comm);
	return 0;

destroy_pool:
	boost_pool_teardown(pool);
remove_dir:
	boost_pool_unregister_shrinker();
	proc_remove(boost_root_dir);
	boost_root_dir = NULL;
put_heap:
	if (system_heap) {
		dma_heap_put(system_heap);
		system_heap = NULL;
	}
	return ret;
}

static void __exit oplus_boost_pool_exit(void)
{
	struct dynamic_boost_pool *pool = camera_boost_pool;

	WRITE_ONCE(camera_boost_pool, NULL);
	unregister_kprobe(&alloc_kp);
	unregister_kprobe(&free_kp);
	boost_pool_unregister_shrinker();
	if (pool)
		boost_pool_teardown(pool);
	proc_remove(boost_root_dir);
	if (system_heap)
		dma_heap_put(system_heap);
	pr_info("unloaded; served %lld pages, refilled %lld pages\n",
		(long long)atomic64_read(&served_pages),
		(long long)atomic64_read(&refilled_pages));
}

module_init(oplus_boost_pool_init);
module_exit(oplus_boost_pool_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("OPlus dma-buf camera boost pool on the Lenovo qcom_dma_heaps");
MODULE_IMPORT_NS(DMA_BUF);
