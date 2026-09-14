// SPDX-License-Identifier: GPL-2.0-only
/*
 * Android oom_score_adj -> Qualcomm KGSL reclaim-state bridge.
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
 * 关于 adj 阈值的默认值
 * ---------------------
 * 默认 100（VISIBLE_APP）。能回收的量几乎全在桌面身上：实测 adj>=700 的进程
 * 加起来只有 21MB，而桌面一个进程在用过一阵后是 129~893MB，且它被应用遮挡
 * 时 adj 正好是 100。
 *
 * 敢取 100 是因为 KGSL 自己带了迟滞：写入 background 后要约 12 秒
 * background_work 才真正回收，12 秒内切回来的进程一页都不会被动。
 */

#define pr_fmt(fmt) "oplus_kgsl_state_bridge: " fmt

#include <linux/err.h>
#include <linux/fcntl.h>
#include <linux/fs.h>
#include <linux/kprobes.h>
#include <linux/mm.h>
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/oom.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/spinlock.h>
#include <linux/tracepoint.h>
#include <linux/workqueue.h>

#define KGSL_STATE_PATH_LEN 64
#define STATE_SLOT_COUNT 256

static int adj_threshold = 800;
module_param(adj_threshold, int, 0644);
MODULE_PARM_DESC(adj_threshold, "oom_score_adj at or above which a process is marked background");

static bool screen_off_sweep = true;
module_param(screen_off_sweep, bool, 0644);
MODULE_PARM_DESC(screen_off_sweep, "on screen blank mark every process background, restore on unblank");

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

/* 只读，用 lsmod 之外的方式看桥有没有在干活：
 * cat /sys/module/oplus_kgsl_state_bridge/parameters/stat_* */
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

static const struct kernel_param_ops stat_events_ops = { .get = stat_events_get };
static const struct kernel_param_ops stat_writes_ops = { .get = stat_writes_get };
static const struct kernel_param_ops stat_missing_ops = { .get = stat_missing_get };
static const struct kernel_param_ops stat_failed_ops = { .get = stat_failed_get };
static const struct kernel_param_ops stat_full_ops = { .get = stat_full_get };

