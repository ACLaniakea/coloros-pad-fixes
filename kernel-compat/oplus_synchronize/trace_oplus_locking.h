/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Copyright (C) 2019 MediaTek Inc.
 */
#undef TRACE_SYSTEM
#define TRACE_SYSTEM oplus_locking

#if !defined(_TRACE_OPLUS_LOCKING_H) || defined(TRACE_HEADER_MULTI_READ)
#define _TRACE_OPLUS_LOCKING_H

#include <linux/sched.h>
#include <linux/types.h>
#include <linux/tracepoint.h>

TRACE_EVENT(reader_opt_rspin_result,

	TP_PROTO(struct task_struct *p, bool result),

	TP_ARGS(p, result),

	TP_STRUCT__entry(
		__field(int,		pid)
		__array(char,		comm, TASK_COMM_LEN)
		__field(bool,		res)),

	TP_fast_assign(
		__entry->pid			= p->pid;
		memcpy(__entry->comm, p->comm, TASK_COMM_LEN);
		__entry->res			= result;),

	TP_printk("pid=%d comm=%s rspin=%d",
		__entry->pid, __entry->comm, __entry->res)
);

DECLARE_EVENT_CLASS(sched_locking_template,

	TP_PROTO(struct task_struct *p, int lk_depth, int lk_nr),

	TP_ARGS(p, lk_depth, lk_nr),

	TP_STRUCT__entry(
		__array(char,	comm, TASK_COMM_LEN)
		__field(int,    pid)
		__field(int,	lk_depth)
		__field(int,	lk_nr)),

	TP_fast_assign(
		memcpy(__entry->comm, p->comm, TASK_COMM_LEN);
		__entry->pid			= p->pid;
		__entry->lk_depth		= lk_depth;
		__entry->lk_nr			= lk_nr;),

	TP_printk("comm=%s pid=%d lk_depth=%d rq_lk_nr=%d",
		__entry->comm, __entry->pid, __entry->lk_depth, __entry->lk_nr)
);

DEFINE_EVENT(sched_locking_template, enqueue_locking_thread,
	TP_PROTO(struct task_struct *p, int lk_depth, int lk_nr),
	TP_ARGS(p, lk_depth, lk_nr));

DEFINE_EVENT(sched_locking_template, dequeue_locking_thread,
	TP_PROTO(struct task_struct *p, int lk_depth, int lk_nr),
	TP_ARGS(p, lk_depth, lk_nr));

DEFINE_EVENT(sched_locking_template, select_locking_thread,
	TP_PROTO(struct task_struct *p, int lk_depth, int lk_nr),
	TP_ARGS(p, lk_depth, lk_nr));

#endif /*_TRACE_OPLUS_LOCKING_H */

#undef TRACE_INCLUDE_PATH
#define TRACE_INCLUDE_PATH .

#undef TRACE_INCLUDE_FILE
#define TRACE_INCLUDE_FILE trace_oplus_locking
/* This part must be outside protection */
#include <trace/define_trace.h>

