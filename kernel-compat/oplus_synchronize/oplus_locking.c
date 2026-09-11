#include <linux/module.h>
#include <linux/init.h>
#include <linux/version.h>
#include <linux/sched/clock.h>
#include <linux/cgroup.h>
#include <linux/ftrace.h>
#include <linux/futex.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/rwsem.h>
#include <linux/rtmutex.h>
#include <linux/mutex.h>
#include <trace/hooks/dtask.h>
#include <trace/hooks/futex.h>
#include <trace/hooks/sched.h>
#include <trace/events/sched.h>
#include <trace/events/task.h>
#include <linux/sched.h>


//#include <../kernel/oplus_cpu/sched/sched_assist/sa_common.h>

#include "locking_main.h"
static inline struct oplus_task_struct *get_oplus_task_struct(struct task_struct *t);
#if (LINUX_VERSION_CODE >= KERNEL_VERSION(6, 1, 0))
#define PDE_DATA pde_data
#endif

static int debug_enable_flag;
static bool init_done = false;
atomic64_t switch_in_cs_cnts;

#define SAVE_TRACE_NUMS	4
struct lock_record {
	int type;
	u64 lock_id;
	u64 start_time;

	unsigned long traces[SAVE_TRACE_NUMS];

	struct list_head node;
};

static const char *skip_str[] = {"f2fs_quota_sync", "f2fs_dquot_commit", "aio_write", "aio_complete_rw", "mcp_wait",
                                 "session_gp_invoke_command", "ksys_read", "devkmsg_read", "n_tty_read", "__driver_attach",
                                 "btmtk_uart_cif_mutex_unlock", "wfsys_unlock", "connv3_core_pre_cal_start", "regulator_unlock_recursive"};

#define MUTEX		0
#define RWSEM		1
#define RTMUTEX		2
#define PCP_RWSEM	3
#define LOCK_TYPES	4

char *lock_str[LOCK_TYPES] = {"mutex", "rwsem", "rtmutex", "pcp-rwsem"};

static atomic64_t wait_cnt[LOCK_TYPES];
static atomic64_t lock_cnts[LOCK_TYPES];
static atomic64_t max_wait_cnts[LOCK_TYPES];
static atomic_t max_depth;

#define CS_STATS_LVLS	6
static atomic64_t cs_duration[LOCK_TYPES][CS_STATS_LVLS];

static unsigned long scopes[CS_STATS_LVLS][2] = {
	{0, 2 * NSEC_PER_MSEC},
	{2 * NSEC_PER_MSEC, 10 * NSEC_PER_MSEC},
	{10 * NSEC_PER_MSEC, 50 * NSEC_PER_MSEC},
	{50 * NSEC_PER_MSEC, 200 * NSEC_PER_MSEC},
	{200 * NSEC_PER_MSEC, 1 * NSEC_PER_SEC},
	{1 * NSEC_PER_SEC, ULONG_MAX}
};

#define MAX_TRACE_DEPTH	10

static bool is_match_skip_str(unsigned long *entries, int nentries)
{
	char trace_str[KSYM_SYMBOL_LEN];
	char buf[1024];
	int idx = 0;
	int i;

	for (i = 0; i < nentries; i++) {
		sprint_symbol_no_offset(trace_str, entries[i]);
		idx += sprintf(&buf[idx], "%s-", trace_str);
	}

	for (i = 0; i < sizeof(skip_str) / sizeof(skip_str[0]); i++) {
		if (strstr(buf, skip_str[i])) {
			//pr_err("krn_reliab : match string = %s\n", strstr(buf, skip_str[i]));
			return true;
		}
	}

	return false;
}

#define WALK_BACKTRACE_LEVELS	4
static bool is_skip_report_error(void)
{
	unsigned long entries[WALK_BACKTRACE_LEVELS];

	stack_trace_save(entries, WALK_BACKTRACE_LEVELS, 3);

	return is_match_skip_str(entries, WALK_BACKTRACE_LEVELS);
}

