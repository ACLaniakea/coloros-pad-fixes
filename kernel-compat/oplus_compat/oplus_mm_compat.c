// SPDX-License-Identifier: GPL-2.0-only
/*
 * Conservative OPlus memory ABI compatibility for the TB710FU ColorOS port.
 *
 * This deliberately remains a standard-zram backend.  It supplies useful
 * OPlus control/telemetry interfaces and clamps (never raises) reclaim
 * swappiness, but does not pretend that HybridSwap is present.
 */

#define pr_fmt(fmt) "oplus_mm_compat: " fmt

#include <linux/atomic.h>
#include <linux/gfp.h>
#include <linux/kprobes.h>
#include <linux/ktime.h>
#include <linux/mm.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/swap.h>
#include <linux/uaccess.h>
#include <linux/vmstat.h>
#include <trace/events/vmscan.h>
#include <trace/hooks/mm.h>
#include <trace/hooks/vmscan.h>

#define INPUT_LEN 128
#define ORDER_BUCKETS 16
#define KSWAPD_SAFETY_CEILING 50
#define DIRECT_SAFETY_CEILING 20

static struct proc_dir_entry *oplus_mem_dir;
static struct proc_dir_entry *swappiness_para_entry;
static struct proc_dir_entry *dynamic_swappiness_entry;
static struct proc_dir_entry *alloc_adjust_entry;
static struct proc_dir_entry *kswapd_debug_entry;
static struct proc_dir_entry *kswapd_load_entry;
static struct proc_dir_entry *status_entry;
static bool owns_oplus_mem_dir;

/* Values are caps: this module never increases the kernel-selected value. */
static int kswapd_cap = 20;
static int direct_cap = 10;
static int threshold1_cap = 20;
static int threshold1_mb = 1024;
static int threshold2_cap = 10;
static int threshold2_mb = 512;

/* Risky upstream behaviour is intentionally disabled on this port. */
static bool alloc_adjust_enabled;
static bool alloc_stats_enabled;
static bool kswapd_stats_enabled;
static DEFINE_MUTEX(hook_lock);

static atomic64_t zsmalloc_cma_bypass_count;
static atomic64_t slowpath_count[ORDER_BUCKETS];
static atomic64_t kswapd_wake_count[ORDER_BUCKETS];
static atomic64_t kswapd_runtime_ns[ORDER_BUCKETS];
static u64 kswapd_start_ns[MAX_NUMNODES][ORDER_BUCKETS];

static int order_bucket(unsigned int order)
{
	return min_t(unsigned int, order, ORDER_BUCKETS - 1);
}

static int dynamic_kswapd_cap(void)
{
	unsigned long file_pages;
	int cap = READ_ONCE(kswapd_cap);

	file_pages = global_node_page_state(NR_ACTIVE_FILE) +
		global_node_page_state(NR_INACTIVE_FILE);

	/* OPlus ABI thresholds are MiB (MiB * 256 pages on a 4 KiB page). */
	if (READ_ONCE(threshold1_mb) > 0 &&
	    file_pages >= ((unsigned long)READ_ONCE(threshold1_mb) << 8))
		cap = READ_ONCE(threshold1_cap);
	else if (READ_ONCE(threshold2_mb) > 0 &&
		 file_pages >= ((unsigned long)READ_ONCE(threshold2_mb) << 8))
		cap = READ_ONCE(threshold2_cap);

	return clamp(cap, 0, 200);
}

static void tune_swappiness(void *unused, int *swappiness)
{
	int cap = current_is_kswapd() ? dynamic_kswapd_cap() :
		READ_ONCE(direct_cap);

	/* Preserve cgroup/global decisions and only suppress excessive reclaim. */
	if (*swappiness > cap)
		*swappiness = cap;
}

