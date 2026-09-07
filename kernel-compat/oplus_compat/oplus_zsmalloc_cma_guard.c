// SPDX-License-Identifier: GPL-2.0-only
/*
 * Remove __GFP_CMA only at the zsmalloc allocation boundary.
 *
 * The port's OPlus HybridSwap module is built from the older SM8650 source and
 * still passes __GFP_CMA to zs_malloc().  The newer OPlus implementation does
 * not do that.  Keeping the filter as a tiny, independently unloadable kprobe
 * lets us validate the behavior without replacing zram, zsmalloc, or the
 * running HybridSwap module.
 */

#include <linux/gfp.h>
#include <linux/kprobes.h>
#include <linux/module.h>

static atomic_t cma_filtered;

static int zsmalloc_cma_pre_handler(struct kprobe *probe, struct pt_regs *regs)
{
	gfp_t flags = (gfp_t)regs->regs[2];

	if (!(flags & __GFP_CMA))
		return 0;

	regs->regs[2] = (unsigned long)(flags & ~__GFP_CMA);
	if (atomic_inc_return(&cma_filtered) == 1)
		pr_info("first __GFP_CMA request filtered at zs_malloc\n");
	return 0;
}

static struct kprobe zsmalloc_cma_probe = {
	.symbol_name = "zs_malloc",
	.pre_handler = zsmalloc_cma_pre_handler,
};

static int __init zsmalloc_cma_guard_init(void)
{
	int ret;

	ret = register_kprobe(&zsmalloc_cma_probe);
	if (ret) {
		pr_err("register zs_malloc kprobe failed: %d\n", ret);
		return ret;
	}

	pr_info("loaded; __GFP_CMA is filtered only for zs_malloc\n");
	return 0;
}

static void __exit zsmalloc_cma_guard_exit(void)
{
	unregister_kprobe(&zsmalloc_cma_probe);
	pr_info("unloaded; filtered requests=%d\n",
		atomic_read(&cma_filtered));
}

module_init(zsmalloc_cma_guard_init);
module_exit(zsmalloc_cma_guard_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("OPlus zsmalloc CMA boundary guard for the SM8650Q port");