static void output_backtrace(unsigned long *entries, int nentrie)
{
	char trace_str[KSYM_SYMBOL_LEN];
	char *buf;
	int idx = 0;
	int i;

	buf = kmalloc(KSYM_SYMBOL_LEN * MAX_TRACE_DEPTH, GFP_ATOMIC);
	if (!buf)
		return;

	for (i = 0; i < nentrie; i++) {
		sprint_symbol(trace_str, entries[i]);
		idx += sprintf(&buf[idx], "%s - ", trace_str);
	}
	idx += sprintf(&buf[idx], "\n");

	pr_err("krn_reliab : trace = %s\n", buf);

	kfree(buf);
}

static void print_backtrace(int skip, int depth)
{
	unsigned long entries[MAX_TRACE_DEPTH];

	if (depth >= MAX_TRACE_DEPTH)
		return;

	stack_trace_save(entries, depth, skip);
	output_backtrace(entries, depth);
}

#define CLEAR_PERIOD_MSEC	(5000)
#define HOLD_EXPIRE_TIME	(10 * NSEC_PER_SEC)
/* Timer to clear hold lock info which exceed timeout. */
static atomic64_t clear_seq;
static struct timer_list clh_timer;

static void clear_hold_timer(struct timer_list *unused)
{
	atomic64_inc_return(&clear_seq);
	mod_timer(&clh_timer, jiffies + CLEAR_PERIOD_MSEC);
}

static void init_clear_lock_hold_timer(void)
{
	timer_setup(&clh_timer, clear_hold_timer, 0);
	clh_timer.expires = jiffies + msecs_to_jiffies(CLEAR_PERIOD_MSEC);
	add_timer(&clh_timer);
}

static void exit_clear_lock_hold_timer(void)
{
	del_timer_sync(&clh_timer);
}

static void stats_cs_duration(int type, u64 duration)
{
	int i;

	if (duration > 10 * NSEC_PER_SEC) {
		pr_err("krn_reliab : lock %s own the lock exceed threshold, duration = %llu s\n", lock_str[type], duration / NSEC_PER_SEC);
		print_backtrace(2, 7);
	}

	if (duration > NSEC_PER_SEC) {
		if (is_skip_report_error()) {
			//pr_err("match the skip str, do not report the error\n");
		}
		else {
			pr_err("krn_reliab : lock %s own the lock exceed 1s, duration = %llu s\n", lock_str[type], duration / NSEC_PER_SEC);
			print_backtrace(2, 7);
			atomic64_inc(&cs_duration[type][CS_STATS_LVLS-1]);
		}
	}

	for (i = 0; i < CS_STATS_LVLS-1; i++) {
		if (duration < scopes[i][1] && duration >= scopes[i][0]) {
			atomic64_inc(&cs_duration[type][i]);
			return;
		}
	}
}

void locking_record_switch_in_cs(struct task_struct *tsk)
{
	struct oplus_task_struct *ots;

	ots = get_oplus_task_struct(tsk);
	if (NULL == ots)
		return;
	if (atomic_read(&ots->lkinfo.lock_depth)) {
		atomic64_inc(&switch_in_cs_cnts);
	}
}
EXPORT_SYMBOL(locking_record_switch_in_cs);

static void get_lock_cnts(bool is_lock, int type)
{
	if (is_lock)
		atomic64_inc(&lock_cnts[type]);
}

void depth_check(struct oplus_task_struct *ots, int flag)
{
	int length = 0;
	struct list_head *list_node;

	list_for_each(list_node, &ots->lkinfo.lock_head) {
		length++;
	}
	
	if(length != atomic_read(&ots->lkinfo.lock_depth)) {
		//pr_err("depth:%d, length:%d, flag:%d", atomic_read(&ots->lkinfo.lock_depth), length, flag);
		atomic_set(&ots->lkinfo.lock_depth, length);
	}
}

