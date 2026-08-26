#!/usr/bin/env python3
"""一加 mm 侧内核补丁：把 GKI 缺、而一加模块要的那几处补上。

用法： apply_oplus_mm.py <内核源码根>    （幂等）

补的内容：
1. `enum oplus_mm_ext_vh_type`（include/linux/mm.h）——
   一加用 android_vh_si_meminfo_adjust 这一个已有钩子，把第一个参数
   当成"问什么"的选择子来复用，枚举值就是选择子。hybridswap 和
   uxmem_opt 都引用它。照抄一加 common 内核的定义。
2. `android_vh_drain_all_pages_bypass`(include/trace/hooks/mm.h +
   mm/page_alloc.c)——GKI 5.15 有、6.1 上游删掉的钩子。
   直接回收失败后会 drain_all_pages() 给所有 CPU 发 IPI，
   一加的 pcppages_opt 就是用这个钩子把它跳过去。补回调用点，
   位置与 5.15 相同：__alloc_pages_direct_reclaim 里那次重试。
"""
import re, sys, os

def patch(path, mark, anchor, repl, what):
    s = open(path).read()
    if mark in s:
        print(f"  {os.path.basename(path)}: {what} 已存在"); return True
    if anchor not in s:
        print(f"  !! {os.path.basename(path)}: {what} 锚点未命中"); return False
    open(path, "w").write(s.replace(anchor, repl, 1))
    print(f"  {os.path.basename(path)}: {what} 已补")
    return True


def main(src):
    ok = True
    # 1. 枚举
    enum = """
/* 一加 mm 模块用 android_vh_si_meminfo_adjust 的第一个参数做选择子 */
enum oplus_mm_ext_vh_type {
	OPLUS_MM_VH_CURRENT_IS_UX = 0,
	OPLUS_MM_VH_FREE_ZRAM_IS_OK,
	OPLUS_MM_VH_CURRENT_IS_KEY,
};

#endif /* _LINUX_MM_H */"""
    ok &= patch(src + "/include/linux/mm.h", "OPLUS_MM_VH_CURRENT_IS_UX",
                "#endif /* _LINUX_MM_H */", enum, "oplus_mm_ext_vh_type 枚举")

    # 2a. 钩子声明
    hook = """DECLARE_HOOK(android_vh_drain_all_pages_bypass,
	TP_PROTO(gfp_t gfp_mask, unsigned int order, unsigned long alloc_flags,
		int migratetype, unsigned long did_some_progress, bool *bypass),
	TP_ARGS(gfp_mask, order, alloc_flags, migratetype, did_some_progress,
		bypass));

DECLARE_HOOK(android_vh_si_meminfo_adjust,"""
    ok &= patch(src + "/include/trace/hooks/mm.h", "android_vh_drain_all_pages_bypass",
                "DECLARE_HOOK(android_vh_si_meminfo_adjust,", hook, "drain_all_pages_bypass 声明")

    # 2b. 调用点
    anchor = """	if (!page && !drained) {
		unreserve_highatomic_pageblock(ac, false);
		drain_all_pages(NULL);
		drained = true;
		++retry_times;
		goto retry;
	}"""
    repl = """	if (!page && !drained) {
		bool bypass = false;

		trace_android_vh_drain_all_pages_bypass(gfp_mask, order,
			alloc_flags, ac->migratetype, *did_some_progress, &bypass);
		unreserve_highatomic_pageblock(ac, false);
		if (!bypass)
			drain_all_pages(NULL);
		drained = true;
		goto retry;
	}"""
    ok &= patch(src + "/mm/page_alloc.c", "trace_android_vh_drain_all_pages_bypass",
                anchor, repl, "drain_all_pages_bypass 调用点")

    # 2b2. 导出 tracepoint，否则模块链接时找不到
    exp = "EXPORT_TRACEPOINT_SYMBOL_GPL(android_vh_si_meminfo_adjust);"
    ok &= patch(src + "/drivers/android/vendor_hooks.c",
                "EXPORT_TRACEPOINT_SYMBOL_GPL(android_vh_drain_all_pages_bypass);",
                exp, exp + "\nEXPORT_TRACEPOINT_SYMBOL_GPL(android_vh_drain_all_pages_bypass);",
                "drain_all_pages_bypass 导出")

    # 2c. 白名单：照已有的 si_meminfo_adjust 两条复制
    wl = src + "/abi_symbollist.raw"
    lines = open(wl).read().split("\n")
    add = [l.replace("si_meminfo_adjust", "drain_all_pages_bypass")
           for l in lines if "si_meminfo_adjust" in l]
    add = [a for a in add if a and a not in lines]
    if add:
        open(wl, "w").write("\n".join(lines + add).strip("\n") + "\n")
        print("  abi_symbollist.raw: 新增", add)
    else:
        print("  abi_symbollist.raw: 已包含")
    return ok


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.exit(0 if main(sys.argv[1].rstrip("/")) else 1)
