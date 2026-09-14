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

MODULE_INFO(scmversion, "g417ca7e0f38e-dirty");

#ifdef CONFIG_RETPOLINE
MODULE_INFO(retpoline, "Y");
#endif


static const struct modversion_info ____versions[]
__used __section("__versions") = {
	{ 0x58c6377, "for_each_kernel_tracepoint" },
	{ 0x92997ed8, "_printk" },
	{ 0x95e102ab, "tracepoint_probe_register" },
	{ 0x472cf3b, "register_kprobe" },
	{ 0xeb78b1ed, "unregister_kprobe" },
	{ 0xdbeeece6, "tracepoint_probe_unregister" },
	{ 0xc8063a7e, "tracepoint_srcu" },
	{ 0x5ee82342, "synchronize_srcu" },
	{ 0x6091797f, "synchronize_rcu" },
	{ 0xd969d6f4, "cancel_work_sync" },
	{ 0xe783e261, "sysfs_emit" },
	{ 0xe2d5255a, "strcmp" },
	{ 0x1348649e, "alt_cb_patch_nops" },
	{ 0x34db050b, "_raw_spin_lock_irqsave" },
	{ 0xd35cce70, "_raw_spin_unlock_irqrestore" },
	{ 0x2d3385d3, "system_wq" },
	{ 0x732ac580, "queue_work_on" },
	{ 0x8d522714, "__rcu_read_lock" },
	{ 0xd567d551, "init_task" },
	{ 0x2469810f, "__rcu_read_unlock" },
	{ 0x599fb41c, "kvmalloc_node" },
	{ 0x7aa1756e, "kvfree" },
	{ 0xc2c193d2, "__stack_chk_fail" },
	{ 0x656e4a6e, "snprintf" },
	{ 0x52d66a00, "filp_open" },
	{ 0xbd9f9017, "kernel_write" },
	{ 0x9523ac09, "filp_close" },
	{ 0xa3b1eb1d, "param_ops_int" },
	{ 0x91a089b, "param_ops_bool" },
	{ 0xea759d7f, "module_layout" },
};

MODULE_INFO(depends, "");

