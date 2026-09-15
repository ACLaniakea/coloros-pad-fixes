/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ACLaniakea: shared /proc directories for the ported OPlus modules.
 *
 * Several independent OPlus modules each call proc_mkdir("oplus_mem", NULL)
 * (and "task_info"), relying on a NULL return to fall back to the
 * "oplus_mem/<node>" path form.  That fallback works, but on 6.1 every
 * duplicate proc_mkdir() hits WARN() in proc_register ("already registered")
 * and taints the kernel — one full stack dump per module at every boot.
 * Probing the directory first avoids both WARNs: proc_mkdir() warns when the
 * name exists and a path-style proc_create() warns when it does not.
 *
 * filp_open() is exported by the r3 kernel's symbol list.
 */
#ifndef _OPLUS_PROC_COMPAT_H
#define _OPLUS_PROC_COMPAT_H

#include <linux/err.h>
#include <linux/fcntl.h>
#include <linux/fs.h>
#include <linux/proc_fs.h>

static inline bool oplus_proc_dir_exists(const char *name)
{
	char path[64];
	struct file *filp;

	snprintf(path, sizeof(path), "/proc/%s", name);
	filp = filp_open(path, O_RDONLY | O_DIRECTORY, 0);
	if (IS_ERR(filp))
		return false;
	filp_close(filp, NULL);
	return true;
}

/*
 * proc_mkdir() for a top-level directory that other modules may already own.
 * Returns NULL when the directory exists, which is exactly the case the
 * callers' "dir/<node>" fallback already handles.
 */
static inline struct proc_dir_entry *oplus_proc_mkdir_shared(const char *name)
{
	if (oplus_proc_dir_exists(name))
		return NULL;
	return proc_mkdir(name, NULL);
}

#endif /* _OPLUS_PROC_COMPAT_H */
