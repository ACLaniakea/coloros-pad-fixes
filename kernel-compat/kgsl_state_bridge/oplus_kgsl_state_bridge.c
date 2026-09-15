// SPDX-License-Identifier: GPL-2.0-only
/*
 * Android oom_score_adj -> Qualcomm KGSL reclaim-state bridge (v4.1.1).
 *
 * 为什么需要它
 * ------------
 * 联想这版 msm_kgsl 用的是高通较老的 "process reclaim" 设计：每个进程的 GPU
 * 内存分配在 shmem 文件里，并被钉成 unevictable，直到有人把该进程标记为
 * background 才解钉。移植包里缺了负责写
 * /sys/class/kgsl/kgsl/proc/<tgid>/state 的那个厂商组件，于是所有进程恒为
 * foreground，链条在第一环就断了：
 *
 *   没人写 state -> 进程恒在 PINNED 态 -> kgsl_reclaim_shrink_count_objects()
 *   恒返回 0 -> 内核永远不调用 scan_objects -> 显存永久不可回收。
 *
 * 实测（2026-09-14，r3 内核）：压力下 count_objects 被 do_shrink_slab 调用
 * 1328 次，每次都返回 0，scan_objects 命中 0 次。手工把桌面标成 background
 * 后，count 立刻返回 0xc800，scan 开始执行，12 秒内吐出 119~150MB，
 * Unevictable 从 533MB 降到 410MB。还原 foreground 只花 11ms 且不阻塞，
 * 1 秒内重新钉住，桌面全程无异常。
 *
 * 实现上的两条红线
 * ----------------
 * 1. 不调用 msm_kgsl 的私有函数。上一版用 kprobe 取 kgsl_proc_state_store
 *    的地址再间接调用，并猜 kobject 在 kgsl_process_private 里的偏移
 *    (+0x68)。kCFI 会拒绝这种间接调用，实测直接 panic，pstore 里留有
 *    "CFI failure ... target: kgsl_proc_state_store"。偏移也是猜的。
 * 2. 只走公开接口：filp_open + kernel_write 写那个 sysfs 节点，和 shell
 *    里 `echo background > .../state` 完全等价。
 *
 * 需要配套的 SELinux 放行（见 fix-module/module/sepolicy.rule）：
 *   allow kernel vendor_sysfs_kgsl_proc file { open write getattr };
 *   allow kernel vendor_sysfs_kgsl_proc dir  { search };
 * 少了它 filp_open 会返回 -EACCES —— 这是上一版真正卡住的地方。
 *
 * v4.1.1 改版：shrinker 驱动而非熄屏回收
 * ---------------------------------------
 * v4.1.0 使用熄屏全量回收（30 分钟延迟），副作用明确：解锁时桌面 95 分位帧时
 * 从 24ms 飙到 101~121ms（4~5 倍），原因是桌面几百 MB GPU 内存被收掉后需要
 * 重新钉住。
 *
 * v4.1.1 改为注册 shrinker：内核在真有内存压力时调用 count_objects，那一刻
 * 触发一次全量标记（adj>=100 -> background），返回 0。零主动轮询、不依赖屏幕
 * 状态，更接近对照机手机的行为（那边 GPU 内存本就不钉，压力来时内核自己回收）。
 *
 * adj 阈值默认 100（VISIBLE_APP）。能回收的量几乎全在桌面身上：adj>=700 的进程
 * 加起来只有 21MB，而桌面单进程用过一阵后是 129~893MB，被应用遮挡时 adj 正好
 * 100。KGSL 自己带迟滞（写 background 后约 12 秒 background_work 才真正回收），
 * 12 秒内切回来的进程一页都不会被动。
 */

#define pr_fmt(fmt) "oplus_kgsl_state_bridge: " fmt

#include <linux/err.h>
#include <linux/fcntl.h>
#include <linux/fs.h>
#include <linux/jiffies.h>
#include <linux/kprobes.h>
#include <linux/mm.h>
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/oom.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/shrinker.h>
#include <linux/spinlock.h>
#include <linux/tracepoint.h>
#include <linux/workqueue.h>

#define KGSL_STATE_PATH_LEN 64
#define STATE_SLOT_COUNT 256

static int adj_threshold = 100;
module_param(adj_threshold, int, 0644);
MODULE_PARM_DESC(adj_threshold, "oom_score_adj at or above which a process is marked background");

static unsigned int shrinker_throttle_sec = 30;
module_param(shrinker_throttle_sec, uint, 0644);
MODULE_PARM_DESC(shrinker_throttle_sec, "minimum seconds between shrinker-triggered sweeps");

