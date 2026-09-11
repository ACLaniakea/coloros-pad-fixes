#include <linux/module.h>
#define INCLUDE_VERMAGIC
#include <linux/build-salt.h>
#include <linux/elfnote-lto.h>
#include <linux/export-internal.h>
#include <linux/vermagic.h>
#include <linux/compiler.h>

BUILD_SALT;
BUILD_LTO_INFO;

MODULE_INFO(vermagic, VERMAGIC_STRING);
MODULE_INFO(name, KBUILD_MODNAME);

__visible struct module __this_module
__section(".gnu.linkonce.this_module") = {
	.name = KBUILD_MODNAME,
	.init = init_module,
#ifdef CONFIG_MODULE_UNLOAD
	.exit = cleanup_module,
#endif
	.arch = MODULE_ARCH_INIT,
};

MODULE_INFO(scmversion, "g5c2cea985a84");

#ifdef CONFIG_RETPOLINE
MODULE_INFO(retpoline, "Y");
#endif

SYMBOL_CRC(g_opt_enable, 0x3f00a121, "");
SYMBOL_CRC(d_oplus_locking, 0x349a21cf, "");
SYMBOL_CRC(kern_lstat_init, 0x5b398f94, "");
SYMBOL_CRC(kern_lstat_exit, 0xa5d5eccd, "");

