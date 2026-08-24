// SPDX-License-Identifier: GPL-2.0-only
/*
 * Compatibility ABI for OPlus Horae on a non-OPlus vendor kernel.
 *
 * ColorOS Horae publishes three calculated shell temperatures through
 * /proc/shell-temp using the OPlus "<index> <milli-celsius>" protocol.  The
 * Lenovo vendor kernel has real skin thermal zones, but lacks this OPlus
 * aggregation endpoint.  Keep the original userspace protocol without
 * inventing a temperature or registering misleading thermal zones.
 */

#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/spinlock.h>
#include <linux/uaccess.h>

#define OPLUS_SHELL_TEMP_PROC "shell-temp"
#define OPLUS_SHELL_TEMP_COUNT 3
#define OPLUS_SHELL_TEMP_INPUT_MAX 64

static DEFINE_SPINLOCK(shell_temp_lock);
static int shell_temp[OPLUS_SHELL_TEMP_COUNT];
static unsigned long shell_temp_valid;
static struct proc_dir_entry *shell_temp_entry;

/* Exported ABI consumed by OPlus thermal modules when they are present. */
int get_current_shell_temp(void);
int get_current_temp(void);

static int shell_temp_max_locked(void)
{
	int index;
	int result = 0;
	bool have_value = false;

	for (index = 0; index < OPLUS_SHELL_TEMP_COUNT; index++) {
		if (!(shell_temp_valid & BIT(index)))
			continue;
		if (!have_value || shell_temp[index] > result)
			result = shell_temp[index];
		have_value = true;
	}

	/* The original OPlus node starts at zero before Horae publishes data. */
	return have_value ? result : 0;
}

static int shell_temp_max(void)
{
	unsigned long flags;
	int result;

	spin_lock_irqsave(&shell_temp_lock, flags);
	result = shell_temp_max_locked();
	spin_unlock_irqrestore(&shell_temp_lock, flags);
	return result;
}

int get_current_shell_temp(void)
{
	return shell_temp_max();
}
EXPORT_SYMBOL_GPL(get_current_shell_temp);

int get_current_temp(void)
{
	return shell_temp_max();
}
EXPORT_SYMBOL(get_current_temp);

static int shell_temp_show(struct seq_file *file, void *unused)
{
	seq_printf(file, "%d", shell_temp_max());
	return 0;
}

static int shell_temp_open(struct inode *inode, struct file *file)
{
	return single_open(file, shell_temp_show, NULL);
}

static ssize_t shell_temp_write(struct file *file, const char __user *buffer,
				size_t count, loff_t *position)
{
	char input[OPLUS_SHELL_TEMP_INPUT_MAX + 1];
	unsigned int index;
	unsigned long flags;
	int temperature;
	size_t length;

	if (!count)
		return 0;

	length = min_t(size_t, count, OPLUS_SHELL_TEMP_INPUT_MAX);
	if (copy_from_user(input, buffer, length))
		return -EFAULT;
	input[length] = '\0';

	if (sscanf(input, "%u %d", &index, &temperature) != 2)
		return -EINVAL;
	if (index >= OPLUS_SHELL_TEMP_COUNT)
		return -ERANGE;

	spin_lock_irqsave(&shell_temp_lock, flags);
	shell_temp[index] = temperature;
	shell_temp_valid |= BIT(index);
	spin_unlock_irqrestore(&shell_temp_lock, flags);

	return count;
}

static const struct proc_ops shell_temp_ops = {
	.proc_open = shell_temp_open,
	.proc_read = seq_read,
	.proc_write = shell_temp_write,
	.proc_lseek = seq_lseek,
	.proc_release = single_release,
};

static int __init oplus_shell_temp_compat_init(void)
{
	/* Match the ABI and mode of the official OPlus horae_shell_temp module. */
	shell_temp_entry = proc_create(OPLUS_SHELL_TEMP_PROC, 0666, NULL,
				       &shell_temp_ops);
	if (!shell_temp_entry)
		return -ENOMEM;

	pr_info("OPlus Horae /proc/%s compatibility ABI ready\n",
		OPLUS_SHELL_TEMP_PROC);
	return 0;
}

static void __exit oplus_shell_temp_compat_exit(void)
{
	proc_remove(shell_temp_entry);
	shell_temp_entry = NULL;
}

module_init(oplus_shell_temp_compat_init);
module_exit(oplus_shell_temp_compat_exit);

MODULE_DESCRIPTION("OPlus Horae shell-temperature ABI compatibility");
MODULE_AUTHOR("ColorOS Pad Fixes");
MODULE_LICENSE("GPL v2");