static void lock_handler(struct task_struct *tsk, u64 lock_id, int type)
{
	struct oplus_task_struct *ots;
	struct lock_record *node, *tmp;

	u64 g_clear_seq;
	
	ots = get_oplus_task_struct(tsk);
	if (IS_ERR_OR_NULL(ots)) {
		//pr_err("krn_reliab : ots == NULL \n");
		return;
	}
	if (NULL == ots->lkinfo.lock_head.next) {
		INIT_LIST_HEAD(&ots->lkinfo.lock_head);
	}

	g_clear_seq = atomic64_read_acquire(&clear_seq);
	if (ots->lkinfo.clear_seq < g_clear_seq) {
		ots->lkinfo.clear_seq = g_clear_seq;
		list_for_each_entry_safe_reverse(node, tmp, &ots->lkinfo.lock_head, node) {
			if (sched_clock() - node->start_time >= HOLD_EXPIRE_TIME) {
				if (is_match_skip_str(node->traces, SAVE_TRACE_NUMS)) {
					pr_err("krn_reliab : free skip expire held lock info, type = %s\n", lock_str[type]);
				} else {
					pr_err("krn_reliab : free unskip expire held lock info, type = %s, lock_id = 0x%llx, lock_stamp = %llu\n", lock_str[node->type], node->lock_id, node->start_time);
					output_backtrace(node->traces, SAVE_TRACE_NUMS);
					print_backtrace(2, 7);
				}
				if (type == PCP_RWSEM) {
					if (atomic_read(&ots->lkinfo.lock_depth) < 0) {
						//pr_err("krn_reliab : why lock depth less than 0?\n");
					}
				}
				atomic_dec(&ots->lkinfo.lock_depth);
				list_del(&node->node);
				depth_check(ots, 1);
				kfree(node);
			} else {
				break;
			}
		}
	}

	node = kmalloc(sizeof(*node), GFP_ATOMIC);
	if (!node) {
		pr_err("krn_reliab : Failed to alloc record node\n");
		return;
	}
	node->lock_id = lock_id;
	node->type = type;
	node->start_time = sched_clock();
	stack_trace_save(node->traces, SAVE_TRACE_NUMS, 2);
	list_add(&node->node, &ots->lkinfo.lock_head);

	atomic_inc(&ots->lkinfo.lock_depth);
	depth_check(ots, 2);
	if (atomic_read(&max_depth) < atomic_read(&ots->lkinfo.lock_depth)) {
		atomic_set(&max_depth, atomic_read(&ots->lkinfo.lock_depth));
		pr_err("krn_reliab : update the max depth: %d, type = %s, lock_id = 0x%llx\n", atomic_read(&ots->lkinfo.lock_depth), lock_str[node->type], node->lock_id);
		print_backtrace(2, 7);
	}

}

static void unlock_handler(struct task_struct *tsk, u64 lock_id, int type)
{
	struct oplus_task_struct *ots;
	struct lock_record *node, *tmp;
	u64 duration;
	int found = 0;

	ots = get_oplus_task_struct(tsk);

	if (IS_ERR_OR_NULL(ots)) {
		pr_err("krn_reliab : ots == NULL \n");
		return;
	}
	if (NULL == ots->lkinfo.lock_head.next)
		INIT_LIST_HEAD(&ots->lkinfo.lock_head);

	list_for_each_entry_safe(node, tmp, &ots->lkinfo.lock_head, node) {
		if (lock_id == node->lock_id) {
			duration = sched_clock() - node->start_time;
			stats_cs_duration(type, duration);

			/* Find corresponding lock !*/
			found++;
			/*
			if (found >= 2) {
				pr_err("krn_reliab : why found 2 unlocks, type = %s\n", lock_str[type]);
				print_backtrace(3, 4);
			} */
			atomic_dec(&ots->lkinfo.lock_depth);
			depth_check(ots, 3);
			if (atomic_read(&ots->lkinfo.lock_depth) < 0) {
				pr_err("krn_reliab : why lock depth less than 0?\n");
			}

			list_del(&node->node);
			kfree(node);

			/* f2fs_dquot_commit/f2fs_quota_sync will lock recursively rwsem(reader) with trylock. */
			break;
		}
	}
	if (0 == found) {
		if (is_skip_report_error()) {
			//pr_err("krn_reliab : skip report unlocked error, type =  %s\n", lock_str[type]);
		} else {
			pr_err("krn_reliab : can't find corresponding lock, type = %s, lock_id = 0x%llx, lock_stamp = %llu\n", lock_str[type], lock_id, sched_clock());
			print_backtrace(2, 7);
		}
	}
}

void mutex_lock_handler(u64 lock, struct task_struct *tsk, unsigned long jiffies)
{
	if(!debug_enable_flag) return;

	get_lock_cnts(jiffies, MUTEX);

	if (jiffies) {
		lock_handler(tsk, lock, MUTEX);
	}
	else {
		unlock_handler(tsk, lock, MUTEX);
	}

}

