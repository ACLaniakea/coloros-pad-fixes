#include <linux/module.h>
#include <linux/types.h>
#include <trace/hooks/vmscan.h>
#include <linux/swap.h>
#include <linux/proc_fs.h>
#include <trace/hooks/signal.h>
#include <linux/mm.h>
#include <linux/sched/task.h>
#include <linux/delay.h>
#include <linux/sched/clock.h>
#include <trace/hooks/dtask.h>
#include <linux/sched/wake_q.h>
#include <../kernel/oplus_cpu/sched/sched_assist/sa_common.h>
#include <trace/events/sched.h>
#include <trace/hooks/sched.h>
#include "locking_main.h"

#define SET_TAG		1
#define DRV_WAKED	2

extern unsigned long nr_running(void);

static int timer_interval = 2;
module_param(timer_interval, int, S_IRUGO | S_IWUSR);

static int max_threads_thres = 60;
module_param(max_threads_thres, int, S_IRUGO | S_IWUSR);

static int max_wake_thread_thres = 40;
module_param(max_wake_thread_thres, int, S_IRUGO | S_IWUSR);

static int release_unexit_cnts = 150;
module_param(release_unexit_cnts, int, S_IRUGO | S_IWUSR);

unsigned long drv_nr_running(void)
{
	return nr_running();
}

static struct list_head kill_plist;
static DEFINE_SPINLOCK(list_lock);
static struct hrtimer scan_timer;

/*
 * delayed_cnts : total counts that record call threads which have been delayed.
 * auto_release_cnts : some threads may need not to be wakeup,
 * 				they have already in rq or running on cpu.
 * drv_release_exit_cnts : thread which waked by timer, already exit cnts.
 * drv_release_cnts : Thread cnts that wakeup by this driver.
 *
 */
static atomic_t delayed_cnts, drv_release_cnts,
			drv_release_exit_cnts, auto_release_cnts;
static atomic_t dup_handle_cnts;

static void android_vh_exit_check_handler(void *unused, struct task_struct *p)
{
	struct oplus_task_struct *ots;

	/* Current thread, noneed to use rcu_lock here.*/
	ots = get_oplus_task_struct(current);
	if (!ots)
		return;
	spin_lock_irq(&list_lock);
	if (ots->lkinfo.owner) {
		atomic_inc(&auto_release_cnts);
		list_del(&ots->lkinfo.node);
		ots->lkinfo.owner = NULL;
	}
	spin_unlock_irq(&list_lock);

	if (ots->lkinfo.kill_flag == DRV_WAKED)
		atomic_inc_return(&drv_release_exit_cnts);
}


static void android_vh_exit_signal_whether_wake_handler(void *unused,
					struct task_struct *p, bool *wake)
{
	struct oplus_task_struct *ots;
	unsigned long nr;

	nr = drv_nr_running();

	rcu_read_lock();
	ots = get_oplus_task_struct(p);
	if (!ots) {
		rcu_read_unlock();
		return;
	}

	if (!ots->lkinfo.kill_flag && nr >= max_threads_thres) {
		ots->lkinfo.kill_flag = SET_TAG;

		/* Already in sighand spinlock critical section. */

		if (!(p->flags & PF_EXITING)) {
			spin_lock_irq(&list_lock);
			list_add_tail(&ots->lkinfo.node, &kill_plist);
			atomic_inc(&delayed_cnts);
			ots->lkinfo.owner = p;
			spin_unlock_irq(&list_lock);
			set_tsk_thread_flag(p, TIF_SIGPENDING);
			cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
				"smooth_kill : task %s pid %d set TIF_SIGPENDING\n",
				p->comm, p->pid);
			*wake = false;
		}
	} else if (ots->lkinfo.kill_flag) {
		cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
			"smooth_kill : task %s already handled!!\n", p->comm);
		atomic_inc(&dup_handle_cnts);
	}
	rcu_read_unlock();
}

/*
 * Incase Some projects do not enable oom_reaper by default,
 * We have to enable it.
 */

#define REAPER_SZ (SZ_1M * 32 / PAGE_SIZE)
static void android_vh_killed_process_handler(void *unused,
				struct task_struct *killer,
				struct task_struct *killee, bool *reap)
{
	unsigned long pages = 0;

	if (!strcmp(killer->comm, "lmkd") ||
		!strcmp(killer->comm, "athena_killer") ||
		!strcmp(killer->comm, "PreKillActionT")) {

		cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
			"killer1: %s\n", killer->comm);
		task_lock(killee);
		if (killee->mm)
			pages = get_mm_counter(killee->mm, MM_ANONPAGES) +
					get_mm_counter(killee->mm, MM_SWAPENTS);
		task_unlock(killee);

		if (pages > REAPER_SZ) {
			*reap = true;
		}
	} else {
		cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
			"killer2: %s\n", killer->comm);
	}
}

static void android_vh_freeze_whether_wake_handler(void *unused,
					struct task_struct *p, bool *wake)
{
	struct oplus_task_struct *ots;

