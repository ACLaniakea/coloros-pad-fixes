// SPDX-License-Identifier: GPL-2.0-only
/*
 * Android oom_score_adj -> Qualcomm KGSL reclaim-state bridge (v4.1.2).
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
 * 实现上的两条红线
 * ----------------
 * 1. 不调用 msm_kgsl 的私有函数（kCFI 拒绝间接调用 kgsl_proc_state_store，
 *    实测 panic；kobject 偏移也是猜的）。
 * 2. 只走公开接口：filp_open + kernel_write / read_iter 读写那组 sysfs 节点，
 *    和 shell 里 `echo background > .../state` 完全等价。
 *
 * 需要配套的 SELinux 放行（见 fix-module/module/sepolicy.rule）：
 *   allow kernel vendor_sysfs_kgsl_proc file { open read write getattr };
 *   allow kernel vendor_sysfs_kgsl_proc dir  { search };
 *
 * v4.1.2：按对照机 PKX110（ColorOS 手机）的行为重做策略
 * -----------------------------------------------------
 * v4.1.1 自称"只在内存压力时回收"，实际不成立：oom_score_adj tracepoint 在
 * 每次 adj 变到 >=100 时就立刻写 background，与压力无关。熄屏后桌面被锁屏
 * 遮住、adj 变 100，实测 20 秒内 114MB 显存被全部回收，解锁时再从 zram 读
 * 回——与 4.1.0 熄屏回收（桌面 p95 帧时 24ms -> 101~121ms）同一个机制，只是
 * 从 30 分钟提前到了十几秒。壁纸、com.oplus.blur、侧边栏、输入法这些常驻
 * adj 100~200 的界面组件也一直处于被回收状态。另外 shrinker 的 count_objects
 * 里同步做全量扫描，几百次 filp_open 落在触发直接回收的线程上。
 *
 * 对照机给出的经验：
 *   - 手机的 GPU 显存根本不钉（Unevictable 恒 69MB），只在压力下由内核按页
 *     回收冷页，不存在"整进程一次性回收"；
 *   - 前台 UI 栈（桌面/SF/SystemUI/输入法/壁纸）不回收是正确行为，手机解锁时
 *     桌面一帧都不重画；
 *   - 原厂 performance HAL 在亮屏后 ~1.2s 暂停 swapd ~6s，专门保护亮屏后的
 *     第一个交互窗口。
 *
 * 所以 v4.1.2 的规则：
 *   1. 标 background 只发生在内存压力下（shrinker 触发、节流、在工作队列里做，
 *      shrinker 回调本身不做任何文件 I/O）。
 *   2. 缓存进程（adj >= cached_adj，默认 900）压力下即可标记——不在屏幕上画东西。
 *   3. UI 带（ui_adj <= adj < cached_adj）要同时满足：亮屏、不在亮屏保护窗口
 *      （wake_grace_sec）、连续处于该带 >= ui_min_age_sec、KGSL 映射量
 *      >= ui_min_mapped_mb。默认 192MB 只会命中堆起来的桌面这类大户，
 *      壁纸/模糊/输入法/侧边栏永远不碰。
 *   4. 熄屏立即把 UI 带全部还原 foreground：重新钉住的代价付在熄屏期间，
 *      而不是解锁动画上。
 *   5. adj 真正变化并落到 cached_adj 以下时立即还原 foreground（tracepoint）。
 *   6. adj < 0 的常驻系统进程永远不碰；卸载时所有进程还原 foreground。
 */

#define pr_fmt(fmt) "oplus_kgsl_state_bridge: " fmt

#include <linux/err.h>
#include <linux/fcntl.h>
#include <linux/fs.h>
#include <linux/jiffies.h>
#include <linux/kernel.h>
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
#include <linux/uio.h>
#include <linux/workqueue.h>

#define KGSL_PROC_PATH_LEN 72
#define STATE_SLOT_COUNT 256
#define ADJ_UNKNOWN INT_MIN

static bool bridge_enabled = true;
module_param(bridge_enabled, bool, 0644);
MODULE_PARM_DESC(bridge_enabled, "master switch; turning it off stops new background marks");

static int cached_adj = 900;
module_param(cached_adj, int, 0644);
MODULE_PARM_DESC(cached_adj, "oom_score_adj at or above which a process may be marked background under pressure");

