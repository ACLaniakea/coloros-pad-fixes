// SPDX-License-Identifier: GPL-2.0-only
/*
 * Compatibility ABI for OPlus Horae on a non-OPlus vendor kernel.
 *
 * ColorOS Horae publishes three calculated shell temperatures through
 * /proc/shell-temp using the OPlus "<index> <milli-celsius>" protocol.  The
 * Lenovo vendor kernel has real skin thermal zones, but lacks this OPlus
 * aggregation endpoint.  Keep the original userspace protocol byte for byte.
 *
 * On top of that ABI the module publishes one real thermal zone, "shell-therm",
 * named skin-msm-therm-usr that reports the value Horae itself calculated.
 * That is the QTI convention for "the skin temperature userspace policy should
 * use", and this board's device tree does not provide it: both thermal-engine
 * and the thermal HAL look it up by that name, and thermal-engine was measured
 * to ignore any sensor name outside the QTI convention.  It invents nothing: the
 * number is whatever ColorOS wrote, and when Horae has not written for
 * SHELL_TEMP_STALE_MS the zone falls back to the raw board sensor, which is the
 * conservative pre-existing behaviour.  thermal-engine needs a *zone* rather
 * than a /proc file, and the ported vendor configuration calibrates its skin
 * ladders against a shell-like sensor; on this board skin-msm-therm sits next
 * to the SoC and runs up to 19 C above the shell, so those ladders fire while
 * the tablet is cold.  Pointing them at this zone restores the original intent.
 */

#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/jiffies.h>
#include <linux/slab.h>
#include <linux/moduleparam.h>
#include <linux/spinlock.h>
#include <linux/thermal.h>
#include <linux/uaccess.h>

#define OPLUS_SHELL_TEMP_PROC "shell-temp"
#define OPLUS_SHELL_TEMP_COUNT 3
#define OPLUS_SHELL_TEMP_INPUT_MAX 64

/* Name of the zone this module publishes, and the raw sensor it falls back to. */
#define SHELL_ZONE_NAME "skin-msm-therm-usr"
#define SHELL_FALLBACK_ZONE "skin-msm-therm"
#define SHELL_TEMP_STALE_MS 60000
/*
 * The zone must be polled: on this kernel a zone registered with a zero polling
 * delay reports the temperature captured when it was enabled and never updates,
 * which would silently disarm every ladder pointed at it.
 */
#define SHELL_ZONE_POLL_MS 1000

static bool shell_zone = true;
module_param(shell_zone, bool, 0444);
MODULE_PARM_DESC(shell_zone,
		 "publish the Horae shell temperature as a thermal zone");


static DEFINE_SPINLOCK(shell_temp_lock);
static int shell_temp[OPLUS_SHELL_TEMP_COUNT];
static unsigned long shell_temp_valid;
static struct proc_dir_entry *shell_temp_entry;
static unsigned long shell_temp_stamp;
static struct thermal_zone_device *shell_zone_dev;
static struct thermal_zone_device *shell_fallback_dev;

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
	shell_temp_stamp = jiffies;
	spin_unlock_irqrestore(&shell_temp_lock, flags);

	return count;
}


/*
 * Report the shell temperature Horae published.  If Horae is not running, has
 * not published yet, or has gone quiet, report the raw board sensor instead:
 * that is strictly more conservative than the shell value and matches how the
 * device behaved before this zone existed.
 */
static bool shell_temp_fresh(int *value)
{
	unsigned long flags;
	bool fresh = false;
	int result;

	spin_lock_irqsave(&shell_temp_lock, flags);
	result = shell_temp_max_locked();
	if (shell_temp_valid &&
	    time_before(jiffies,
			shell_temp_stamp + msecs_to_jiffies(SHELL_TEMP_STALE_MS)))
		fresh = true;
	spin_unlock_irqrestore(&shell_temp_lock, flags);

	*value = result;
	return fresh;
}

static int shell_zone_get_temp(struct thermal_zone_device *zone, int *temp)
{
	int value;

	if (shell_temp_fresh(&value)) {
		*temp = value;
		return 0;
	}

	if (!shell_fallback_dev)
		shell_fallback_dev =
			thermal_zone_get_zone_by_name(SHELL_FALLBACK_ZONE);
	if (IS_ERR_OR_NULL(shell_fallback_dev)) {
		shell_fallback_dev = NULL;
		return -EAGAIN;
	}

	return thermal_zone_get_temp(shell_fallback_dev, temp);
}

/*
 * Two writable passive trips with nothing bound to them.  The QTI thermal HAL
 * arms a zone's trips to be woken on threshold crossings; a zone without trips
 * would leave it unable to report severity changes at all.  Passive trips with
 * no cooling device attached make the kernel itself take no action, so these
 * exist purely as the HAL's notification channel.
 */
#define SHELL_ZONE_TRIPS 3