static bool bridge_enabled = true;
module_param(bridge_enabled, bool, 0644);
MODULE_PARM_DESC(bridge_enabled, "master switch; turning it off stops new writes");

struct kgsl_state_slot {
	pid_t tgid;
	bool background;
	bool pending;
};

static struct tracepoint *oom_adj_tracepoint;
static struct kgsl_state_slot state_slots[STATE_SLOT_COUNT];
static DEFINE_SPINLOCK(state_lock);

static atomic64_t events_seen = ATOMIC64_INIT(0);
static atomic64_t writes_ok = ATOMIC64_INIT(0);
static atomic64_t nodes_missing = ATOMIC64_INIT(0);
static atomic64_t writes_failed = ATOMIC64_INIT(0);
static atomic64_t slots_full = ATOMIC64_INIT(0);
static atomic64_t shrinker_calls = ATOMIC64_INIT(0);
static atomic64_t shrinker_sweeps = ATOMIC64_INIT(0);

/* 只读统计 */
static int stat_events_get(char *buf, const struct kernel_param *kp)
{
	return sysfs_emit(buf, "%lld\n", atomic64_read(&events_seen));
}
static int stat_writes_get(char *buf, const struct kernel_param *kp)
{
	return sysfs_emit(buf, "%lld\n", atomic64_read(&writes_ok));
}
static int stat_missing_get(char *buf, const struct kernel_param *kp)
{
	return sysfs_emit(buf, "%lld\n", atomic64_read(&nodes_missing));
}
static int stat_failed_get(char *buf, const struct kernel_param *kp)
{
	return sysfs_emit(buf, "%lld\n", atomic64_read(&writes_failed));
}
static int stat_full_get(char *buf, const struct kernel_param *kp)
{
	return sysfs_emit(buf, "%lld\n", atomic64_read(&slots_full));
}
static int stat_shrinker_calls_get(char *buf, const struct kernel_param *kp)
{
	return sysfs_emit(buf, "%lld\n", atomic64_read(&shrinker_calls));
}
static int stat_shrinker_sweeps_get(char *buf, const struct kernel_param *kp)
{
	return sysfs_emit(buf, "%lld\n", atomic64_read(&shrinker_sweeps));
}

static const struct kernel_param_ops stat_events_ops = { .get = stat_events_get };
static const struct kernel_param_ops stat_writes_ops = { .get = stat_writes_get };
static const struct kernel_param_ops stat_missing_ops = { .get = stat_missing_get };
static const struct kernel_param_ops stat_failed_ops = { .get = stat_failed_get };
static const struct kernel_param_ops stat_full_ops = { .get = stat_full_get };
static const struct kernel_param_ops stat_shrinker_calls_ops = { .get = stat_shrinker_calls_get };
static const struct kernel_param_ops stat_shrinker_sweeps_ops = { .get = stat_shrinker_sweeps_get };

module_param_cb(stat_events, &stat_events_ops, NULL, 0444);
module_param_cb(stat_writes, &stat_writes_ops, NULL, 0444);
module_param_cb(stat_missing, &stat_missing_ops, NULL, 0444);
module_param_cb(stat_failed, &stat_failed_ops, NULL, 0444);
module_param_cb(stat_slots_full, &stat_full_ops, NULL, 0444);
module_param_cb(stat_shrinker_calls, &stat_shrinker_calls_ops, NULL, 0444);
module_param_cb(stat_shrinker_sweeps, &stat_shrinker_sweeps_ops, NULL, 0444);

/*
 * 和 shell 里 `echo background > /sys/class/kgsl/kgsl/proc/<tgid>/state`
 * 等价。全部是导出符号的直接调用，没有间接调用，kCFI 无话可说。
 */
static int write_kgsl_state(pid_t tgid, bool background)
{
	static const char bg[] = "background";
	static const char fg[] = "foreground";
	char path[KGSL_STATE_PATH_LEN];
	const char *state = background ? bg : fg;
	size_t len = background ? sizeof(bg) - 1 : sizeof(fg) - 1;
	struct file *filp;
	loff_t pos = 0;
	ssize_t ret;

	snprintf(path, sizeof(path), "/sys/class/kgsl/kgsl/proc/%d/state", tgid);

	filp = filp_open(path, O_WRONLY, 0);
	if (IS_ERR(filp)) {
		ret = PTR_ERR(filp);
		/* 进程已退出时节点随之消失，这是正常情况，不算失败 */
		return (ret == -ENOENT) ? -ENOENT : (int)ret;
	}

	ret = kernel_write(filp, state, len, &pos);
	filp_close(filp, NULL);

	if (ret < 0)
		return (int)ret;
	return (ret == len) ? 0 : -EIO;
}

