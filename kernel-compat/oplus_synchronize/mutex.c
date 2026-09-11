// SPDX-License-Identifier: GPL-2.0-only
/*
 * Copyright (C) 2020-2022 Oplus. All rights reserved.
 */

#include <linux/version.h>
#include <trace/hooks/dtask.h>
#if IS_ENABLED(CONFIG_OPLUS_FEATURE_SCHED_ASSIST)
#include <../kernel/oplus_cpu/sched/sched_assist/sa_common.h>
#endif
#include <linux/sched/clock.h>
#include <linux/sched/rt.h>
#include <linux/module.h>
#include <linux/kernel.h>

#include "locking_main.h"
#ifdef CONFIG_OPLUS_LOCKING_OSQ
#include "mutex.h"
#endif
#ifdef CONFIG_OPLUS_LOCKING_MONITOR
#include "kern_lock_stat.h"
#endif

/*
 * Note:
 * The following structure must be the same as it's definition in kernel/locking/mutex.h
 */
struct mutex_waiter {
	struct list_head	list;
	struct task_struct	*task;
	struct ww_acquire_ctx	*ww_ctx;
#ifdef CONFIG_DEBUG_MUTEXES
	void			*magic;
#endif
};

#define MUTEX_FLAGS		0x07
static inline struct task_struct *__mutex_owner(struct mutex *lock)
{
	return (struct task_struct *)(atomic_long_read(&lock->owner) & ~MUTEX_FLAGS);
}

/*
 * ACLaniakea: mutex 的 ux 插队在本设备上会在 wait_list 里留下悬挂节点。
 * 实测 panic：wake_q_add <- mutex_unlock <- kgsl_devfreq_get_cur_freq，
 * wait_list.next 指向一段已退栈的内核栈（task 指针 = sp+0x50 一带，
 * 再加 task_struct.wake_q 的 0x928 就冲出 16KB 栈顶 -> level 3 translation fault）。
 * 具体触发路径未收敛到一行代码，故默认关闭；优先级继承 mutex_set_inherit_ux
 * 不受影响，rwsem/futex 的 ux 插队也照常。
 * 需要复现研究时：echo Y > /sys/module/oplus_synchronize/parameters/mutex_ux_insert
 */
static bool mutex_ux_insert = false;
module_param(mutex_ux_insert, bool, 0660);

/*
 * ACLaniakea: 原实现返回 void，调用方 mutex_list_add() 无条件 return true，
 * 向内核声称 already_on_list=true。但本函数存在一条什么都不插的路径
 * （pos != head），一旦走到，waiter 没进 wait_list 而内核又跳过了
 * 自己的 list_add_tail，退出时的 list_del 就会破坏链表，
 * 后续 __mutex_unlock_slowpath 取 waiter->task 拿到已释放的栈 → panic。
 * 改为据实返回是否真的插入。
 */
static bool mutex_list_add_ux(struct list_head *entry, struct list_head *head)
{
	struct list_head *pos = NULL;
	struct list_head *n = NULL;
	struct mutex_waiter *waiter = NULL;

	list_for_each_safe(pos, n, head) {
		waiter = list_entry(pos, struct mutex_waiter, list);
		/* ACLaniakea: waiter->task 空指针防御 */
		if (!waiter->task || !test_task_ux(waiter->task)) {
			list_add(entry, waiter->list.prev);
			return true;
		}
	}

	if (pos == head) {
		list_add_tail(entry, head);
		return true;
	}

	/* ACLaniakea: 没插成功，让内核自己 list_add_tail */
	return false;
}