void rwsem_lock_handler(u64 sem, struct task_struct *tsk, unsigned long jiffies)
{
	if(!debug_enable_flag) return;
	get_lock_cnts(jiffies, RWSEM);

	if (jiffies) {	
		lock_handler(tsk, (u64)sem, RWSEM);
	}
	else {
		unlock_handler(tsk, (u64)sem, RWSEM);
	}
}

void rtmutex_lock_handler(u64 lock, struct task_struct *tsk, unsigned long jiffies)
{
	if(!debug_enable_flag) return;
	get_lock_cnts(jiffies, RTMUTEX);

	if (jiffies)
		lock_handler(tsk, (u64)lock, RTMUTEX);
	else
		unlock_handler(tsk, (u64)lock, RTMUTEX);
}

void android_vh_pcpu_rwsem_handler(u64 sem, struct task_struct *tsk, unsigned long jiffies)
{
	if(!debug_enable_flag) return;
	get_lock_cnts(jiffies, PCP_RWSEM);

/*
	if (jiffies)
		lock_handler(tsk, (u64)sem, PCP_RWSEM);
	else
		unlock_handler(tsk, (u64)sem, PCP_RWSEM);
*/
}
EXPORT_SYMBOL(android_vh_pcpu_rwsem_handler);

static int iter_rbtree_for_elem_nums(struct rb_root *root)
{
	int cnts = 0;
	struct rb_node *node;

	for (node = rb_first(root); node; node = rb_next(node))
		cnts++;

	return cnts;
}

unsigned long flags;
static noinline int walk_list_for_elem_nums(struct list_head *head)
{
	atomic_t cnts;
	struct list_head *node;

	atomic_set(&cnts, 0);

	list_for_each(node, head) {
		atomic_inc(&cnts);
	}
	return atomic_read(&cnts);
}

/* We don't want to add a cnt field in lock struct.
 * Just walk the list/rbtree to get wait list cnt;
 */
static void record_waiters_cnts(void *lock, int type)
{
	struct mutex *mlock;
	struct rw_semaphore *rwsem;
	struct rt_mutex_base *rtlock;
	struct percpu_rw_semaphore *pcp_sem;

	int max;
	atomic_t cnt;
	
	atomic_set(&cnt, 0);
	if (NULL == lock) {
		printk("krn_reliab : lock is NULL\n");
		return;
	}

	/* Only read, no need to acquire spinlock.*/
	max = atomic64_read(&max_wait_cnts[type]);
	switch (type) {
	case MUTEX:
		mlock = (struct mutex*)lock;
		atomic_set(&cnt, walk_list_for_elem_nums(&mlock->wait_list));
		break;
	case RWSEM:
		rwsem = (struct rw_semaphore *)lock;
		atomic_set(&cnt, walk_list_for_elem_nums(&rwsem->wait_list));
		break;
	case RTMUTEX:
		rtlock = (struct rt_mutex_base *)lock;
		atomic_set(&cnt, iter_rbtree_for_elem_nums(&rtlock->waiters.rb_root));
		break;
	case PCP_RWSEM:
		pcp_sem = (struct percpu_rw_semaphore *)lock;
		spin_lock_irqsave(&pcp_sem->waiters.lock, flags);
		atomic_set(&cnt, walk_list_for_elem_nums(&pcp_sem->waiters.head));
		spin_unlock_irqrestore(&pcp_sem->waiters.lock, flags);
		break;
	default:
		atomic_set(&cnt, 0);
		break;
	}

	if (atomic_read(&cnt) > max) {
		atomic64_set(&max_wait_cnts[type], atomic_read(&cnt));
		pr_err("krn_reliab : update the max waiter_cnts: %d, type = %s, lock_id = 0x%lx\n", atomic_read(&cnt), lock_str[type], (unsigned long)lock);
		print_backtrace(2, 7);
	}
}

void mutex_wait_handler(struct mutex *lock)
{
	if(!debug_enable_flag) return;
	/* Contend stats. */
	atomic64_inc(&wait_cnt[MUTEX]);

	/* Wait list nums stats. */
	record_waiters_cnts(lock, MUTEX);
}