	rcu_read_lock();
	ots = get_oplus_task_struct(p);
	if (!ots) {
		rcu_read_unlock();
		cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
			"massive_kill : ots == NULL !!! \n");
		return;
	}
	if (ots->lkinfo.kill_flag)
		*wake = false;

	rcu_read_unlock();
}

static enum hrtimer_restart scan_nr_running(struct hrtimer *timer)
{
	unsigned long nr;
	int woken = 0;
	struct locking_info *lk, *tmp;
	int drv_rels, rel_unexits;

	drv_rels = atomic_read(&drv_release_cnts);
	rel_unexits = atomic_read_acquire(&drv_release_exit_cnts);

	nr = drv_nr_running();
	if ((nr <= max_threads_thres) &&
		(drv_rels - rel_unexits <= release_unexit_cnts)) {
		if (!list_empty(&kill_plist)) {
			spin_lock(&list_lock);
			list_for_each_entry_safe(lk, tmp, &kill_plist, node) {
				if (drv_nr_running() >
					(max_threads_thres + max_wake_thread_thres))
					break;

				list_del(&lk->node);
				lk->kill_flag = DRV_WAKED;
				atomic_inc(&drv_release_cnts);
				if (!wake_up_state(lk->owner,
						TASK_WAKEKILL | TASK_INTERRUPTIBLE))
					kick_process(lk->owner);
				lk->owner = NULL;

				woken++;
				if (woken >= max_wake_thread_thres)
					break;
			}
			spin_unlock(&list_lock);

			cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
					"smooth_kill : wake %d threads,"
					"nr_running = %lu, stamp = %llu ms\n",
					woken, nr, sched_clock() / 1000000);

		} else {
			/* Prevent cumulative errors */
			/* atomic_set(&drv_release_exit_cnts, 0);*/
			/* stop hrtimer ?*/
		}
	} else {
		cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
			"smooth_kill : too many waked threads"
			"blocked before do exit check point,"
			"noneed to do wake operation\n");
		/* do nothing */
	}

	hrtimer_forward_now(timer, ms_to_ktime(timer_interval));
	return HRTIMER_RESTART;
}


static int show_kill_show(struct seq_file *m, void *v)
{
	char *buf;
	int idx = 0;

	buf = kmalloc(4096, GFP_KERNEL);
	if (!buf)
		return -ENOMEM;
	idx += sprintf(&buf[idx], "%-16s%-16s%-16s%-16s%-16s\n", "delayed",
						"drv_release", "drv_rel_exit",
						"auto_release", "dup_handled");
	idx += sprintf(&buf[idx], "%-16u%-16u%-16u%-16u%-16u\n",
				atomic_read(&delayed_cnts),
				atomic_read(&drv_release_cnts),
				atomic_read(&drv_release_exit_cnts),
				atomic_read(&auto_release_cnts),
				atomic_read(&dup_handle_cnts));
	/* don't clear */

	seq_printf(m, "%s\n", buf);
	kfree(buf);

	return 0;
}

static int show_kill_open(struct inode *inode, struct file *file)
{
	return single_open(file, show_kill_show, inode);
}


static const struct proc_ops show_kill_fops = {
	.proc_open		= show_kill_open,
	.proc_read		= seq_read,
	.proc_lseek		= seq_lseek,
	.proc_release		= single_release,
};


int smooth_kill_init(void)
{
	INIT_LIST_HEAD(&kill_plist);
	hrtimer_init(&scan_timer, CLOCK_MONOTONIC, HRTIMER_MODE_REL);
	scan_timer.function = scan_nr_running;
	hrtimer_start(&scan_timer, ms_to_ktime(5), HRTIMER_MODE_REL);

	proc_create("show_kill_cnts_rclear", S_IRUGO | S_IWUGO,
						NULL, &show_kill_fops);

	register_trace_android_vh_exit_check(
				android_vh_exit_check_handler, NULL);
	register_trace_android_vh_exit_signal_whether_wake(
				android_vh_exit_signal_whether_wake_handler, NULL);

	register_trace_android_vh_killed_process(
			android_vh_killed_process_handler, NULL);

	register_trace_android_vh_freeze_whether_wake(
				android_vh_freeze_whether_wake_handler, NULL);
	printk("smooth_kill : smooth_kill_init done! \n");

	return 0;
}

void smooth_kill_exit(void)
{
	hrtimer_cancel(&scan_timer);
	remove_proc_entry("show_kill_cnts_rclear", NULL);
	unregister_trace_android_vh_exit_check(
				android_vh_exit_check_handler, NULL);
	unregister_trace_android_vh_exit_signal_whether_wake(
				android_vh_exit_signal_whether_wake_handler, NULL);

	unregister_trace_android_vh_killed_process(
				android_vh_killed_process_handler, NULL);

	unregister_trace_android_vh_freeze_whether_wake(
				android_vh_freeze_whether_wake_handler, NULL);
}