static bool mutex_list_add(struct task_struct *task, struct list_head *entry, struct list_head *head, struct mutex *lock)
{
	bool is_ux = test_task_ux(task);

	if (!entry || !head || !lock)
		return false;

	/* ACLaniakea: 二分用运行时开关，默认关闭 ux 插队 */
	if (!mutex_ux_insert)
		return false;

	/*
	 * ACLaniakea: ★真正的根因★
	 * __mutex_add_waiter() 的第三个参数语义是"插到这个节点之前"，
	 * 不一定是链表头。ww_mutex 路径（ww_mutex.h:53 __ww_waiter_add）
	 * 传进来的是 &pos->list —— 链表中间的某个 waiter 节点。
	 * 内核自己用 list_add_tail(&waiter->list, p) 正好是"插在 p 前"，两种情况都对；
	 * 而 mutex_list_add_ux() 把它当链表头做 list_for_each_safe 绕圈遍历，
	 * 途中会走到真正的 &lock->wait_list 并把它 list_entry 成 mutex_waiter，
	 * 于是 waiter->task 读到的其实是 struct mutex 的 android_oem_data1[0]
	 * （wait_list 偏移 0x10 + task 偏移 0x10 = mutex+0x20）。
	 * 实测 panic：wake_q_add 拿到用户态指针 0x7c973bf000，+0x928(task_struct.wake_q)
	 * 直接翻译错误。ww_mutex 被 DRM/dma-resv 大量使用，所以"一交互就崩"。
	 * 这里直接放弃 ux 插队，交还内核处理。
	 */
	if (head != &lock->wait_list)
		return false;

	/* ACLaniakea: 据实返回，不再无条件声称已入链 */
	if (is_ux)
		return mutex_list_add_ux(entry, head);

	return false;
}

static void mutex_set_inherit_ux(struct mutex *lock, struct task_struct *task)
{
	bool is_ux = false, is_rt = false;
	struct task_struct *owner = NULL;

	if (!lock)
		return;

	is_ux = test_set_inherit_ux(task);
	is_rt = rt_prio(task->prio);

	owner = __mutex_owner(lock);

	/*
	 * ACLaniakea: ★第三处根因★（2026-09-11 第三次 panic）
	 * __mutex_owner() 在互斥量此刻空闲时返回 NULL——持锁者在"等待者决定阻塞"
	 * 与"等待者真正入队"之间把锁放掉就是这个状态，熄屏/亮屏这种锁竞争剧烈的
	 * 时刻概率最高。原实现把它直接喂给 set_inherit_ux()，那边会解引用，
	 * 于是 sys.boot.reason = kernel_panic,null。
	 *
	 * 注意 test_inherit_ux(NULL, ...) 返回 false，所以它挡不住——条件反而成立、
	 * 直接走进去。必须显式判 NULL。
	 */
	if (!owner)
		return;

	if ((is_ux || is_rt) && !test_inherit_ux(owner, INHERIT_UX_MUTEX)) {
		if(oplus_get_ux_state(task) == SCHED_UX_STATE_DEBUG_MAGIC)
			return;
		if(is_ux)
			set_inherit_ux(owner, INHERIT_UX_MUTEX, oplus_get_ux_depth(task), oplus_get_ux_state(task));
		if(is_rt) {
			int ux_state = oplus_get_ux_state(owner);
			ux_state = ux_state > 0 ? ux_state : SA_TYPE_LIGHT;
			set_inherit_ux(owner, INHERIT_UX_MUTEX, oplus_get_ux_depth(task), ux_state);
		}
	}
}

static void mutex_unset_inherit_ux(struct mutex *lock, struct task_struct *task)
{
	if (test_inherit_ux(task, INHERIT_UX_MUTEX))
		unset_inherit_ux(task, INHERIT_UX_MUTEX);
}

/* implement vender hook in kernel/locking/mutex.c */
#ifdef CONFIG_OPLUS_LOCKING_OSQ
static void mutex_update_ux_cnt_when_add(struct mutex *lock)
{
	struct oplus_task_struct *ots = get_oplus_task_struct(current);
	struct oplus_mutex *om = get_oplus_mutex(lock);

	if (unlikely(!ots))
		return;

	/* Record the ux flag when task is added to waiter list */
	ots->lkinfo.is_block_ux = (ots->ux_state & SCHED_ASSIST_UX_MASK) || (current->prio < MAX_RT_PRIO);
	if (ots->lkinfo.is_block_ux)
		atomic_long_inc(&om->count);
}

