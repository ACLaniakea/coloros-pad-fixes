// SPDX-License-Identifier: GPL-2.0-only
/*
 * oplus_sched_assist - the /proc ABI ColorOS uses to mark latency-critical work
 *
 * The ported ColorOS userspace marks UI-critical threads and scenes through a
 * small /proc tree that the Lenovo kernel does not provide. Every mark fails:
 *
 *   E PerformanceService: Failed to open
 *     /proc/oplus_scheduler/sched_assist/sched_assist_scene (2): No such file
 *
 * Measured on this tablet: ~1 failure per second at idle, and the marks that
 * matter most cluster exactly where the user sees stutter - opening and closing
 * apps from the launcher. /proc/oplus_binder/ux_flag alone failed 86 times in a
 * short sample.
 *
 * The cost is not the failed open. It is that nothing is ever marked, so the
 * scheduler treats an animation frame like any other work.
 *
 * What this module does, and does not do:
 *
 *   - Per-task marks (im_flag, sched_impt_task, ux_task) apply a real
 *     utilization floor to the named thread through sched_setattr_nocheck().
 *     That is the honest equivalent of what OPlus' scheduler assist does with
 *     its own in-kernel hooks: give UI work a floor so the governor does not
 *     have to observe the load before ramping.
 *   - Scene and app-level marks are recorded and readable. They carry no
 *     scheduling effect on their own; userspace uses them for its own state.
 *   - /proc/oplus_binder/ux_flag is recorded only. Binder priority inheritance
 *     lives inside the binder driver and cannot be added from a module without
 *     the restricted android_rvh_ hooks this project deliberately refuses.
 *     Accepting the write stops the failure; it does not fake the inheritance.
 *
 * Reads return exactly what was written. Nothing here invents a value.
 */

#include <linux/init.h>
#include <linux/module.h>
#include <linux/proc_fs.h>
#include <linux/sched.h>
#include <linux/seq_file.h>
#include <linux/slab.h>
#include <linux/uaccess.h>
#include <linux/mutex.h>
/*
 * linux/sched/types.h only forward-declares struct sched_attr for in-kernel
 * users; the definition sched_setattr_nocheck() needs lives in the uapi header.
 */
#include <uapi/linux/sched/types.h>

#define ACL_BUF_SZ 128
#define ACL_SAMPLES 4
/*
 * How many threads may carry the floor at once. The framework only ever marks
 * ("r 8189"); it never clears. Without a bound, every thread that was ever
 * foreground keeps a 20% frequency floor forever, including deep in the
 * background - which costs power and competes with whatever is actually on
 * screen. Keeping the most recently marked N and releasing the rest matches
 * what the mark means, and cannot grow without limit.
 */
#define ACL_BOOST_SLOTS 48

static unsigned int ux_util_min = 200;
module_param(ux_util_min, uint, 0644);
MODULE_PARM_DESC(ux_util_min,
	"Utilization floor (0-1024) applied to threads userspace marks as UX/important");

static bool apply_boost = true;
module_param(apply_boost, bool, 0644);
MODULE_PARM_DESC(apply_boost,
	"Apply the utilization floor. Set 0 to record marks without touching scheduling.");

struct acl_node {
	const char *name;
	bool per_task;          /* value is "tid [flag]" and should be boosted */
	char buf[ACL_BUF_SZ];
	size_t len;
	struct mutex lock;
	atomic_t writes;
	atomic_t boosted;
	/*
	 * A few distinct recent payloads. The write formats are not documented
	 * anywhere we can read, and guessing them is how the first revision of
	 * this module ended up boosting nothing: it assumed "tid value" while
	 * the framework actually writes "r 8189" and "fgLauncher 5127".
	 * Sampling what really arrives is cheaper than another guess.
	 */
	char samples[ACL_SAMPLES][ACL_BUF_SZ];
	int nsamples;
};

static struct acl_node nodes[] = {
	{ .name = "sched_assist_scene", .per_task = false },
	{ .name = "im_flag",            .per_task = true  },
	{ .name = "im_flag_app",        .per_task = false },
	{ .name = "sched_impt_task",    .per_task = true  },
	{ .name = "ux_task",            .per_task = true  },
	{ .name = "ux_task_app",        .per_task = false },
	{ .name = "debug_enabled",      .per_task = false },
	{ .name = "lb_enable",          .per_task = false },
};

static struct acl_node binder_ux_flag = { .name = "ux_flag", .per_task = false };
static struct acl_node audio_status   = { .name = "status",  .per_task = false };
static struct acl_node waker_rt_info  = { .name = "rt_info", .per_task = false };

static pid_t boosted_tids[ACL_BOOST_SLOTS];
static int boost_next;
static DEFINE_MUTEX(boost_lock);

static struct proc_dir_entry *dir_sched, *dir_assist, *dir_audio;
static struct proc_dir_entry *dir_binder, *dir_waker;