static void kgsl_state_workfn(struct work_struct *work);
static DECLARE_WORK(kgsl_state_work, kgsl_state_workfn);

static void kgsl_state_workfn(struct work_struct *work)
{
	unsigned long flags;
	pid_t tgid;
	bool background;
	int i, ret;

	for (;;) {
		tgid = 0;
		spin_lock_irqsave(&state_lock, flags);
		for (i = 0; i < STATE_SLOT_COUNT; i++) {
			if (!state_slots[i].pending)
				continue;
			tgid = state_slots[i].tgid;
			background = state_slots[i].background;
			state_slots[i].pending = false;
			break;
		}
		spin_unlock_irqrestore(&state_lock, flags);
		if (!tgid)
			break;

		ret = write_kgsl_state(tgid, background);
		if (!ret) {
			if (atomic64_inc_return(&writes_ok) == 1)
				pr_info("首次 state 写入成功\n");
		} else if (ret == -ENOENT) {
			atomic64_inc(&nodes_missing);
		} else {
			if (atomic64_inc_return(&writes_failed) == 1)
				pr_warn("首次写入失败: %d（缺 SELinux 放行时是 -13）\n",
					ret);
		}
	}
}

static void oom_score_adj_update_probe(void *unused, struct task_struct *task)
{
	struct kgsl_state_slot *slot = NULL;
	unsigned long flags;
	pid_t tgid;
	bool background;
	int i, adj;

	if (!READ_ONCE(bridge_enabled))
		return;
	if (!task || !task->signal)
		return;
	tgid = task_tgid_nr(task);
	if (tgid <= 0)
		return;

	adj = READ_ONCE(task->signal->oom_score_adj);
	background = adj >= READ_ONCE(adj_threshold);
	atomic64_inc(&events_seen);

	/* tracepoint 上下文里不做任何分配。三级查找：先认本进程已有的槽，
	 * 再用从未使用过的空槽，最后才回收别人用完的槽。 */
	spin_lock_irqsave(&state_lock, flags);
	for (i = 0; i < STATE_SLOT_COUNT; i++) {
		if (state_slots[i].tgid == tgid) {
			slot = &state_slots[i];
			break;
		}
	}
	if (!slot) {
		for (i = 0; i < STATE_SLOT_COUNT; i++) {
			if (!state_slots[i].tgid) {
				slot = &state_slots[i];
				break;
			}
		}
	}
	if (!slot) {
		for (i = 0; i < STATE_SLOT_COUNT; i++) {
			if (!state_slots[i].pending) {
				slot = &state_slots[i];
				break;
			}
		}
	}
	if (slot && (slot->tgid != tgid || slot->background != background ||
		     slot->pending)) {
		slot->tgid = tgid;
		slot->background = background;
		slot->pending = true;
	}
	spin_unlock_irqrestore(&state_lock, flags);
	if (slot)
		schedule_work(&kgsl_state_work);
	else
		atomic64_inc(&slots_full);
}

static void find_oom_adj_tracepoint(struct tracepoint *tp, void *unused)
{
	if (!strcmp(tp->name, "oom_score_adj_update"))
		oom_adj_tracepoint = tp;
}

/*
 * 全量对齐。两种场合用它：
 *   1. 模块加载时——tracepoint 只在 adj "变化"时触发，加载前就稳定下来的
 *      进程永远等不到事件。
 *   2. shrinker 触发时——内核有内存压力才调用 count_objects，那一刻全量
 *      标记一遍，adj>=100 的统统 background。
 *
 * 保留 adj<0 的系统进程（SurfaceFlinger -1000 / system_server -900 /
 * SystemUI -800），它们占用很少但对解锁动画必需，不回收。
 */