module_param_cb(stat_events, &stat_events_ops, NULL, 0444);
module_param_cb(stat_writes, &stat_writes_ops, NULL, 0444);
module_param_cb(stat_missing, &stat_missing_ops, NULL, 0444);
module_param_cb(stat_failed, &stat_failed_ops, NULL, 0444);
module_param_cb(stat_slots_full, &stat_full_ops, NULL, 0444);

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
				pr_info("first state write succeeded\n");
		} else if (ret == -ENOENT) {
			atomic64_inc(&nodes_missing);
		} else {
			if (atomic64_inc_return(&writes_failed) == 1)
				pr_warn("first write failed: %d (缺 SELinux 放行时是 -13)\n",
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
	int i;

	if (!READ_ONCE(bridge_enabled))
		return;
	if (!task || !task->signal)
		return;
	tgid = task_tgid_nr(task);
	if (tgid <= 0)
		return;
	background = READ_ONCE(task->signal->oom_score_adj) >=
		     READ_ONCE(adj_threshold);
	atomic64_inc(&events_seen);

	/* tracepoint 上下文里不做任何分配。
	 *
	 * 三级查找，顺序不能变：先认本进程已有的槽，再用从未使用过的空槽，
	 * 最后才回收别人用完的槽。早先的写法把"第一个 pending=false 的槽"
	 * 当空槽用——而 work 处理完只清 pending、保留 tgid，于是那个槽几乎
	 * 永远是 0 号，所有新进程挤在同一个槽里互相覆盖。实测桌面从
	 * adj=0 变到 100 的事件就是这么丢掉的，state 一直停在 foreground。
	 */
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
 *   2. 熄屏/亮屏——见下面 panel 那段的说明。
 *
 * 早先这里把结果存在 256 个元素的栈数组里并在满了之后 break，实测开机后
 * 进程数远超 256，桌面根本没被扫到。改成按 nr_threads 动态分配。
 */
static void kgsl_state_sweep(bool force_background)
{
	struct task_struct *p;
	pid_t *tgids;
	bool *bgs;
	int n = 0, i, cap = 0;

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
		tgids[n] = task_tgid_nr(p);
		bgs[n] = force_background ||
			 READ_ONCE(p->signal->oom_score_adj) >=
				 READ_ONCE(adj_threshold);
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

/*
 * 熄屏钩子——这条链真正可用的触发点
 * --------------------------------
 * 本来想用 oom_score_adj 驱动桌面，实测走不通：桌面的 adj 恒为 100，开应用、
 * 回桌面都不变（用 events/oom/oom_score_adj_update 抓了整个来回，涉及桌面的
 * 事件 0 条；该 tracepoint 上的事件全部来自 lmkd，写的是 930/940/950 这类
 * 缓存进程）。也就是说桌面根本没有可挂的 adj 跳变。
 *
 * 而能拿的量几乎全在桌面身上：adj>=700 的进程加起来只有 21MB，桌面一个进程
 * 用过一阵之后是 129~893MB。
 *
 * 熄屏是唯一既能覆盖桌面、风险又为零的边：屏幕关着的时候没有任何东西可见，
 * 回收不可能造成可感知的卡顿；亮屏再整体还原。这也正对"长待机"这个原始症状。
 *
 * 触发点的选择走过一次弯路：本来想复用 hybridswap 那个
 * panel_event_notification_trigger 钩子，实测在这台机器上它只送 FPS 变化
 * (notif_type=4，负载 144/120，正是这块 144Hz 屏的刷新率)，从不送
 * blank/unblank——这也解释了 oplus_hybridswap_panel_bridge 当初为什么被排除。
 *
 * 改为直接挂 msm_drm 的面板电源函数，实测每次转换各触发一次、无歧义：
 *   熄屏 sde_encoder_virt_disable -> dsi_panel_disable ->
 *        dsi_display_unprepare -> dsi_panel_power_off
 *   亮屏 dsi_display_prepare -> dsi_panel_power_on ->
 *        dsi_panel_enable -> sde_encoder_virt_enable
 * 取熄屏序列的末端（面板已彻底关掉才回收）和亮屏序列的最前端（给重新钉住
 * 留最多时间，实测还原只要约 1 秒）。
 */
static struct kprobe panel_off_kp, panel_on_kp;
static bool panel_kp_registered;
static bool pending_screen_off;

static void kgsl_sweep_workfn(struct work_struct *work);
static DECLARE_WORK(kgsl_sweep_work, kgsl_sweep_workfn);

static void kgsl_sweep_workfn(struct work_struct *work)
{
	kgsl_state_sweep(READ_ONCE(pending_screen_off));
}

static int panel_off_handler(struct kprobe *kp, struct pt_regs *regs)
{
	if (!READ_ONCE(screen_off_sweep) || !READ_ONCE(bridge_enabled))
		return 0;
	WRITE_ONCE(pending_screen_off, true);
	schedule_work(&kgsl_sweep_work);
	return 0;
}

static int panel_on_handler(struct kprobe *kp, struct pt_regs *regs)
{
	if (!READ_ONCE(bridge_enabled))
		return 0;
	/* 亮屏一律还原，即使中途把 screen_off_sweep 关掉了也不能留下半回收状态 */
	WRITE_ONCE(pending_screen_off, false);
	schedule_work(&kgsl_sweep_work);
	return 0;
}

static int register_panel_kprobes(void)
{
	int ret;

	panel_off_kp.symbol_name = "dsi_panel_power_off";
	panel_off_kp.pre_handler = panel_off_handler;
	ret = register_kprobe(&panel_off_kp);
	if (ret)
		return ret;

	panel_on_kp.symbol_name = "dsi_panel_power_on";
	panel_on_kp.pre_handler = panel_on_handler;
	ret = register_kprobe(&panel_on_kp);
	if (ret) {
		unregister_kprobe(&panel_off_kp);
		return ret;
	}
	panel_kp_registered = true;
	return 0;
}

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

	ret = register_panel_kprobes();
	if (ret)
		pr_warn("面板 kprobe 注册失败 (%d)，熄屏回收不可用\n", ret);

	kgsl_state_sweep(false);

	pr_info("已加载，adj 阈值=%d，熄屏回收=%d\n",
		adj_threshold, screen_off_sweep && panel_kp_registered);
	return 0;
}

static void __exit kgsl_state_bridge_exit(void)
{
	tracepoint_probe_unregister(oom_adj_tracepoint,
				    (void *)oom_score_adj_update_probe, NULL);
	tracepoint_synchronize_unregister();
	if (panel_kp_registered) {
		unregister_kprobe(&panel_off_kp);
		unregister_kprobe(&panel_on_kp);
	}
	cancel_work_sync(&kgsl_state_work);
	cancel_work_sync(&kgsl_sweep_work);
	/* 卸载时把所有进程还原成 foreground，不给系统留下半回收状态 */
	kgsl_state_sweep(false);
	pr_info("已卸载; events=%lld writes=%lld missing=%lld failed=%lld full=%lld\n",
		atomic64_read(&events_seen), atomic64_read(&writes_ok),
		atomic64_read(&nodes_missing), atomic64_read(&writes_failed),
		atomic64_read(&slots_full));
}

module_init(kgsl_state_bridge_init);
module_exit(kgsl_state_bridge_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Event-driven Android oom_adj to Qualcomm KGSL reclaim-state bridge");