static void mutex_update_ux_cnt_when_remove(struct mutex *lock)
{
	struct oplus_task_struct *ots = get_oplus_task_struct(current);
	struct oplus_mutex *om = get_oplus_mutex(lock);

	if (unlikely(!ots))
		return;

	/*
	 * Update the ux tasks when tasks exit the waiter list.
	 * We only care about the recorded ux flag, but don't
	 * care about the current ux flag. This avoids the corruption
	 * of the number caused by ux change when in waiter list.
	 */
	if (ots->lkinfo.is_block_ux) {
		/* Count == 0! Something is wrong! */
		WARN_ON_ONCE(!atomic_long_read(&om->count));
		atomic_long_add(-1, &om->count);
		ots->lkinfo.is_block_ux = false;
	}
}
#endif

static void android_vh_alter_mutex_list_add_handler(void *unused, struct mutex *lock,
			struct mutex_waiter *waiter, struct list_head *list, bool *already_on_list)
{
	if (likely(locking_opt_enable(LK_MUTEX_ENABLE))) {
		*already_on_list = mutex_list_add(current, &waiter->list, list, lock);
	}

#ifdef CONFIG_OPLUS_LOCKING_OSQ
	if (likely(locking_opt_enable(LK_OSQ_ENABLE)))
		mutex_update_ux_cnt_when_add(lock);
#endif
}

static void android_vh_mutex_wait_start_handler(void *unused, struct mutex *lock)
{
	if (unlikely(!locking_opt_enable(LK_MUTEX_ENABLE)))
		return;

	mutex_set_inherit_ux(lock, current);
}

static void android_vh_mutex_wait_finish_handler(void *unused, struct mutex *lock)
{
#ifdef CONFIG_OPLUS_LOCKING_OSQ
	mutex_update_ux_cnt_when_remove(lock);
#endif
}

static void android_vh_mutex_unlock_slowpath_handler(void *unused, struct mutex *lock)
{
	mutex_unset_inherit_ux(lock, current);
}

#ifdef CONFIG_OPLUS_LOCKING_OSQ
static int mutex_opt_spin_time_threshold = NSEC_PER_USEC * 60;
static int mutex_ux_opt_spin_time_threshold = NSEC_PER_USEC * 100;
static int mutex_opt_spin_total_cnt = 0;
static int mutex_opt_spin_timeout_exit_cnt = 0;
module_param(mutex_opt_spin_time_threshold, int, 0644);
module_param(mutex_ux_opt_spin_time_threshold, int, 0644);
module_param(mutex_opt_spin_total_cnt, int, 0444);
module_param(mutex_opt_spin_timeout_exit_cnt, int, 0444);

static void android_vh_mutex_opt_spin_start_handler(void *unused, struct mutex *lock,
		bool *time_out, int *cnt)
{
	struct oplus_task_struct *ots = get_oplus_task_struct(current);
	u64 delta;

	if (unlikely(IS_ERR_OR_NULL(ots)))
		return;

	if (unlikely(!locking_opt_enable(LK_OSQ_ENABLE)))
		return;

	if (!ots->lkinfo.opt_spin_start_time) {
		/* Note: Should use atomic operations? */
		mutex_opt_spin_total_cnt++;
		ots->lkinfo.opt_spin_start_time = sched_clock();
		return;
	}

	++(*cnt);
	/* Check whether time is out every 16 times in order to reduce the cost of calling sched_clock() */
	if ((*cnt) & 0xf)
		return;

	delta = sched_clock() - ots->lkinfo.opt_spin_start_time;
	if (((ots->ux_state & SCHED_ASSIST_UX_MASK) && delta > mutex_ux_opt_spin_time_threshold) ||
	    (!(ots->ux_state & SCHED_ASSIST_UX_MASK) && delta > mutex_opt_spin_time_threshold)) {
		/* Note: Should use atomic operations? */
		mutex_opt_spin_timeout_exit_cnt++;
		*time_out = true;
	}
}