void rwsem_read_wait_handler(struct rw_semaphore *sem)
{
	if(!debug_enable_flag) return;
	/* Contend stats. */
	atomic64_inc(&wait_cnt[RWSEM]);

	/* Wait list nums stats. */
	raw_spin_lock(&sem->wait_lock);
	record_waiters_cnts(sem, RWSEM);
	// walk_list_for_elem_nums(&sem->wait_list);
	raw_spin_unlock(&sem->wait_lock);
}

void rwsem_write_wait_handler(struct rw_semaphore *sem)
{
	/* Contend stats. */
	atomic64_inc(&wait_cnt[RWSEM]);

	/* Wait list nums stats. */
	record_waiters_cnts(sem, RWSEM);
}

void rtmutex_wait_handler(struct rt_mutex_base *lock)
{
	if(!debug_enable_flag) return;
	/* Contend stats. */
	atomic64_inc(&wait_cnt[RTMUTEX]);

	/* Wait list nums stats. */
	record_waiters_cnts(lock, RTMUTEX);
}

void pcp_wait_handler(struct percpu_rw_semaphore *sem, bool is_reader, int phase)
{
	if(!debug_enable_flag) return;
	/* Contend stats. */
	atomic64_inc(&wait_cnt[PCP_RWSEM]);

	/* Wait list nums stats. */
	record_waiters_cnts(sem, PCP_RWSEM);
}

/*
static void schedule_handler(void *unused, struct task_struct *prev, struct task_struct *next, struct rq *rq)
{

}*/


/***************************** switch in cs *******************************/
static int switch_in_cs_show(struct seq_file *m, void *v)
{
	char buf[1024];
	int idx = 0;
	long long int total_lock_cnts = 0;
	int i;

	idx += sprintf(&buf[idx], "%-16s%-16s\n", "total", "switch_incs");

	for (i = 0; i < LOCK_TYPES; i++) {
		total_lock_cnts += atomic64_read(&lock_cnts[i]);
	}

	idx += sprintf(&buf[idx], "%-16lld%-16lld\n", total_lock_cnts, atomic64_read(&switch_in_cs_cnts));

	sprintf(&buf[idx], "\n");

	seq_printf(m, "%s\n", buf);

	return 0;
}

static int switch_in_cs_open(struct inode *inode, struct file *file)
{
	return single_open(file, switch_in_cs_show, PDE_DATA(inode));
}

static const struct proc_ops switch_in_cs_fops = {
	.proc_open		= switch_in_cs_open,
	.proc_read		= seq_read,
	.proc_lseek		= seq_lseek,
	.proc_release		= single_release,
};

/*****************************Contend Info *******************************/

/***************************** max depth *******************************/
static int max_depth_show(struct seq_file *m, void *v)
{
	char buf[1024];
	int idx = 0;
	// int i;

	idx += sprintf(&buf[idx], "%-16s%-16d\n", "max_depth:", atomic_read(&max_depth));

	/*
	for (i = 0; i < LOCK_TYPES; i++) {
		idx += sprintf(&buf[idx], "%-16s%-16d\n", lock_str[i], atomic_read(&max_depth[i]));
	}
	*/

	seq_printf(m, "%s\n", buf);

	return 0;
}

static int max_depth_open(struct inode *inode, struct file *file)
{
	return single_open(file, max_depth_show, PDE_DATA(inode));
}

static const struct proc_ops max_depth_fops = {
	.proc_open		= max_depth_open,
	.proc_read		= seq_read,
	.proc_lseek		= seq_lseek,
	.proc_release		= single_release,
};

/***************************** max depth *******************************/


/***************************** CS duration *******************************/
#define STAMP_TO_UNIT_NUM(x)	(x / 1000000000 >= 1 ? (x / 1000000000) : (x / 1000000 >= 1 ? (x / 1000000) : (x / 1000)))
#define STAMP_TO_NUM_UNIT(x)	(x / 1000000000 >= 1 ? "s" : (x / 1000000 >= 1 ? "ms" : "us"))

