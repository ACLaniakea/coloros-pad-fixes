// SPDX-License-Identifier: GPL-2.0
/*
 * oplus_lb_bridge —— 把 sched_assist 的 tick 负载均衡入口接回调度器
 *
 * 问题
 * ----
 * 平板上 /proc/oplus_scheduler/sched_assist/lb_stat 全部计数长期为 0，而同
 * ColorOS 版本的对照手机（PKX110）跑 26 小时是：
 *
 *     tick_hit     24,803,944        newidle_hit    104,757,927
 *     tick_running_ux     21,549     newidle_runnable_ux   5,629
 *
 * tick_hit 基本等于调度 tick 次数，平板为 0 意味着函数体一次都没进过。
 *
 * 根因
 * ----
 * 反汇编 oplus_cpu_sched_sched_assist.ko 可见：
 *
 *   1. __oplus_tick_balance 与 __oplus_newidle_balance 都在 __ksymtab 里，
 *      是**导出给外部模块调用**的入口，模块内部没有任何调用者；
 *   2. sched_assist 自己注册的 android_vh_scheduler_tick_handler 只在
 *      global_debug_enabled 打开时调 sa_scene_systrace_c，不碰负载均衡；
 *   3. __oplus_tick_balance 入口处的门读的是 .data..read_mostly+0x4
 *      （即 lb_enable，运行时值为 1）——门是开的，只是没人进。
 *
 * 对照手机的 lsmod 显示 sched_walt 在 oplus_bsp_sched_assist 的使用者列表里，
 * 平板的不在；平板 kallsyms 里 walt 符号 3177 个，手机 4164 个。也就是说
 * **本机内核带的是高通原版 WALT，没有 OPlus 那套补丁**，而 OPlus 版 WALT 正是
 * 在 tick 与 newidle 路径里调用上面两个导出入口的那一方。
 *
 * 后果是 UX 线程（动画、输入、渲染）不会被主动拉到空闲核上，表现为亮屏瞬间
 * 与动画期间的卡顿 —— 这不是参数没调好，是这条路径压根没接通。
 *
 * 做法
 * ----
 * __oplus_tick_balance 的签名恰好就是 android_vh_scheduler_tick 处理器的签名：
 *
 *     f4b0: ldr  w8, [x9]        ; x9 = lb_enable
 *     f4b4: cbz  w8, <退出>
 *     f4b8: mov  x19, x1         ; 只用 x1 = rq，x0(data) 从不读
 *
 * 所以把它本身注册成该 hook 的处理器即可，连转接函数都不需要。
 * android_vh_* 是普通 vendor hook，允许多个处理器共存，不会顶掉 sched_assist
 * 自己那个做 systrace 的处理器。
 *
 * 为什么不一并接 newidle 那半边
 * ----------------------------
 * android_rvh_sched_newidle_balance 是**受限** hook。GKI 的
 * android_rvh_probe_register() 在 static key 已翻起时直接返回 -EBUSY，
 * 即只允许一个探针，而高通 WALT 已经占了它。硬接会失败，所以本模块只做
 * tick 这一半 —— 对照手机上这一半是 2480 万次命中的那条路径。
 *
 * 风险与撤销
 * ----------
 * ★ 这是本项目唯一一个会在调度 tick 上下文里触发任务迁移的自加模块。
 *   OPlus 从未在"高通原版 WALT + OPlus 负载均衡"这个组合上测过，属于我们
 *   自己承担的新组合。
 *
 * 三级撤销，由轻到重：
 *   1. echo 0 > /proc/oplus_scheduler/sched_assist/lb_enable
 *      —— 入口第一道门就被关掉，模块留着但不做任何事，免重启；
 *   2. rmmod oplus_lb_bridge
 *      —— 本模块只注册普通 vh，可正常摘除；
 *   3. 从 oplus-bsp-module 的模块清单里删掉本行，重启即彻底还原。
 *
 * 验证：加载后 lb_stat 的 tick_hit 应开始随时间增长。
 */

#define pr_fmt(fmt) "oplus_lb_bridge: " fmt

#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/tracepoint.h>
#include <trace/hooks/sched.h>

/*
 * 由 oplus_cpu_sched_sched_assist 以 EXPORT_SYMBOL（非 GPL 版）导出，
 * 因此不能走 __symbol_get() —— 它只接受 GPL_ONLY 符号 —— 只能正常链接。
 * 构建时通过 KBUILD_EXTRA_SYMBOLS 提供该符号的 CRC（0x6870a9af，直接从
 * .ko 的 __kcrctab 段取出，见 tools/build_lb_bridge.sh）。
 *
 * struct rq 是调度器私有类型，模块侧只当不透明指针透传，不解引用。
 */
extern void __oplus_tick_balance(void *data, struct rq *rq);

/*
 * 必须经这层转接，不能把 __oplus_tick_balance 本身注册成探针。
 *
 * 2026-09-11 第一次实测就是直接注册它，开机 49 分钟后 insmod 立刻 panic：
 *
 *   Kernel panic - not syncing: Oops - CFI: Fatal exception in interrupt
 *   __oplus_tick_balance+0x0/0x354 [oplus_cpu_sched_sched_assist]
 *     at __traceiter_android_vh_scheduler_tick+0x40/0x68
 *
 * 崩在进入函数体之前，是 kCFI 拦的。__traceiter_* 通过函数指针间接调用探针，
 * 会校验目标的 kCFI 类型标识前缀；而 __oplus_tick_balance 在 OPlus 自己的
 * 源码里是被**直接调用**的，地址从未被取过，编译器因此没给它生成那个前缀。
 *
 * 这反过来证实了本模块的前提：它本来就是给别人直接调的入口，不是探针。
 * 所以注册本模块自己的 lb_bridge_tick（签名与 hook 完全一致，CFI 标识由我们
 * 这次编译生成），再在里面**直接调用** —— 直接调用不走 CFI 校验。
 */
static void lb_bridge_tick(void *data, struct rq *rq)
{
	__oplus_tick_balance(data, rq);
}

static bool probe_registered;

static int __init oplus_lb_bridge_init(void)
{
	int ret;

	ret = register_trace_android_vh_scheduler_tick(lb_bridge_tick, NULL);
	if (ret) {
		pr_err("注册 android_vh_scheduler_tick 失败：%d\n", ret);
		return ret;
	}

	probe_registered = true;
	pr_info("tick 负载均衡已接通（lb_enable 仍是总开关）\n");
	return 0;
}

static void __exit oplus_lb_bridge_exit(void)
{
	if (probe_registered) {
		unregister_trace_android_vh_scheduler_tick(lb_bridge_tick, NULL);
		probe_registered = false;
		/* 等所有在途的探针调用退出后再让模块卸载 */
		tracepoint_synchronize_unregister();
	}
	pr_info("已摘除\n");
}

module_init(oplus_lb_bridge_init);
module_exit(oplus_lb_bridge_exit);

MODULE_LICENSE("GPL v2");
MODULE_DESCRIPTION("Bridge sched_assist tick load balance onto android_vh_scheduler_tick");
