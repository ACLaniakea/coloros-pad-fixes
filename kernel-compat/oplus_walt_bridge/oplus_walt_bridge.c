// SPDX-License-Identifier: GPL-2.0
/*
 * Translate OPlus sched_assist UX state into Qualcomm WALT's native
 * per-task boost state without competing for a restricted scheduler hook.
 *
 * The target's sched-walt.ko already owns android_rvh_replace_next_task_fair.
 * Its walt_cfs_replace_next_task_fair() selects tasks from WALT's MVP list.
 * Trying to install the OPlus pick-next handler beside it therefore fails with
 * -EBUSY and, even if forced, would create two final decision makers.
 *
 * WALT exports set_task_boost(int type, u64 period_ms).  It operates on
 * current and is IRQ-safe (the implementation only updates current's WALT
 * fields and reads sched_clock).  android_vh_scheduler_tick is an ordinary,
 * multi-subscriber vendor hook and executes in the context of rq->curr.  On a
 * tick belonging to an OPlus UX CFS task, briefly renew WALT boost type 3.
 * WALT then consumes that state through its existing MVP pick/preempt path.
 *
 * Important limits:
 *   - this cannot accelerate a task's very first wakeup; it starts after the
 *     task has accumulated one scheduler tick;
 *   - no WALT-private task_struct offsets are read or written;
 *   - no explicit reset is issued.  The short boost expires naturally, so a
 *     stale OPlus UX mark cannot leave a permanent WALT boost;
 *   - set_task_boost has no public getter.  Calling it may replace a concurrent
 *     PowerHAL boost on the same UX task.  Keep the bridge staged and disabled
 *     by default until interaction and soak tests show the trade-off is sound.
 *
 * This hook is unregisterable.  rmmod is a complete live rollback after the
 * callback is disabled and in-flight tracepoint calls are synchronized.
 */

#define pr_fmt(fmt) "oplus_walt_bridge: " fmt

#include <linux/atomic.h>
#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/proc_fs.h>
#include <linux/sched.h>
#include <linux/seq_file.h>
#include <linux/tracepoint.h>
#include <trace/hooks/sched.h>

struct rq;

extern bool test_task_ux(struct task_struct *task);
extern int set_task_boost(int boost, u64 period_ms);

/* Manual activation is intentional: loading the experiment changes nothing. */
static bool enable;
module_param(enable, bool, 0644);
MODULE_PARM_DESC(enable, "Mirror running OPlus UX tasks into WALT task boost");

static unsigned int boost_type = 3;
module_param(boost_type, uint, 0644);
MODULE_PARM_DESC(boost_type, "WALT task boost type (1..3, default 3)");

static unsigned long boost_period_ms = 100;
module_param(boost_period_ms, ulong, 0644);
MODULE_PARM_DESC(boost_period_ms, "Natural WALT boost expiry in ms (1..1000)");

static atomic64_t tick_calls = ATOMIC64_INIT(0);
static atomic64_t ux_hits = ATOMIC64_INIT(0);
static atomic64_t boost_ok = ATOMIC64_INIT(0);
static atomic64_t boost_errors = ATOMIC64_INIT(0);
static bool hook_registered;
static struct proc_dir_entry *status_node;

static void bridge_tick(void *unused, struct rq *rq)
{
	unsigned int type;
	unsigned long period;
	int ret;

	atomic64_inc(&tick_calls);
	if (!READ_ONCE(enable))
		return;

	if (!test_task_ux(current))
		return;
	atomic64_inc(&ux_hits);

	type = READ_ONCE(boost_type);
	period = READ_ONCE(boost_period_ms);
	if (unlikely(type < 1 || type > 3 || period < 1 || period > 1000)) {
		atomic64_inc(&boost_errors);
		return;
	}

	ret = set_task_boost(type, period);
	if (likely(!ret))
		atomic64_inc(&boost_ok);
	else
		atomic64_inc(&boost_errors);
}

static int bridge_status_show(struct seq_file *m, void *unused)
{
	seq_printf(m, "registered=%d enable=%d type=%u period_ms=%lu\n",
		hook_registered, enable, boost_type, boost_period_ms);
	seq_printf(m, "tick_calls=%lld ux_hits=%lld boost_ok=%lld errors=%lld\n",
		atomic64_read(&tick_calls), atomic64_read(&ux_hits),
		atomic64_read(&boost_ok), atomic64_read(&boost_errors));
	return 0;
}

static int bridge_status_open(struct inode *inode, struct file *file)
{
	return single_open(file, bridge_status_show, NULL);
}

static const struct proc_ops bridge_status_ops = {
	.proc_open = bridge_status_open,
	.proc_read = seq_read,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static int __init oplus_walt_bridge_init(void)
{
	int ret;

	ret = register_trace_android_vh_scheduler_tick(bridge_tick, NULL);
	if (ret) {
		pr_err("cannot register scheduler tick hook: %d\n", ret);
		return ret;
	}
	hook_registered = true;

	status_node = proc_create("oplus_walt_bridge", 0444, NULL,
			&bridge_status_ops);
	if (!status_node)
		pr_warn("could not create /proc/oplus_walt_bridge\n");

	pr_info("loaded disabled; set enable=1 only for a bounded test\n");
	return 0;
}

static void __exit oplus_walt_bridge_exit(void)
{
	WRITE_ONCE(enable, false);
	if (status_node)
		proc_remove(status_node);
	if (hook_registered) {
		unregister_trace_android_vh_scheduler_tick(bridge_tick, NULL);
		tracepoint_synchronize_unregister();
		hook_registered = false;
	}
	pr_info("unloaded\n");
}

module_init(oplus_walt_bridge_init);
module_exit(oplus_walt_bridge_exit);

MODULE_LICENSE("GPL v2");
MODULE_DESCRIPTION("Mirror OPlus UX tasks into Qualcomm WALT MVP boost");