static int cs_duration_show(struct seq_file *m, void *v)
{
	char *buf;
	char tmp[50];
	int i, j, idx = 0;

	buf = kmalloc(8192, GFP_ATOMIC);
	if (!buf)
		return -ENOMEM;

	idx += sprintf(&buf[idx], "%-16s", "");

	for (i = 0; i < CS_STATS_LVLS; i++) {
		if(i < CS_STATS_LVLS -1) {
			sprintf(tmp, "%lu%s~%lu%s",
					STAMP_TO_UNIT_NUM(scopes[i][0]),
					STAMP_TO_NUM_UNIT(scopes[i][0]),
					STAMP_TO_UNIT_NUM(scopes[i][1]),
					STAMP_TO_NUM_UNIT(scopes[i][1]));
		} else {
			sprintf(tmp, "%lu%s~%s",
					STAMP_TO_UNIT_NUM(scopes[i][0]),
					STAMP_TO_NUM_UNIT(scopes[i][0]),
					"UL_MAX");
		}
		idx += sprintf(&buf[idx], "%-12s", tmp);
	}
	idx += sprintf(&buf[idx], "\n");

	for (i = 0; i < LOCK_TYPES; i++) {
		idx += sprintf(&buf[idx], "%-16s", lock_str[i]);
		for (j = 0; j < CS_STATS_LVLS; j++) {
			idx += sprintf(&buf[idx], "%-12lld", atomic64_read(&cs_duration[i][j]));
		}
		idx += sprintf(&buf[idx], "\n");
	}
	seq_printf(m, "%s\n", buf);

	kfree(buf);
	return 0;
}

static int cs_duration_open(struct inode *inode, struct file *file)
{
	return single_open(file, cs_duration_show, PDE_DATA(inode));
}

static const struct proc_ops cs_duration_fops = {
	.proc_open		= cs_duration_open,
	.proc_read		= seq_read,
	.proc_lseek		= seq_lseek,
	.proc_release		= single_release,
};

/***************************** CS duration *******************************/

/***************************** Waiter cnts *******************************/
static int waiter_cnts_show(struct seq_file *m, void *v)
{
	char waiter_cnts[1024];
	int idx = 0;
	int i;

	idx += sprintf(&waiter_cnts[idx], "%-16s%-16s\n", "", "max_cnts");

	for (i = 0; i < LOCK_TYPES; i++) {
		idx += sprintf(&waiter_cnts[idx], "%-16s%-16lld\n", lock_str[i], atomic64_read(&max_wait_cnts[i]));
	}

	sprintf(&waiter_cnts[idx], "\n");

	seq_printf(m, "%s\n", waiter_cnts);

	return 0;
}

static int waiter_cnts_open(struct inode *inode, struct file *file)
{
	return single_open(file, waiter_cnts_show, PDE_DATA(inode));
}

static const struct proc_ops waiter_cnts_fops = {
	.proc_open		= waiter_cnts_open,
	.proc_read		= seq_read,
	.proc_lseek		= seq_lseek,
	.proc_release		= single_release,
};

/***************************** Waiter cnts *******************************/

/*****************************Contend Info *******************************/
static int contend_infos_show(struct seq_file *m, void *v)
{
	char contend_info[1024];
	int idx = 0;
	int i;

	idx += sprintf(&contend_info[idx], "%-16s%-16s%-16s\n", "", "total", "contend");

	for (i = 0; i < LOCK_TYPES; i++) {
		idx += sprintf(&contend_info[idx], "%-16s%-16lld%-16lld\n", lock_str[i], atomic64_read(&lock_cnts[i]), atomic64_read(&wait_cnt[i]));	
	}

	sprintf(&contend_info[idx], "\n");

	seq_printf(m, "%s\n", contend_info);

	return 0;
}

static int contend_infos_open(struct inode *inode, struct file *file)
{
	return single_open(file, contend_infos_show, PDE_DATA(inode));
}

static const struct proc_ops contend_info_fops = {
	.proc_open		= contend_infos_open,
	.proc_read		= seq_read,
	.proc_lseek		= seq_lseek,
	.proc_release		= single_release,
};

/*****************************Contend Info *******************************/


#define CONTEND_INFO_FILE	"contend_info"
#define WAITER_CNTS		"waiter_cnts"
#define CS_DURATION		"cs_duration"
#define MAX_DEPTH		"max_depth"
#define KRN_RELIAB_DIR		"boot_reliab_dir"
#define SWITCH_IN_CS		"switch_in_cs"

static struct proc_dir_entry *d_krn_reliab;