static bool ui_band_enabled = true;
module_param(ui_band_enabled, bool, 0644);
MODULE_PARM_DESC(ui_band_enabled, "also consider large covered UI processes (ui_adj <= adj < cached_adj)");

static int ui_adj = 100;
module_param(ui_adj, int, 0644);
MODULE_PARM_DESC(ui_adj, "lower bound of the UI band (VISIBLE_APP)");

static unsigned int ui_min_mapped_mb = 192;
module_param(ui_min_mapped_mb, uint, 0644);
MODULE_PARM_DESC(ui_min_mapped_mb, "UI band: minimum gpumem_mapped before a process is worth reclaiming");

static unsigned int ui_min_age_sec = 60;
module_param(ui_min_age_sec, uint, 0644);
MODULE_PARM_DESC(ui_min_age_sec, "UI band: seconds a process must stay covered before it may be marked");

static unsigned int wake_grace_sec = 30;
module_param(wake_grace_sec, uint, 0644);
MODULE_PARM_DESC(wake_grace_sec, "no UI band marking for this long after the panel powers on");

static unsigned int shrinker_throttle_sec = 30;
module_param(shrinker_throttle_sec, uint, 0644);
MODULE_PARM_DESC(shrinker_throttle_sec, "minimum seconds between pressure sweeps");

struct kgsl_state_slot {
	pid_t tgid;
	int last_adj;
	unsigned long ui_since;	/* 进入 UI 带的时刻；只在从带下方跨入时刷新 */
	bool restore_pending;
};

static struct tracepoint *oom_adj_tracepoint;
static struct kgsl_state_slot state_slots[STATE_SLOT_COUNT];
static DEFINE_SPINLOCK(state_lock);

static unsigned long load_jiffies;
static bool display_on = true;
static unsigned long wake_jiffies;
static bool panel_kp_registered;
static bool shrinker_registered;

static atomic64_t events_seen = ATOMIC64_INIT(0);
static atomic64_t writes_bg = ATOMIC64_INIT(0);
static atomic64_t writes_fg = ATOMIC64_INIT(0);
static atomic64_t nodes_missing = ATOMIC64_INIT(0);
static atomic64_t writes_failed = ATOMIC64_INIT(0);
static atomic64_t reads_failed = ATOMIC64_INIT(0);
static atomic64_t slots_full = ATOMIC64_INIT(0);
static atomic64_t shrinker_calls = ATOMIC64_INIT(0);
static atomic64_t shrinker_sweeps = ATOMIC64_INIT(0);
static atomic64_t marked_cached = ATOMIC64_INIT(0);
static atomic64_t marked_ui = ATOMIC64_INIT(0);
static atomic64_t screen_off_restores = ATOMIC64_INIT(0);