/*
 * Give one thread a utilization floor. SCHED_FLAG_KEEP_ALL means policy and
 * priority are untouched: only the clamp changes, so a mark can never turn a
 * normal thread into a realtime one.
 */
static int acl_boost_task(pid_t tid, unsigned int umin)
{
	struct task_struct *p;
	struct sched_attr attr = {};
	int ret;

	if (tid <= 0)
		return -EINVAL;

	rcu_read_lock();
	p = find_task_by_vpid(tid);
	if (p)
		get_task_struct(p);
	rcu_read_unlock();
	if (!p)
		return -ESRCH;

	attr.size = sizeof(attr);
	attr.sched_flags = SCHED_FLAG_KEEP_ALL | SCHED_FLAG_UTIL_CLAMP;
	attr.sched_util_min = umin;
	attr.sched_util_max = SCHED_CAPACITY_SCALE;

	ret = sched_setattr_nocheck(p, &attr);
	put_task_struct(p);
	return ret;
}

/*
 * Pull up to two integers out of an arbitrary payload, skipping any leading
 * non-numeric tokens. Returns how many were found.
 */
static int acl_parse_ints(const char *s, int *first, int *second)
{
	int found = 0;
	long v;

	while (*s && found < 2) {
		if ((*s >= '0' && *s <= '9') ||
		    (*s == '-' && s[1] >= '0' && s[1] <= '9')) {
			char *end;

			v = simple_strtol(s, &end, 10);
			if (found == 0)
				*first = (int)v;
			else
				*second = (int)v;
			found++;
			s = end;
			continue;
		}
		s++;
	}
	return found;
}

static void acl_record_sample(struct acl_node *n, const char *payload)
{
	int i;

	for (i = 0; i < n->nsamples; i++)
		if (!strcmp(n->samples[i], payload))
			return;
	if (n->nsamples < ACL_SAMPLES) {
		strscpy(n->samples[n->nsamples], payload, ACL_BUF_SZ);
		n->nsamples++;
	}
}

/*
 * Remember this thread as boosted, releasing whichever thread falls out of the
 * window. Re-marking a thread that is already tracked just refreshes it.
 */
static void acl_track_boost(pid_t tid)
{
	int i;

	mutex_lock(&boost_lock);
	for (i = 0; i < ACL_BOOST_SLOTS; i++) {
		if (boosted_tids[i] == tid) {
			mutex_unlock(&boost_lock);
			return;
		}
	}
	if (boosted_tids[boost_next])
		acl_boost_task(boosted_tids[boost_next], 0);
	boosted_tids[boost_next] = tid;
	boost_next = (boost_next + 1) % ACL_BOOST_SLOTS;
	mutex_unlock(&boost_lock);
}

static void acl_untrack_boost(pid_t tid)
{
	int i;

	mutex_lock(&boost_lock);
	for (i = 0; i < ACL_BOOST_SLOTS; i++)
		if (boosted_tids[i] == tid)
			boosted_tids[i] = 0;
	mutex_unlock(&boost_lock);
}

static void acl_release_all(void)
{
	int i;

	mutex_lock(&boost_lock);
	for (i = 0; i < ACL_BOOST_SLOTS; i++) {
		if (boosted_tids[i]) {
			acl_boost_task(boosted_tids[i], 0);
			boosted_tids[i] = 0;
		}
	}
	boost_next = 0;
	mutex_unlock(&boost_lock);
}

static ssize_t acl_read(struct file *file, char __user *ubuf, size_t count,
			loff_t *ppos)
{
	struct acl_node *n = pde_data(file_inode(file));
	ssize_t ret;

	mutex_lock(&n->lock);
	ret = simple_read_from_buffer(ubuf, count, ppos, n->buf, n->len);
	mutex_unlock(&n->lock);
	return ret;
}

static ssize_t acl_write(struct file *file, const char __user *ubuf,
			 size_t count, loff_t *ppos)
{
	struct acl_node *n = pde_data(file_inode(file));
	char kbuf[ACL_BUF_SZ];
	size_t len = min(count, sizeof(kbuf) - 1);
	int tid = 0, val = 0, parsed;

	if (copy_from_user(kbuf, ubuf, len))
		return -EFAULT;
	kbuf[len] = '\0';

	mutex_lock(&n->lock);
	memcpy(n->buf, kbuf, len);
	n->buf[len] = '\0';
	n->len = len;
	acl_record_sample(n, kbuf);
	mutex_unlock(&n->lock);
	atomic_inc(&n->writes);

	if (n->per_task && apply_boost) {
		/*
		 * Observed payloads: "r 8189" (im_flag) and "fgLauncher 5127"
		 * (sched_impt_task). The leading token is a mode character or a
		 * caller label, not a number, so scan for the first integer and
		 * treat it as the thread id; a second integer, when present, is
		 * the flag, and zero means userspace is clearing the mark.
		 */
		parsed = acl_parse_ints(kbuf, &tid, &val);
		if (parsed >= 1 && tid > 0) {
			unsigned int umin = ux_util_min;

			if (parsed == 2 && val == 0)
				umin = 0;
			if (!acl_boost_task(tid, umin)) {
				atomic_inc(&n->boosted);
				if (umin)
					acl_track_boost(tid);
				else
					acl_untrack_boost(tid);
			}
		}
	}

	return count;
}