static void kgsl_state_sweep(bool force_background)
{
	struct task_struct *p;
	pid_t *tgids;
	bool *bgs;
	int n = 0, i, cap = 0;
	int adj;

	/* nr_threads 没有导出给模块用，先数一遍再分配 */
	rcu_read_lock();
	for_each_process(p)
		cap++;
	rcu_read_unlock();
	cap += 64;

	tgids = kvmalloc_array(cap, sizeof(*tgids), GFP_KERNEL);
	bgs = kvmalloc_array(cap, sizeof(*bgs), GFP_KERNEL);
	if (!tgids || !bgs)
		goto out;

	rcu_read_lock();
	for_each_process(p) {
		if (!p->signal || (p->flags & PF_KTHREAD))
			continue;
		if (n >= cap)
			break;
		adj = READ_ONCE(p->signal->oom_score_adj);

		/* 始终保留 adj<0 的常驻系统进程 */
		tgids[n] = task_tgid_nr(p);
		bgs[n] = (force_background && adj >= 0) ||
			 adj >= READ_ONCE(adj_threshold);
		n++;
	}
	rcu_read_unlock();

	/* filp_open 会睡眠，必须离开 RCU 临界区之后再写 */
	for (i = 0; i < n; i++) {
		int ret = write_kgsl_state(tgids[i], bgs[i]);

		if (!ret)
			atomic64_inc(&writes_ok);
		else if (ret == -ENOENT)
			atomic64_inc(&nodes_missing);
		else
			atomic64_inc(&writes_failed);
	}
	pr_info("全量对齐：%d 个进程，force_background=%d\n", n, force_background);
out:
	kvfree(tgids);
	kvfree(bgs);
}

/* shrinker 节流：同一个回调可能连续打进来很多次，不能每次都全量扫 */
static unsigned long last_shrinker_sweep_jiffies;

static unsigned long kgsl_shrinker_count_objects(struct shrinker *s,
						 struct shrink_control *sc)
{
	unsigned long now;
	unsigned int throttle;

	atomic64_inc(&shrinker_calls);

	if (!READ_ONCE(bridge_enabled))
		return 0;

	now = jiffies;
	throttle = READ_ONCE(shrinker_throttle_sec);
	if (time_before(now, last_shrinker_sweep_jiffies +
			     msecs_to_jiffies(throttle * 1000U)))
		return 0;

	last_shrinker_sweep_jiffies = now;
	atomic64_inc(&shrinker_sweeps);

	/* 触发全量标记。不阻塞在这里，把写操作丢给 work queue 处理。
	 * 返回 0：告诉内核"我已触发标记，但实际能回收多少由 KGSL 自己的
	 * shrinker 去报"。 */
	kgsl_state_sweep(false);
	return 0;
}

static unsigned long kgsl_shrinker_scan_objects(struct shrinker *s,
						struct shrink_control *sc)
{
	/* 不做实际回收，回 SHRINK_STOP */
	return SHRINK_STOP;
}

static struct shrinker kgsl_state_shrinker = {
	.count_objects = kgsl_shrinker_count_objects,
	.scan_objects = kgsl_shrinker_scan_objects,
	.seeks = DEFAULT_SEEKS,
};

static int __init kgsl_state_bridge_init(void)
{
	int ret;

	for_each_kernel_tracepoint(find_oom_adj_tracepoint, NULL);
	if (!oom_adj_tracepoint) {
		pr_err("找不到 oom_score_adj_update tracepoint\n");
		return -ENOENT;
	}

	ret = tracepoint_probe_register(oom_adj_tracepoint,
					(void *)oom_score_adj_update_probe, NULL);
	if (ret) {
		pr_err("注册 tracepoint 失败: %d\n", ret);
		return ret;
	}

	ret = register_shrinker(&kgsl_state_shrinker);
	if (ret) {
		pr_warn("注册 shrinker 失败 (%d)，内存压力触发不可用\n", ret);
		/* 不致命，继续运行，tracepoint 仍可用 */
	}

	/* 初始全量对齐 */
	kgsl_state_sweep(false);

	pr_info("已加载，adj 阈值=%d，shrinker 节流=%u 秒\n",
		adj_threshold, shrinker_throttle_sec);
	return 0;
}

static void __exit kgsl_state_bridge_exit(void)
{
	unregister_shrinker(&kgsl_state_shrinker);
	tracepoint_probe_unregister(oom_adj_tracepoint,
				    (void *)oom_score_adj_update_probe, NULL);
	tracepoint_synchronize_unregister();
	cancel_work_sync(&kgsl_state_work);
	/* 卸载时把所有进程还原成 foreground，不给系统留下半回收状态 */
	kgsl_state_sweep(false);
	pr_info("已卸载; events=%lld writes=%lld missing=%lld failed=%lld full=%lld shrinker_calls=%lld sweeps=%lld\n",
		atomic64_read(&events_seen), atomic64_read(&writes_ok),
		atomic64_read(&nodes_missing), atomic64_read(&writes_failed),
		atomic64_read(&slots_full), atomic64_read(&shrinker_calls),
		atomic64_read(&shrinker_sweeps));
}

module_init(kgsl_state_bridge_init);
module_exit(kgsl_state_bridge_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Shrinker-driven Android oom_adj to Qualcomm KGSL reclaim-state bridge (v4.1.1)");