static int zsmalloc_pre_handler(struct kprobe *probe, struct pt_regs *regs)
{
	/*
	 * AArch64 passes zs_malloc(pool, size, gfp) in x0/x1/x2. This Lenovo GKI's
	 * zram adds __GFP_CMA to both its fast and slow zsmalloc calls, draining
	 * the display reserve under the port's 8 GiB workload. Strip CMA at the
	 * zsmalloc boundary; all unrelated page, DMA, display and camera
	 * allocations remain untouched.
	 */
	gfp_t flags = (gfp_t)regs->regs[2];

	if (flags & __GFP_CMA) {
		regs->regs[2] = (unsigned long)(flags & ~__GFP_CMA);
		atomic64_inc(&zsmalloc_cma_bypass_count);
	}
	return 0;
}

static struct kprobe zsmalloc_probe = {
	.symbol_name = "zs_malloc",
	.pre_handler = zsmalloc_pre_handler,
};

static void slowpath_stat(void *unused, gfp_t gfp_mask, unsigned int order,
			  unsigned long delta)
{
	if (READ_ONCE(alloc_stats_enabled))
		atomic64_inc(&slowpath_count[order_bucket(order)]);
}

static void kswapd_start(void *unused, int nid, int zid, int order)
{
	int bucket;

	if (!READ_ONCE(kswapd_stats_enabled) || nid < 0 || nid >= MAX_NUMNODES)
		return;
	bucket = order_bucket(order < 0 ? 0 : order);
	WRITE_ONCE(kswapd_start_ns[nid][bucket], ktime_get_mono_fast_ns());
	atomic64_inc(&kswapd_wake_count[bucket]);
}

static void kswapd_done(void *unused, int nid, unsigned int highest_zoneidx,
			unsigned int alloc_order, unsigned int reclaim_order)
{
	u64 start, now;
	int bucket;

	if (!READ_ONCE(kswapd_stats_enabled) || nid < 0 || nid >= MAX_NUMNODES)
		return;
	bucket = order_bucket(alloc_order);
	start = READ_ONCE(kswapd_start_ns[nid][bucket]);
	if (!start)
		return;
	now = ktime_get_mono_fast_ns();
	if (now > start)
		atomic64_add(now - start, &kswapd_runtime_ns[bucket]);
	WRITE_ONCE(kswapd_start_ns[nid][bucket], 0);
}

static int set_alloc_adjust(bool enabled)
{
	/* Accept the stock ABI write, but never enable its unsafe high-order
	 * reclaim suppression without the complete OPlus memory stack. */
	WRITE_ONCE(alloc_adjust_enabled, false);
	return 0;
}

static int set_alloc_stats(bool enabled)
{
	int ret = 0;

	mutex_lock(&hook_lock);
	if (enabled == alloc_stats_enabled)
		goto out;
	if (enabled) {
		ret = register_trace_android_vh_alloc_pages_slowpath(
			slowpath_stat, NULL);
		if (!ret)
			WRITE_ONCE(alloc_stats_enabled, true);
	} else {
		WRITE_ONCE(alloc_stats_enabled, false);
		unregister_trace_android_vh_alloc_pages_slowpath(
			slowpath_stat, NULL);
		tracepoint_synchronize_unregister();
	}
out:
	mutex_unlock(&hook_lock);
	return ret;
}

static int set_kswapd_stats(bool enabled)
{
	int ret = 0;

	mutex_lock(&hook_lock);
	if (enabled == kswapd_stats_enabled)
		goto out;
	if (enabled) {
		ret = register_trace_mm_vmscan_kswapd_wake(kswapd_start, NULL);
		if (ret)
			goto out;
		ret = register_trace_android_vh_vmscan_kswapd_done(
			kswapd_done, NULL);
		if (ret) {
			unregister_trace_mm_vmscan_kswapd_wake(kswapd_start, NULL);
			tracepoint_synchronize_unregister();
			goto out;
		}
		WRITE_ONCE(kswapd_stats_enabled, true);
	} else {
		WRITE_ONCE(kswapd_stats_enabled, false);
		unregister_trace_android_vh_vmscan_kswapd_done(kswapd_done, NULL);
		unregister_trace_mm_vmscan_kswapd_wake(kswapd_start, NULL);
		tracepoint_synchronize_unregister();
	}
out:
	mutex_unlock(&hook_lock);
	return ret;
}