static const struct modversion_info ____versions[]
__used __section("__versions") = {
	{ 0xc473e973, "of_find_node_by_name" },
	{ 0x94a3f9c7, "of_property_read_variable_u32_array" },
	{ 0x92997ed8, "_printk" },
	{ 0x7d68eebb, "param_ops_uint" },
	{ 0x7dbea13a, "__tracepoint_android_vh_alter_mutex_list_add" },
	{ 0x95e102ab, "tracepoint_probe_register" },
	{ 0x63ae9c7e, "__tracepoint_android_vh_mutex_wait_start" },
	{ 0xaf33e17b, "__tracepoint_android_vh_mutex_wait_finish" },
	{ 0xf5dc2012, "__tracepoint_android_vh_mutex_unlock_slowpath" },
	{ 0x6aae72bc, "test_task_ux" },
	{ 0x68f31cbd, "__list_add_valid" },
	{ 0x786a1bf0, "test_set_inherit_ux" },
	{ 0x54d9dbd3, "test_inherit_ux" },
	{ 0x54495472, "set_inherit_ux" },
	{ 0x8cbb24b5, "unset_inherit_ux" },
	{ 0xdbeeece6, "tracepoint_probe_unregister" },
	{ 0x91a089b, "param_ops_bool" },
	{ 0x77909e8e, "trace_event_buffer_reserve" },
	{ 0x816bb80d, "trace_event_buffer_commit" },
	{ 0x942c940f, "__trace_trigger_soft_disabled" },
	{ 0xc2c193d2, "__stack_chk_fail" },
	{ 0x2d2c902f, "perf_trace_buf_alloc" },
	{ 0xd1fcd174, "perf_trace_run_bpf_submit" },
	{ 0xc4b28304, "bpf_trace_run2" },
	{ 0x1f7600cb, "bpf_trace_run3" },
	{ 0x15ba50a6, "jiffies" },
	{ 0xb65a03d5, "__tracepoint_android_vh_alter_rwsem_list_add" },
	{ 0x41026d41, "__tracepoint_android_vh_rwsem_wake" },
	{ 0x6b369940, "__tracepoint_android_vh_rwsem_wake_finish" },
	{ 0xc2e8063f, "__tracepoint_android_vh_rwsem_read_wait_finish" },
	{ 0xa8c3dad3, "__tracepoint_android_vh_rwsem_write_wait_finish" },
	{ 0xf3c8d647, "trace_raw_output_prep" },
	{ 0xbfb5f86f, "trace_event_printf" },
	{ 0x7381287f, "trace_handle_return" },
	{ 0xa20d01ba, "__trace_bprintk" },
	{ 0x62e5727, "trace_event_reg" },
	{ 0xa7d84344, "trace_event_raw_init" },
	{ 0xaa946a68, "test_task_is_rt" },
	{ 0x244345e0, "task_rq_lock" },
	{ 0x7ff431ed, "update_rq_clock" },
	{ 0xb93cb4ae, "raw_spin_rq_unlock" },
	{ 0xd35cce70, "_raw_spin_unlock_irqrestore" },
	{ 0x56470118, "__warn_printk" },
	{ 0x76782dd0, "__tracepoint_android_vh_do_futex" },
	{ 0x196030ee, "__tracepoint_android_vh_futex_wait_start" },
	{ 0x49b606a8, "__tracepoint_android_vh_futex_wait_end" },
	{ 0xed380509, "__tracepoint_android_vh_alter_futex_plist_add" },
	{ 0xc26ab705, "__tracepoint_android_vh_futex_sleep_start" },
	{ 0xaadca4aa, "__tracepoint_android_vh_futex_wake_traverse_plist" },
	{ 0x5301beb4, "__tracepoint_android_vh_futex_wake_this" },
	{ 0x5bf93e96, "__tracepoint_android_vh_futex_wake_up_q_finish" },
	{ 0xf6af96b1, "get_ux_state_type" },
	{ 0x1348649e, "alt_cb_patch_nops" },
	{ 0x4b0a3f52, "gic_nonsecure_priorities" },
	{ 0x2be0c009, "__arch_copy_from_user" },
	{ 0x8d522714, "__rcu_read_lock" },
	{ 0x5cd583b1, "find_task_by_vpid" },
	{ 0x2469810f, "__rcu_read_unlock" },
	{ 0x79f7350b, "ta_task" },
	{ 0x950c847f, "__put_task_struct" },
	{ 0xb72c0b08, "fg_task" },
	{ 0xd91d8594, "bg_task" },
	{ 0x9a85eebb, "__arch_copy_to_user" },
	{ 0xdcb764ad, "memset" },
	{ 0x79e4c52b, "cpu_hwcaps" },
	{ 0x296695f, "refcount_warn_saturate" },
	{ 0x786d2762, "inc_inherit_ux_refs" },
	{ 0x63726885, "unset_inherit_ux_value" },
	{ 0x541c0506, "param_ops_long" },
	{ 0xa86a5262, "__tracepoint_android_rvh_rtmutex_force_update" },
	{ 0x7fbbd0bb, "android_rvh_probe_register" },
	{ 0x5494b8bf, "__tracepoint_android_vh_task_blocks_on_rtmutex" },
	{ 0xfbb21e2, "__tracepoint_android_vh_rtmutex_waiter_prio" },
	{ 0x1e39ba1c, "proc_mkdir" },
	{ 0x524767b, "proc_create" },
	{ 0xef8b7667, "remove_proc_entry" },
	{ 0x4adb51eb, "single_open" },
	{ 0x88db9f48, "__check_object_size" },
	{ 0x77bc13a0, "strim" },
	{ 0x8c8569cb, "kstrtoint" },
	{ 0x4be4820, "seq_printf" },
	{ 0xd963f308, "seq_read" },
	{ 0x11f8a681, "seq_lseek" },
	{ 0xe8f7183d, "single_release" },
	{ 0xf15b38a, "kmalloc_caches" },
	{ 0xb78e7543, "kmalloc_trace" },
	{ 0xd653b126, "sched_clock" },
	{ 0x6bd1aa56, "stack_trace_save" },
	{ 0xba8fbd64, "_raw_spin_lock" },
	{ 0xe1537255, "__list_del_entry_valid" },
	{ 0x37a0cba, "kfree" },
	{ 0xb5b54b34, "_raw_spin_unlock" },
	{ 0x65ad336b, "__tracepoint_android_vh_rwsem_read_wait_start" },
	{ 0xfe512d7a, "__tracepoint_android_vh_rwsem_write_wait_start" },
	{ 0x7ae3018a, "proc_create_data" },
	{ 0x656e4a6e, "snprintf" },
	{ 0x9ed12e20, "kmalloc_large" },
	{ 0xe769232e, "sprint_symbol_no_offset" },
	{ 0x98cf60b3, "strlen" },
	{ 0x5a921311, "strncmp" },
	{ 0xbcab6ee6, "sscanf" },
	{ 0x4829a47e, "memcpy" },
	{ 0x3c3ff9fd, "sprintf" },
	{ 0xea759d7f, "module_layout" },
};

MODULE_INFO(depends, "oplus_cpu_sched_sched_assist");

