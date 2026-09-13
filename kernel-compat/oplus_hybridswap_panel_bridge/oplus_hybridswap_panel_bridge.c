// SPDX-License-Identifier: GPL-2.0-only
#include <linux/atomic.h>
#include <linux/kprobes.h>
#include <linux/module.h>

static atomic_t display_off = ATOMIC_INIT(0);
static unsigned long blank_events, unblank_events, skipped_wakeups;
module_param_named(blank_events, blank_events, ulong, 0444);
module_param_named(unblank_events, unblank_events, ulong, 0444);
module_param_named(skipped_wakeups, skipped_wakeups, ulong, 0444);

struct panel_event_prefix { unsigned int notif_type; };

static int panel_pre(struct kprobe *kp, struct pt_regs *regs)
{
	struct panel_event_prefix *e = (void *)regs->regs[1];

	if (regs->regs[0] != 1 || !e) /* PRIMARY only */
		return 0;
	if (READ_ONCE(e->notif_type) == 1) { /* DRM_PANEL_EVENT_BLANK */
		atomic_set(&display_off, 1);
		blank_events++;
	} else if (READ_ONCE(e->notif_type) == 2) { /* UNBLANK */
		atomic_set(&display_off, 0);
		unblank_events++;
	}
	return 0;
}

/* wake_all_swapd() is void; skip its untouched prologue by returning to LR. */
static int wake_pre(struct kprobe *kp, struct pt_regs *regs)
{
	if (!atomic_read(&display_off))
		return 0;
	regs->pc = regs->regs[30];
	skipped_wakeups++;
	return 1;
}

static struct kprobe panel_kp = {
	.symbol_name = "panel_event_notification_trigger",
	.pre_handler = panel_pre,
};

/*
 * wake_all_swapd is a local HybridSwap symbol.  Kprobes resolves local module
 * symbols by name (the tracing interface has verified this on the target), so
 * do not derive an address from /sys/module/.../.text: hardened kernels expose
 * that value as zero.
 */
static struct kprobe wake_kp = {
	.symbol_name = "wake_all_swapd",
	.pre_handler = wake_pre,
};

static int __init bridge_init(void)
{
	int ret;
	ret = register_kprobe(&panel_kp);
	if (ret)
		return ret;
	ret = register_kprobe(&wake_kp);
	if (ret) {
		unregister_kprobe(&panel_kp);
		return ret;
	}
	return 0;
}
static void __exit bridge_exit(void)
{
	atomic_set(&display_off, 0);
	unregister_kprobe(&wake_kp);
	unregister_kprobe(&panel_kp);
}
module_init(bridge_init); module_exit(bridge_exit);
MODULE_LICENSE("GPL v2");
MODULE_DESCRIPTION("version-locked HybridSwap panel kprobe bridge");