static int create_proc_files(void)
{
	struct proc_dir_entry *p;

	p = proc_create(CONTEND_INFO_FILE, S_IRUGO | S_IWUGO,
			d_krn_reliab, &contend_info_fops);
	if (!p)
		goto err;

	p = proc_create(WAITER_CNTS, S_IRUGO | S_IWUGO,
			d_krn_reliab, &waiter_cnts_fops);
	if (!p)
		goto err1;

	p = proc_create(CS_DURATION, S_IRUGO | S_IWUGO,
			d_krn_reliab, &cs_duration_fops);
	if (!p)
		goto err2;

	p = proc_create(MAX_DEPTH, S_IRUGO | S_IWUGO,
			d_krn_reliab, &max_depth_fops);
	if (!p)
		goto err3;

	p = proc_create(SWITCH_IN_CS, S_IRUGO | S_IWUGO,
			d_krn_reliab, &switch_in_cs_fops);
	if (!p)
		goto err4;

	return 0;
err4:
	remove_proc_entry(MAX_DEPTH, d_krn_reliab);
err3:
	remove_proc_entry(CS_DURATION, d_krn_reliab);
err2:
	remove_proc_entry(WAITER_CNTS, d_krn_reliab);
err1:
	remove_proc_entry(CONTEND_INFO_FILE, d_krn_reliab);
err:
	remove_proc_entry(KRN_RELIAB_DIR, NULL);
	return -ENOMEM;
}


static void remove_proc_files(void)
{
	remove_proc_entry(SWITCH_IN_CS, d_krn_reliab);
	remove_proc_entry(MAX_DEPTH, d_krn_reliab);
	remove_proc_entry(CONTEND_INFO_FILE, d_krn_reliab);
	remove_proc_entry(WAITER_CNTS, d_krn_reliab);
	remove_proc_entry(CS_DURATION, d_krn_reliab);
}

static int register_driver(void)
{
	create_proc_files();
	return 0;
}

static void unregister_driver(void)
{
	remove_proc_files();
}

static ssize_t kernel_reliab_enabled_write(struct file *file, const char __user *buf,
		size_t count, loff_t *ppos)
{
	char buffer[8];
	int err;

	memset(buffer, 0, sizeof(buffer));

	if (count > sizeof(buffer) - 1)
		count = sizeof(buffer) - 1;

	if (copy_from_user(buffer, buf, count))
		return -EFAULT;

	buffer[count] = '\0';
	err = kstrtoint(strstrip(buffer), 10, &debug_enable_flag);
	if (err)
		return err;

	if(debug_enable_flag) {
		if(init_done)
			return count;
		init_done = true;
		register_driver();
		init_clear_lock_hold_timer();
	} else {
		init_done = false;
		unregister_driver();
		exit_clear_lock_hold_timer();
	}

	return count;
}

static ssize_t kernel_reliab_enabled_read(struct file *file, char __user *buf,
		size_t count, loff_t *ppos)
{
	char buffer[20];
	size_t len = 0;

	len = snprintf(buffer, sizeof(buffer), "debug_enable_flag=%d\n", debug_enable_flag);

	return simple_read_from_buffer(buf, count, ppos, buffer, len);
}

static const struct proc_ops kernel_reliab_enabled_fops = {
	.proc_write		= kernel_reliab_enabled_write,
	.proc_read		= kernel_reliab_enabled_read,
};

static int __init krn_reliab_init(void)
{
	struct proc_dir_entry *proc_node;

	d_krn_reliab = proc_mkdir(KRN_RELIAB_DIR, NULL);
	if (!d_krn_reliab)
		return -ENOMEM;

	proc_node = proc_create("debug_enable", 0666, d_krn_reliab, &kernel_reliab_enabled_fops);
	if (!proc_node) {
		pr_err("failed to create proc node debug_enable_flag\n");
		goto err_creat_debug_enable_flag;
	}

	return 0;

err_creat_debug_enable_flag:
	remove_proc_entry("debug_enable_flag", d_krn_reliab);
	remove_proc_entry(KRN_RELIAB_DIR, NULL);
	return 0;
}

static void __exit krn_reliab_exit(void)
{
	exit_clear_lock_hold_timer();
}

module_init(krn_reliab_init);
module_exit(krn_reliab_exit);
