/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef _LINUX_HEALTHINFO_FG_H
#define _LINUX_HEALTHINFO_FG_H

#include <linux/sched.h>

/* Provided by the already loaded OPlus sched_info module on the tablet. */
extern int is_fg(struct task_struct *task);

static inline int current_is_fg(void)
{
	return is_fg(current);
}

#endif