#define DEFINE_STAT(name, counter)						\
	static int stat_##name##_get(char *buf, const struct kernel_param *kp)	\
	{									\
		return sysfs_emit(buf, "%lld\n", atomic64_read(&counter));	\
	}									\
	static const struct kernel_param_ops stat_##name##_ops = {		\
		.get = stat_##name##_get,					\
	};									\
	module_param_cb(stat_##name, &stat_##name##_ops, NULL, 0444)

DEFINE_STAT(events, events_seen);
DEFINE_STAT(writes_bg, writes_bg);
DEFINE_STAT(writes_fg, writes_fg);
DEFINE_STAT(missing, nodes_missing);
DEFINE_STAT(failed, writes_failed);
DEFINE_STAT(read_failed, reads_failed);
DEFINE_STAT(slots_full, slots_full);
DEFINE_STAT(shrinker_calls, shrinker_calls);
DEFINE_STAT(shrinker_sweeps, shrinker_sweeps);
DEFINE_STAT(marked_cached, marked_cached);
DEFINE_STAT(marked_ui, marked_ui);
DEFINE_STAT(screen_off_restores, screen_off_restores);

static int display_on_get(char *buf, const struct kernel_param *kp)
{
	return sysfs_emit(buf, "%d\n", READ_ONCE(display_on));
}
static const struct kernel_param_ops display_on_ops = { .get = display_on_get };
module_param_cb(display_on, &display_on_ops, NULL, 0444);

/* 全部是导出符号的直接调用，没有间接调用 msm_kgsl 私有函数，kCFI 无话可说。 */
static int write_kgsl_state(pid_t tgid, bool background)
{
	static const char bg[] = "background";
	static const char fg[] = "foreground";
	char path[KGSL_PROC_PATH_LEN];
	const char *state = background ? bg : fg;
	size_t len = background ? sizeof(bg) - 1 : sizeof(fg) - 1;
	struct file *filp;
	loff_t pos = 0;
	ssize_t ret;

	snprintf(path, sizeof(path), "/sys/class/kgsl/kgsl/proc/%d/state", tgid);
	filp = filp_open(path, O_WRONLY, 0);
	if (IS_ERR(filp)) {
		ret = PTR_ERR(filp);
		if (ret == -ENOENT) {
			atomic64_inc(&nodes_missing);
		} else if (atomic64_inc_return(&writes_failed) == 1) {
			pr_warn("首次写入失败: %zd（缺 SELinux 放行时是 -13）\n", ret);
		}
		return (int)ret;
	}

	ret = kernel_write(filp, state, len, &pos);
	filp_close(filp, NULL);
	if (ret != len) {
		atomic64_inc(&writes_failed);
		return ret < 0 ? (int)ret : -EIO;
	}
	atomic64_inc(background ? &writes_bg : &writes_fg);
	return 0;
}

/*
 * 读 gpumem_mapped（字节数）。kernel_read 在本内核的符号白名单里没有导出，
 * 这里直接走 sysfs 文件自己的 read_iter —— 函数指针类型与声明一致，kCFI 放行。
 * 返回 <0 表示读不到；调用方按"不回收"处理。
 */
static long long read_kgsl_mapped(pid_t tgid)
{
	char path[KGSL_PROC_PATH_LEN];
	char buf[32];
	struct kvec kv = { .iov_base = buf, .iov_len = sizeof(buf) - 1 };
	struct iov_iter iter;
	struct kiocb kiocb;
	struct file *filp;
	long long value;
	ssize_t ret;

	snprintf(path, sizeof(path), "/sys/class/kgsl/kgsl/proc/%d/gpumem_mapped", tgid);
	filp = filp_open(path, O_RDONLY, 0);
	if (IS_ERR(filp)) {
		if (PTR_ERR(filp) != -ENOENT &&
		    atomic64_inc_return(&reads_failed) == 1)
			pr_warn("首次读取失败: %ld（缺 SELinux read 放行时是 -13）\n",
				PTR_ERR(filp));
		return PTR_ERR(filp);
	}
	if (!(filp->f_mode & FMODE_READ) || !filp->f_op || !filp->f_op->read_iter) {
		filp_close(filp, NULL);
		atomic64_inc(&reads_failed);
		return -EINVAL;
	}

	init_sync_kiocb(&kiocb, filp);
	kiocb.ki_pos = 0;
	iov_iter_kvec(&iter, ITER_DEST, &kv, 1, kv.iov_len);
	ret = filp->f_op->read_iter(&kiocb, &iter);
	filp_close(filp, NULL);
	if (ret <= 0) {
		atomic64_inc(&reads_failed);
		return ret < 0 ? ret : -EIO;
	}
	buf[ret] = '\0';
	if (kstrtoll(strim(buf), 10, &value)) {
		atomic64_inc(&reads_failed);
		return -EINVAL;
	}
	return value;
}

/* ---- tracepoint：记录 adj 变化，落回 cached_adj 以下时排队还原 ---- */

static void kgsl_restore_workfn(struct work_struct *work);
static DECLARE_WORK(kgsl_restore_work, kgsl_restore_workfn);

static void kgsl_restore_workfn(struct work_struct *work)
{
	unsigned long flags;
	pid_t tgid;
	int i;

	for (;;) {
		tgid = 0;
		spin_lock_irqsave(&state_lock, flags);
		for (i = 0; i < STATE_SLOT_COUNT; i++) {
			if (!state_slots[i].restore_pending)
				continue;
			tgid = state_slots[i].tgid;
			state_slots[i].restore_pending = false;
			break;
		}
		spin_unlock_irqrestore(&state_lock, flags);
		if (!tgid)
			break;
		/* 进程本来就是 foreground 时 KGSL 那边是 no-op */
		write_kgsl_state(tgid, false);
	}
}

static void oom_score_adj_update_probe(void *unused, struct task_struct *task)
{
	struct kgsl_state_slot *slot = NULL;
	unsigned long flags;
	bool queue = false;
	pid_t tgid;
	int i, adj, old_adj, cached, ui;

	if (!task || !task->signal)
		return;
	tgid = task_tgid_nr(task);
	if (tgid <= 0)
		return;
	adj = READ_ONCE(task->signal->oom_score_adj);
	atomic64_inc(&events_seen);

	cached = READ_ONCE(cached_adj);
	ui = READ_ONCE(ui_adj);

	/* tracepoint 上下文里不做任何分配。三级查找：本进程已有的槽 ->
	 * 从未用过的空槽 -> 没有待办的旧槽。 */
	spin_lock_irqsave(&state_lock, flags);
	for (i = 0; i < STATE_SLOT_COUNT; i++) {
		if (state_slots[i].tgid == tgid) {
			slot = &state_slots[i];
			break;
		}
	}
	if (!slot) {
		for (i = 0; i < STATE_SLOT_COUNT && !slot; i++)
			if (!state_slots[i].tgid)
				slot = &state_slots[i];
		for (i = 0; i < STATE_SLOT_COUNT && !slot; i++)
			if (!state_slots[i].restore_pending)
				slot = &state_slots[i];
		if (slot) {
			slot->tgid = tgid;
			slot->last_adj = ADJ_UNKNOWN;
			slot->ui_since = 0;
			slot->restore_pending = false;
		}
	}
	if (slot) {
		old_adj = slot->last_adj;
		/* 同值重写不是状态变化：否则 AMS 每次重申 adj 都会把桌面拉回
		 * foreground，形成"回收->钉回"的来回。 */
		if (adj != old_adj) {
			slot->last_adj = adj;
			if (adj >= ui && (old_adj == ADJ_UNKNOWN || old_adj < ui))
				slot->ui_since = jiffies;
			if (adj >= 0 && adj < cached) {
				slot->restore_pending = true;
				queue = true;
			}
		}
	}
	spin_unlock_irqrestore(&state_lock, flags);

	if (!slot)
		atomic64_inc(&slots_full);
	else if (queue)
		schedule_work(&kgsl_restore_work);
}

static void find_oom_adj_tracepoint(struct tracepoint *tp, void *unused)
{
	if (!strcmp(tp->name, "oom_score_adj_update"))
		oom_adj_tracepoint = tp;
}

/* ---- 进程快照 ---- */

struct proc_snapshot {
	pid_t tgid;
	int adj;
};

static struct proc_snapshot *snapshot_processes(int *count)
{
	struct proc_snapshot *snap;
	struct task_struct *p;
	int n = 0, cap = 0;

	/* nr_threads 没有导出给模块用，先数一遍再分配 */
	rcu_read_lock();
	for_each_process(p)
		cap++;
	rcu_read_unlock();
	cap += 64;

	snap = kvmalloc_array(cap, sizeof(*snap), GFP_KERNEL);
	if (!snap)
		return NULL;

	rcu_read_lock();
	for_each_process(p) {
		if (n >= cap)
			break;
		if (!p->signal || !p->mm || (p->flags & PF_KTHREAD))
			continue;
		snap[n].tgid = task_tgid_nr(p);
		snap[n].adj = READ_ONCE(p->signal->oom_score_adj);
		n++;
	}
	rcu_read_unlock();
	*count = n;
	return snap;
}

static unsigned long ui_since_of(pid_t tgid)
{
	unsigned long since = READ_ONCE(load_jiffies);
	unsigned long flags;
	int i;

	spin_lock_irqsave(&state_lock, flags);
	for (i = 0; i < STATE_SLOT_COUNT; i++) {
		if (state_slots[i].tgid == tgid) {
			if (state_slots[i].ui_since)
				since = state_slots[i].ui_since;
			break;
		}
	}
	spin_unlock_irqrestore(&state_lock, flags);
	return since;
}

/* 把 [0, cached_adj) 的进程还原 foreground；all=true 时不看 adj（卸载用） */
static void restore_foreground(bool all)
{
	struct proc_snapshot *snap;
	int n = 0, i, cached = READ_ONCE(cached_adj);

	snap = snapshot_processes(&n);
	if (!snap)
		return;
	for (i = 0; i < n; i++) {
		if (all || (snap[i].adj >= 0 && snap[i].adj < cached))
			write_kgsl_state(snap[i].tgid, false);
	}
	kvfree(snap);
}

/* ---- 压力扫描（工作队列里执行，允许睡眠） ---- */

static struct work_struct kgsl_screen_off_work;
static void kgsl_pressure_workfn(struct work_struct *work);
static DECLARE_WORK(kgsl_pressure_work, kgsl_pressure_workfn);

static bool ui_band_allowed(void)
{
	unsigned long grace = msecs_to_jiffies(READ_ONCE(wake_grace_sec) * 1000U);

	if (!READ_ONCE(ui_band_enabled) || !READ_ONCE(panel_kp_registered))
		return false;
	if (!READ_ONCE(display_on))
		return false;
	return !time_before(jiffies, READ_ONCE(wake_jiffies) + grace);
}

static void kgsl_pressure_workfn(struct work_struct *work)
{
	struct proc_snapshot *snap;
	int n = 0, i, ncached = 0, nui = 0;
	int cached = READ_ONCE(cached_adj);
	int ui = READ_ONCE(ui_adj);
	unsigned long age = msecs_to_jiffies(READ_ONCE(ui_min_age_sec) * 1000U);
	long long min_mapped = (long long)READ_ONCE(ui_min_mapped_mb) << 20;
	bool ui_ok = ui_band_allowed();

	if (!READ_ONCE(bridge_enabled))
		return;
	snap = snapshot_processes(&n);
	if (!snap)
		return;

	for (i = 0; i < n; i++) {
		int adj = snap[i].adj;
		pid_t tgid = snap[i].tgid;

		if (adj < 0)
			continue;
		if (adj >= cached) {
			if (!write_kgsl_state(tgid, true))
				ncached++;
			continue;
		}
		if (!ui_ok || adj < ui)
			continue;
		if (time_before(jiffies, ui_since_of(tgid) + age))
			continue;
		if (read_kgsl_mapped(tgid) < min_mapped)
			continue;
		/* 熄屏钩子可能在读的这段时间里触发；再确认一次，免得标完正赶上熄屏 */
		if (!READ_ONCE(display_on))
			break;
		if (!write_kgsl_state(tgid, true)) {
			nui++;
			pr_info("UI 带标记 background: tgid=%d adj=%d\n", tgid, adj);
			/* 写入与熄屏还原交错时补一次还原，保证熄屏后 UI 带不留 background */
			if (!READ_ONCE(display_on))
				schedule_work(&kgsl_screen_off_work);
		}
	}
	kvfree(snap);
	atomic64_add(ncached, &marked_cached);
	atomic64_add(nui, &marked_ui);
}

/* shrinker 只负责"有压力"这个信号：节流后排队，回调里不做任何文件 I/O。 */
static unsigned long next_sweep_jiffies;
static bool sweep_armed;
static DEFINE_SPINLOCK(throttle_lock);

static unsigned long kgsl_shrinker_count_objects(struct shrinker *s,
						 struct shrink_control *sc)
{
	unsigned long flags;
	bool fire = false;

	atomic64_inc(&shrinker_calls);
	if (!READ_ONCE(bridge_enabled))
		return 0;

	spin_lock_irqsave(&throttle_lock, flags);
	if (!sweep_armed || !time_before(jiffies, next_sweep_jiffies)) {
		sweep_armed = true;
		next_sweep_jiffies = jiffies +
			msecs_to_jiffies(READ_ONCE(shrinker_throttle_sec) * 1000U);
		fire = true;
	}
	spin_unlock_irqrestore(&throttle_lock, flags);

	if (fire && queue_work(system_unbound_wq, &kgsl_pressure_work))
		atomic64_inc(&shrinker_sweeps);
	/* 返回 0：实际能回收多少由 KGSL 自己的 shrinker 报 */
	return 0;
}

static unsigned long kgsl_shrinker_scan_objects(struct shrinker *s,
						struct shrink_control *sc)
{
	return SHRINK_STOP;
}

static struct shrinker kgsl_state_shrinker = {
	.count_objects = kgsl_shrinker_count_objects,
	.scan_objects = kgsl_shrinker_scan_objects,
	.seeks = DEFAULT_SEEKS,
};

/*
 * ---- 面板电源 ----
 * panel_event_notification_trigger 在本机只送 FPS 变化，从不送 blank/unblank；
 * 挂 msm_drm 的面板电源函数，4.1.0 实测每次转换各触发一次、无歧义。
 * kprobe 回调里只改标志并排队。
 */
static struct kprobe panel_off_kp, panel_on_kp;

static void kgsl_screen_off_workfn(struct work_struct *work)
{
	/* 期间又亮屏了就不必做（亮屏前本来就没有 UI 带在熄屏后被标记） */
	if (READ_ONCE(display_on))
		return;
	restore_foreground(false);
	atomic64_inc(&screen_off_restores);
}
static DECLARE_WORK(kgsl_screen_off_work, kgsl_screen_off_workfn);

static int panel_off_handler(struct kprobe *kp, struct pt_regs *regs)
{
	WRITE_ONCE(display_on, false);
	schedule_work(&kgsl_screen_off_work);
	return 0;
}

static int panel_on_handler(struct kprobe *kp, struct pt_regs *regs)
{
	WRITE_ONCE(wake_jiffies, jiffies);
	WRITE_ONCE(display_on, true);
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
	return 0;
}

static int __init kgsl_state_bridge_init(void)
{
	int ret;

	load_jiffies = jiffies;
	wake_jiffies = jiffies;

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
		pr_warn("面板 kprobe 注册失败 (%d)：屏幕状态未知，UI 带停用，只回收缓存进程\n",
			ret);
	else
		WRITE_ONCE(panel_kp_registered, true);

	ret = register_shrinker(&kgsl_state_shrinker, "oplus_kgsl_state_bridge");
	if (ret)
		pr_warn("注册 shrinker 失败 (%d)，不会有任何 background 标记\n", ret);
	else
		shrinker_registered = true;

	/* 上一版可能留下被标成 background 的 UI 进程，加载时先还原 */
	restore_foreground(false);

	pr_info("v4.1.2 已加载：cached_adj=%d ui_band=%d(adj>=%d, >=%uMB, >=%us, 亮屏保护 %us) panel=%d\n",
		cached_adj, ui_band_enabled, ui_adj, ui_min_mapped_mb,
		ui_min_age_sec, wake_grace_sec, panel_kp_registered);
	return 0;
}

static void __exit kgsl_state_bridge_exit(void)
{
	WRITE_ONCE(bridge_enabled, false);
	if (shrinker_registered)
		unregister_shrinker(&kgsl_state_shrinker);
	if (panel_kp_registered) {
		unregister_kprobe(&panel_on_kp);
		unregister_kprobe(&panel_off_kp);
	}
	tracepoint_probe_unregister(oom_adj_tracepoint,
				    (void *)oom_score_adj_update_probe, NULL);
	tracepoint_synchronize_unregister();
	cancel_work_sync(&kgsl_pressure_work);
	cancel_work_sync(&kgsl_screen_off_work);
	cancel_work_sync(&kgsl_restore_work);
	/* 真正全部还原，不看 adj，不给系统留下半回收状态 */
	restore_foreground(true);
	pr_info("已卸载; events=%lld bg=%lld fg=%lld missing=%lld failed=%lld read_failed=%lld cached=%lld ui=%lld sweeps=%lld/%lld off_restores=%lld\n",
		atomic64_read(&events_seen), atomic64_read(&writes_bg),
		atomic64_read(&writes_fg), atomic64_read(&nodes_missing),
		atomic64_read(&writes_failed), atomic64_read(&reads_failed),
		atomic64_read(&marked_cached), atomic64_read(&marked_ui),
		atomic64_read(&shrinker_sweeps), atomic64_read(&shrinker_calls),
		atomic64_read(&screen_off_restores));
}

module_init(kgsl_state_bridge_init);
module_exit(kgsl_state_bridge_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Pressure-gated, display-aware Android oom_adj to Qualcomm KGSL reclaim-state bridge (v4.1.2)");