static int copy_trimmed(const char __user *buf, size_t count, char *kbuf)
{
	if (!count || count >= INPUT_LEN)
		return -EINVAL;
	if (copy_from_user(kbuf, buf, count))
		return -EFAULT;
	kbuf[count] = '\0';
	strim(kbuf);
	return 0;
}

static int parse_bool(const char __user *buf, size_t count, bool *value)
{
	char kbuf[INPUT_LEN];
	int val, ret;

	ret = copy_trimmed(buf, count, kbuf);
	if (ret)
		return ret;
	ret = kstrtoint(kbuf, 0, &val);
	if (ret || (val != 0 && val != 1))
		return -EINVAL;
	*value = val;
	return 0;
}

static int swappiness_show(struct seq_file *m, void *v)
{
	seq_printf(m, "vm_swappiness: %d\n", READ_ONCE(kswapd_cap));
	seq_printf(m, "direct_swappiness: %d\n", READ_ONCE(direct_cap));
	seq_puts(m, "swapd_swappiness: 0\n");
	seq_printf(m, "kswapd_swappiness: %d\n", dynamic_kswapd_cap());
	return 0;
}

static int swappiness_open(struct inode *inode, struct file *file)
{
	return single_open(file, swappiness_show, NULL);
}

static ssize_t swappiness_write(struct file *file, const char __user *buf,
				size_t count, loff_t *ppos)
{
	char kbuf[INPUT_LEN];
	int val, ret;

	ret = copy_trimmed(buf, count, kbuf);
	if (ret)
		return ret;
	if (sscanf(kbuf, "vm_swappiness=%d", &val) == 1) {
		if (val < 0 || val > 200)
			return -EINVAL;
		WRITE_ONCE(kswapd_cap, min(val, KSWAPD_SAFETY_CEILING));
	} else if (sscanf(kbuf, "direct_swappiness=%d", &val) == 1) {
		if (val < 0 || val > 200)
			return -EINVAL;
		WRITE_ONCE(direct_cap, min(val, DIRECT_SAFETY_CEILING));
	} else {
		return -EINVAL;
	}
	return count;
}