static void android_vh_mutex_opt_spin_finish_handler(void *unused, struct mutex *lock, bool taken)
{
	struct oplus_task_struct *ots = get_oplus_task_struct(current);
#ifdef CONFIG_OPLUS_INTERNAL_VERSION
	u64 delta;
#endif

	if (unlikely(IS_ERR_OR_NULL(ots)))
		return;

	if (likely(ots->lkinfo.opt_spin_start_time)) {
#ifdef CONFIG_OPLUS_INTERNAL_VERSION
		delta = sched_clock() - ots->lkinfo.opt_spin_start_time;
		handle_wait_stats(OSQ_MUTEX, delta);
#endif
		ots->lkinfo.opt_spin_start_time = 0;
	}
}

#define MAX_VALID_COUNT (1000)
static void android_vh_mutex_can_spin_on_owner_handler(void *unused, struct mutex *lock, int *retval)
{
	struct oplus_task_struct *ots = get_oplus_task_struct(current);
	struct oplus_mutex *om = get_oplus_mutex(lock);
	long block_ux_cnt;

	if (unlikely(!locking_opt_enable(LK_OSQ_ENABLE) || !ots))
		return;

	/* ux and rt task just go */
	if ((ots->ux_state & SCHED_ASSIST_UX_MASK) || current->prio < MAX_RT_PRIO)
		return;

	/* If some ux or rt task is in the waiter list, non-ux can't optimistic spin. */
	block_ux_cnt = atomic_long_read(&om->count);
	if (block_ux_cnt > 0 && block_ux_cnt < MAX_VALID_COUNT)
		*retval = 0;
}
#endif

void register_mutex_vendor_hooks(void)
{
#ifdef CONFIG_OPLUS_LOCKING_OSQ
	BUILD_BUG_ON(sizeof(struct oplus_mutex) > sizeof(((struct mutex *)0)->android_oem_data1));
#endif
	register_trace_android_vh_alter_mutex_list_add(android_vh_alter_mutex_list_add_handler, NULL);
	register_trace_android_vh_mutex_wait_start(android_vh_mutex_wait_start_handler, NULL);
	register_trace_android_vh_mutex_wait_finish(android_vh_mutex_wait_finish_handler, NULL);
	register_trace_android_vh_mutex_unlock_slowpath(android_vh_mutex_unlock_slowpath_handler, NULL);
#ifdef CONFIG_OPLUS_LOCKING_OSQ
	register_trace_android_vh_mutex_opt_spin_start(android_vh_mutex_opt_spin_start_handler, NULL);
	register_trace_android_vh_mutex_opt_spin_finish(android_vh_mutex_opt_spin_finish_handler, NULL);
	register_trace_android_vh_mutex_can_spin_on_owner(android_vh_mutex_can_spin_on_owner_handler, NULL);
#endif
}

void unregister_mutex_vendor_hooks(void)
{
	unregister_trace_android_vh_alter_mutex_list_add(android_vh_alter_mutex_list_add_handler, NULL);
	unregister_trace_android_vh_mutex_wait_start(android_vh_mutex_wait_start_handler, NULL);
	unregister_trace_android_vh_mutex_wait_finish(android_vh_mutex_wait_finish_handler, NULL);
	unregister_trace_android_vh_mutex_unlock_slowpath(android_vh_mutex_unlock_slowpath_handler, NULL);
#ifdef CONFIG_OPLUS_LOCKING_OSQ
	unregister_trace_android_vh_mutex_opt_spin_start(android_vh_mutex_opt_spin_start_handler, NULL);
	unregister_trace_android_vh_mutex_opt_spin_finish(android_vh_mutex_opt_spin_finish_handler, NULL);
	unregister_trace_android_vh_mutex_can_spin_on_owner(android_vh_mutex_can_spin_on_owner_handler, NULL);
#endif
}
