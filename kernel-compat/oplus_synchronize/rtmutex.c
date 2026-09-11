// SPDX-License-Identifier: GPL-2.0-only
/*
 * Copyright (C) 2020-2022 Oplus. All rights reserved.
 */

#include <linux/spinlock_types.h>
#include <linux/ww_mutex.h>
#include <kernel/locking/rtmutex_common.h>
#include <linux/sched/rt.h>
#include <trace/hooks/sched.h>
#include <trace/hooks/dtask.h>

#include <../kernel/oplus_cpu/sched/sched_assist/sa_common.h>
#include "locking_main.h"

#define UX_TRANS_PRIO (100)

static inline bool is_ux_thread(struct task_struct *task)
{
#if IS_ENABLED(CONFIG_OPLUS_FEATURE_SCHED_ASSIST)
	int type = get_ux_state_type(task);

	return (type != UX_STATE_NONE) && (type != UX_STATE_INVALID);
#else
	return false;
#endif
}

static inline bool is_pi_futex_ux(struct task_struct *task)
{
#if IS_ENABLED(CONFIG_OPLUS_FEATURE_SCHED_ASSIST)
	return test_inherit_ux(task, INHERIT_UX_PIFUTEX);
#else
	return false;
#endif
}

static void rtmutex_force_update_handler(void *unused, struct task_struct *p,
		struct task_struct *pi_task, int *update)
{
	struct oplus_task_struct *ots;
	bool pi_task_ux;
	int p_type;

	if (p->normal_prio < MAX_RT_PRIO)
		return;

	ots = get_oplus_task_struct(pi_task);
	if (IS_ERR_OR_NULL(ots))
		pi_task = NULL;

	pi_task_ux = pi_task ? is_ux_thread(pi_task) : false;
	p_type = get_ux_state_type(p);

	if (pi_task_ux && (p_type == UX_STATE_NONE)) {
		if (unlikely(!locking_opt_enable(LK_PIFUTEX_ENABLE)))
			return;

		set_inherit_ux(p, INHERIT_UX_PIFUTEX, ots->ux_depth, ots->ux_state);
		*update = 1;
	} else if (!pi_task_ux && is_pi_futex_ux(p)) {
		unset_inherit_ux(p, INHERIT_UX_PIFUTEX);
		*update = 1;
	}

	if (pi_task_ux || (p_type == UX_STATE_NONE) || *update) {
		cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
			"pi_task:comm=%-12s pid=%d prio=%d ux=%d inherit_ux=%llx p:comm=%-12s pid=%d prio=%d ux_type=%d inherit_ux=%llx update=%d\n",
			pi_task ? pi_task->comm : "na", pi_task ? pi_task->pid : -1, pi_task ? pi_task->prio : -1,
			pi_task_ux, oplus_get_inherit_ux(pi_task), p->comm, p->pid, p->prio, p_type, oplus_get_inherit_ux(p),
			*update);
	}
}

static void task_blocks_on_rtmutex_handler(void *unused, struct rt_mutex_base *lock,
		struct rt_mutex_waiter *waiter, struct task_struct *task,
		struct ww_acquire_ctx *ww_ctx, unsigned int *chwalk)
{
	if (unlikely(!locking_opt_enable(LK_PIFUTEX_ENABLE)))
		return;

	if (*chwalk == RT_MUTEX_FULL_CHAINWALK)
		*chwalk = RT_MUTEX_MIN_CHAINWALK;
}

static void rtmutex_waiter_prio_handler(void *unused, struct task_struct *task, int *waiter_prio)
{
	if (unlikely(!locking_opt_enable(LK_PIFUTEX_ENABLE)))
		return;

	if (is_ux_thread(task)) {
		*waiter_prio = UX_TRANS_PRIO;
		cond_trace_printk(locking_opt_debug(LK_DEBUG_FTRACE),
			"set task(comm=%-12s pid=%d) rtmutex waiter's prio to 100\n",
			task->comm, task->pid);
	}
}

void register_rtmutex_vendor_hooks(void)
{
	register_trace_android_rvh_rtmutex_force_update(
		rtmutex_force_update_handler,
		NULL);
	register_trace_android_vh_task_blocks_on_rtmutex(
		task_blocks_on_rtmutex_handler,
		NULL);
	register_trace_android_vh_rtmutex_waiter_prio(
		rtmutex_waiter_prio_handler,
		NULL);
}

void unregister_rtmutex_vendor_hooks(void)
{
}