/*
 * Three writable passive trips, matching the count the board's own skin zone
 * carries.  thermal-engine and the thermal HAL both arm trips on the zone they
 * watch and each expects to own one; with only two trips, thermal-engine's
 * writes displaced the HAL's threshold and the framework's thermal status was
 * measured to stay at NONE even with the shell at 52 C.  Trip state lives with
 * the zone rather than in a shared array, because an earlier version that
 * shared one array between two zones let whichever wrote last disarm the other.
 */
struct shell_zone_state {
	int trip_temp[SHELL_ZONE_TRIPS];
	int trip_hyst[SHELL_ZONE_TRIPS];
};

static struct shell_zone_state *shell_zone_state_of(struct thermal_zone_device *zone)
{
	return zone ? zone->devdata : NULL;
}

static int shell_zone_get_trip_type(struct thermal_zone_device *zone, int trip,
				    enum thermal_trip_type *type)
{
	if (trip < 0 || trip >= SHELL_ZONE_TRIPS)
		return -EINVAL;
	*type = THERMAL_TRIP_PASSIVE;
	return 0;
}

static int shell_zone_get_trip_temp(struct thermal_zone_device *zone, int trip,
				    int *temp)
{
	struct shell_zone_state *state = shell_zone_state_of(zone);

	if (!state || trip < 0 || trip >= SHELL_ZONE_TRIPS)
		return -EINVAL;
	*temp = state->trip_temp[trip];
	return 0;
}

static int shell_zone_set_trip_temp(struct thermal_zone_device *zone, int trip,
				    int temp)
{
	struct shell_zone_state *state = shell_zone_state_of(zone);

	if (!state || trip < 0 || trip >= SHELL_ZONE_TRIPS)
		return -EINVAL;
	state->trip_temp[trip] = temp;
	return 0;
}

static int shell_zone_get_trip_hyst(struct thermal_zone_device *zone, int trip,
				    int *hyst)
{
	struct shell_zone_state *state = shell_zone_state_of(zone);

	if (!state || trip < 0 || trip >= SHELL_ZONE_TRIPS)
		return -EINVAL;
	*hyst = state->trip_hyst[trip];
	return 0;
}

static int shell_zone_set_trip_hyst(struct thermal_zone_device *zone, int trip,
				    int hyst)
{
	struct shell_zone_state *state = shell_zone_state_of(zone);

	if (!state || trip < 0 || trip >= SHELL_ZONE_TRIPS)
		return -EINVAL;
	state->trip_hyst[trip] = hyst;
	return 0;
}

static struct thermal_zone_device_ops shell_zone_ops = {
	.get_temp = shell_zone_get_temp,
	.get_trip_type = shell_zone_get_trip_type,
	.get_trip_temp = shell_zone_get_trip_temp,
	.set_trip_temp = shell_zone_set_trip_temp,
	.get_trip_hyst = shell_zone_get_trip_hyst,
	.set_trip_hyst = shell_zone_set_trip_hyst,
};

static struct thermal_zone_device *shell_zone_add(const char *name)
{
	struct thermal_zone_device *zone;
	struct shell_zone_state *state;
	int index;

	state = kzalloc(sizeof(*state), GFP_KERNEL);
	if (!state)
		return NULL;
	for (index = 0; index < SHELL_ZONE_TRIPS; index++) {
		state->trip_temp[index] = 95000;
		state->trip_hyst[index] = 1000;
	}

	zone = thermal_zone_device_register(name, SHELL_ZONE_TRIPS,
					    GENMASK(SHELL_ZONE_TRIPS - 1, 0),
					    state, &shell_zone_ops, NULL,
					    SHELL_ZONE_POLL_MS,
					    SHELL_ZONE_POLL_MS);
	if (IS_ERR(zone)) {
		pr_warn("thermal zone %s not registered: %ld\n", name,
			PTR_ERR(zone));
		kfree(state);
		return NULL;
	}
	if (thermal_zone_device_enable(zone))
		pr_warn("thermal zone %s could not be enabled\n", name);
	return zone;
}

static void shell_zone_register(void)
{
	if (!shell_zone)
		return;

	shell_zone_dev = shell_zone_add(SHELL_ZONE_NAME);
}

static void shell_zone_unregister(void)
{
	if (shell_zone_dev) {
		struct shell_zone_state *state = shell_zone_state_of(shell_zone_dev);

		thermal_zone_device_unregister(shell_zone_dev);
		shell_zone_dev = NULL;
		kfree(state);
	}
	shell_fallback_dev = NULL;
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

	shell_zone_register();

	pr_info("OPlus Horae /proc/%s compatibility ABI ready%s\n",
		OPLUS_SHELL_TEMP_PROC,
		shell_zone_dev ? ", thermal zone " SHELL_ZONE_NAME " published" : "");
	return 0;
}

static void __exit oplus_shell_temp_compat_exit(void)
{
	shell_zone_unregister();
	proc_remove(shell_temp_entry);
	shell_temp_entry = NULL;
}

module_init(oplus_shell_temp_compat_init);
module_exit(oplus_shell_temp_compat_exit);

MODULE_DESCRIPTION("OPlus Horae shell-temperature ABI compatibility");
MODULE_AUTHOR("ColorOS Pad Fixes");
MODULE_LICENSE("GPL v2");