static const struct proc_ops acl_proc_ops = {
	.proc_read  = acl_read,
	.proc_write = acl_write,
	.proc_lseek = default_llseek,
};

static void acl_init_node(struct acl_node *n)
{
	mutex_init(&n->lock);
	n->nsamples = 0;
	n->buf[0] = '0';
	n->buf[1] = '\n';
	n->len = 2;
	atomic_set(&n->writes, 0);
	atomic_set(&n->boosted, 0);
}

static int acl_status_show(struct seq_file *m, void *v)
{
	int i;

	int held = 0;

	mutex_lock(&boost_lock);
	for (i = 0; i < ACL_BOOST_SLOTS; i++)
		if (boosted_tids[i])
			held++;
	mutex_unlock(&boost_lock);
	seq_printf(m, "ux_util_min=%u apply_boost=%d held=%d/%d\n",
		   ux_util_min, apply_boost, held, ACL_BOOST_SLOTS);
	for (i = 0; i < (int)ARRAY_SIZE(nodes); i++) {
		int j;

		seq_printf(m, "%-20s writes=%d boosted=%d per_task=%d\n",
			   nodes[i].name, atomic_read(&nodes[i].writes),
			   atomic_read(&nodes[i].boosted), nodes[i].per_task);
		for (j = 0; j < nodes[i].nsamples; j++)
			seq_printf(m, "  sample: %s\n", nodes[i].samples[j]);
	}
	seq_printf(m, "%-20s writes=%d (record only)\n", "binder/ux_flag",
		   atomic_read(&binder_ux_flag.writes));
	for (i = 0; i < binder_ux_flag.nsamples; i++)
		seq_printf(m, "  sample: %s\n", binder_ux_flag.samples[i]);
	seq_printf(m, "%-20s writes=%d (record only)\n", "waker/rt_info",
		   atomic_read(&waker_rt_info.writes));
	return 0;
}

static int acl_status_open(struct inode *inode, struct file *file)
{
	return single_open(file, acl_status_show, NULL);
}

static const struct proc_ops acl_status_ops = {
	.proc_open    = acl_status_open,
	.proc_read    = seq_read,
	.proc_lseek   = seq_lseek,
	.proc_release = single_release,
};

static void acl_cleanup(void)
{
	if (dir_waker)  remove_proc_subtree("waker_identify", NULL);
	if (dir_binder) remove_proc_subtree("oplus_binder", NULL);
	if (dir_sched)  remove_proc_subtree("oplus_scheduler", NULL);
	dir_waker = dir_binder = dir_sched = NULL;
}

static int __init acl_init(void)
{
	size_t i;

	dir_sched = proc_mkdir("oplus_scheduler", NULL);
	if (!dir_sched)
		return -ENOMEM;
	dir_assist = proc_mkdir("sched_assist", dir_sched);
	if (!dir_assist)
		goto fail;

	for (i = 0; i < ARRAY_SIZE(nodes); i++) {
		acl_init_node(&nodes[i]);
		if (!proc_create_data(nodes[i].name, 0666, dir_assist,
				      &acl_proc_ops, &nodes[i]))
			goto fail;
	}

	dir_audio = proc_mkdir("audio", dir_assist);
	if (dir_audio) {
		acl_init_node(&audio_status);
		proc_create_data(audio_status.name, 0666, dir_audio,
				 &acl_proc_ops, &audio_status);
	}

	if (!proc_create("compat_status", 0444, dir_assist, &acl_status_ops))
		goto fail;

	dir_binder = proc_mkdir("oplus_binder", NULL);
	if (dir_binder) {
		acl_init_node(&binder_ux_flag);
		proc_create_data(binder_ux_flag.name, 0666, dir_binder,
				 &acl_proc_ops, &binder_ux_flag);
	}

	dir_waker = proc_mkdir("waker_identify", NULL);
	if (dir_waker) {
		acl_init_node(&waker_rt_info);
		proc_create_data(waker_rt_info.name, 0666, dir_waker,
				 &acl_proc_ops, &waker_rt_info);
	}

	pr_info("oplus_sched_assist: ABI present, util floor %u/%lu\n",
		ux_util_min, SCHED_CAPACITY_SCALE);
	return 0;

fail:
	acl_cleanup();
	return -ENOMEM;
}

static void __exit acl_exit(void)
{
	acl_cleanup();
	/* Do not leave threads carrying a floor nobody will ever clear. */
	acl_release_all();
}

module_init(acl_init);
module_exit(acl_exit);
MODULE_LICENSE("GPL v2");
MODULE_DESCRIPTION("OPlus sched_assist /proc ABI on the Lenovo kernel (ACLaniakea)");