static const struct proc_ops swappiness_ops = {
	.proc_open = swappiness_open,
	.proc_read = seq_read,
	.proc_write = swappiness_write,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static int dynamic_show(struct seq_file *m, void *v)
{
	seq_printf(m, "%d %d %d %d\n", READ_ONCE(threshold1_cap),
		   READ_ONCE(threshold1_mb), READ_ONCE(threshold2_cap),
		   READ_ONCE(threshold2_mb));
	return 0;
}

static int dynamic_open(struct inode *inode, struct file *file)
{
	return single_open(file, dynamic_show, NULL);
}

static ssize_t dynamic_write(struct file *file, const char __user *buf,
			     size_t count, loff_t *ppos)
{
	char kbuf[INPUT_LEN];
	int cap1, mb1, cap2, mb2, ret;

	ret = copy_trimmed(buf, count, kbuf);
	if (ret)
		return ret;
	if (sscanf(kbuf, "%d %d %d %d", &cap1, &mb1, &cap2, &mb2) != 4 ||
	    cap1 < 0 || cap1 > 200 || cap2 < 0 || cap2 > 200 ||
	    mb1 < 0 || mb2 < 0 || mb1 > 65536 || mb2 > 65536)
		return -EINVAL;
	WRITE_ONCE(threshold1_cap, min(cap1, KSWAPD_SAFETY_CEILING));
	WRITE_ONCE(threshold1_mb, mb1);
	WRITE_ONCE(threshold2_cap, min(cap2, KSWAPD_SAFETY_CEILING));
	WRITE_ONCE(threshold2_mb, mb2);
	return count;
}

static const struct proc_ops dynamic_ops = {
	.proc_open = dynamic_open,
	.proc_read = seq_read,
	.proc_write = dynamic_write,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static int alloc_adjust_show(struct seq_file *m, void *v)
{
	seq_printf(m, "%d\n", READ_ONCE(alloc_adjust_enabled));
	return 0;
}

static int alloc_adjust_open(struct inode *inode, struct file *file)
{
	return single_open(file, alloc_adjust_show, NULL);
}

static ssize_t alloc_adjust_write(struct file *file, const char __user *buf,
				  size_t count, loff_t *ppos)
{
	bool enabled;
	int ret = parse_bool(buf, count, &enabled);

	if (ret)
		return ret;
	ret = set_alloc_adjust(enabled);
	if (ret)
		return ret;
	return count;
}

static const struct proc_ops alloc_adjust_ops = {
	.proc_open = alloc_adjust_open,
	.proc_read = seq_read,
	.proc_write = alloc_adjust_write,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static int kswapd_debug_show(struct seq_file *m, void *v)
{
	int i;

	if (!READ_ONCE(alloc_stats_enabled)) {
		seq_puts(m, "0\n");
		return 0;
	}
	seq_puts(m, "order\t count\n");
	for (i = 0; i < ORDER_BUCKETS; i++)
		seq_printf(m, "%d%s\t %lld\n", i,
			   i == ORDER_BUCKETS - 1 ? "+" : "",
			   atomic64_read(&slowpath_count[i]));
	return 0;
}

static int kswapd_debug_open(struct inode *inode, struct file *file)
{
	return single_open(file, kswapd_debug_show, NULL);
}

static ssize_t kswapd_debug_write(struct file *file, const char __user *buf,
				  size_t count, loff_t *ppos)
{
	bool enabled;
	int ret = parse_bool(buf, count, &enabled);

	if (ret)
		return ret;
	ret = set_alloc_stats(enabled);
	if (ret)
		return ret;
	return count;
}

static const struct proc_ops kswapd_debug_ops = {
	.proc_open = kswapd_debug_open,
	.proc_read = seq_read,
	.proc_write = kswapd_debug_write,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static int kswapd_load_show(struct seq_file *m, void *v)
{
	int i;

	if (!READ_ONCE(kswapd_stats_enabled)) {
		seq_puts(m, "0\n");
		return 0;
	}
	seq_puts(m, "order\t wakes\t runtime(ms)\n");
	for (i = 0; i < ORDER_BUCKETS; i++)
		seq_printf(m, "%d%s\t %lld\t %lld\n", i,
			   i == ORDER_BUCKETS - 1 ? "+" : "",
			   atomic64_read(&kswapd_wake_count[i]),
			   atomic64_read(&kswapd_runtime_ns[i]) / 1000000);
	return 0;
}

static int kswapd_load_open(struct inode *inode, struct file *file)
{
	return single_open(file, kswapd_load_show, NULL);
}

static ssize_t kswapd_load_write(struct file *file, const char __user *buf,
				 size_t count, loff_t *ppos)
{
	bool enabled;
	int ret = parse_bool(buf, count, &enabled);

	if (ret)
		return ret;
	ret = set_kswapd_stats(enabled);
	if (ret)
		return ret;
	return count;
}

static const struct proc_ops kswapd_load_ops = {
	.proc_open = kswapd_load_open,
	.proc_read = seq_read,
	.proc_write = kswapd_load_write,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static int status_show(struct seq_file *m, void *v)
{
	struct sysinfo si;

	si_swapinfo(&si);
	seq_puts(m, "backend=standard_zram\n");
	seq_puts(m, "hybridswap=0\n");
	seq_puts(m, "writeback=0\n");
	seq_puts(m, "swappiness_mode=clamp_only\n");
	seq_printf(m, "kswapd_safety_ceiling=%d\n", KSWAPD_SAFETY_CEILING);
	seq_printf(m, "direct_safety_ceiling=%d\n", DIRECT_SAFETY_CEILING);
	seq_printf(m, "swap_total_kb=%lu\n", si.totalswap << (PAGE_SHIFT - 10));
	seq_printf(m, "swap_free_kb=%lu\n", si.freeswap << (PAGE_SHIFT - 10));
	seq_printf(m, "alloc_adjust=%d\n", READ_ONCE(alloc_adjust_enabled));
	seq_puts(m, "zsmalloc_cma_guard=1\n");
	seq_printf(m, "zsmalloc_cma_bypass=%lld\n",
		   atomic64_read(&zsmalloc_cma_bypass_count));
	return 0;
}

static int status_open(struct inode *inode, struct file *file)
{
	return single_open(file, status_show, NULL);
}

static const struct proc_ops status_ops = {
	.proc_open = status_open,
	.proc_read = seq_read,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static void remove_proc_entries(void)
{
	proc_remove(status_entry);
	proc_remove(kswapd_load_entry);
	proc_remove(kswapd_debug_entry);
	proc_remove(alloc_adjust_entry);
	proc_remove(dynamic_swappiness_entry);
	proc_remove(swappiness_para_entry);
	if (owns_oplus_mem_dir)
		proc_remove(oplus_mem_dir);
	oplus_mem_dir = NULL;
	owns_oplus_mem_dir = false;
}

static struct proc_dir_entry *create_oplus_mem_file(const char *name,
		const char *full_name, umode_t mode, const struct proc_ops *ops)
{
	if (oplus_mem_dir)
		return proc_create(name, mode, oplus_mem_dir, ops);
	/* proc_mkdir() returns NULL when another OPlus module owns the directory. */
	return proc_create(full_name, mode, NULL, ops);
}

static int create_proc_entries(void)
{
	oplus_mem_dir = proc_mkdir("oplus_mem", NULL);
	if (oplus_mem_dir)
		owns_oplus_mem_dir = true;
	swappiness_para_entry = create_oplus_mem_file("swappiness_para",
		"oplus_mem/swappiness_para", 0666, &swappiness_ops);
	dynamic_swappiness_entry = create_oplus_mem_file("dynamic_swappiness",
		"oplus_mem/dynamic_swappiness", 0666, &dynamic_ops);
	alloc_adjust_entry = create_oplus_mem_file("alloc_adjust_ctrl",
		"oplus_mem/alloc_adjust_ctrl", 0660, &alloc_adjust_ops);
	kswapd_debug_entry = create_oplus_mem_file("kswapd_debug",
		"oplus_mem/kswapd_debug", 0660, &kswapd_debug_ops);
	kswapd_load_entry = create_oplus_mem_file("kswapd_load_stat",
		"oplus_mem/kswapd_load_stat", 0660, &kswapd_load_ops);
	status_entry = create_oplus_mem_file("compat_status",
		"oplus_mem/compat_status", 0440, &status_ops);
	if (!swappiness_para_entry || !dynamic_swappiness_entry ||
	    !alloc_adjust_entry || !kswapd_debug_entry || !kswapd_load_entry ||
	    !status_entry) {
		remove_proc_entries();
		return -ENOMEM;
	}
	return 0;
}

static int __init oplus_mm_compat_init(void)
{
	int ret;

	ret = create_proc_entries();
	if (ret)
		return ret;
	ret = register_trace_android_vh_tune_swappiness(tune_swappiness, NULL);
	if (ret)
		goto err_proc;
	ret = register_kprobe(&zsmalloc_probe);
	if (ret)
		goto err_swappiness;

	pr_info("loaded: standard-zram backend, clamp-only reclaim policy, zsmalloc CMA guard\n");
	return 0;
err_swappiness:
	unregister_trace_android_vh_tune_swappiness(tune_swappiness, NULL);
	tracepoint_synchronize_unregister();
err_proc:
	remove_proc_entries();
	return ret;
}

static void __exit oplus_mm_compat_exit(void)
{
	set_kswapd_stats(false);
	set_alloc_stats(false);
	unregister_kprobe(&zsmalloc_probe);
	unregister_trace_android_vh_tune_swappiness(tune_swappiness, NULL);
	tracepoint_synchronize_unregister();
	remove_proc_entries();
	pr_info("unloaded\n");
}

module_init(oplus_mm_compat_init);
module_exit(oplus_mm_compat_exit);

MODULE_DESCRIPTION("Conservative OPlus memory ABI compatibility");
MODULE_AUTHOR("ColorOS Pad Fixes");
MODULE_LICENSE("GPL");
MODULE_IMPORT_NS(MINIDUMP);
