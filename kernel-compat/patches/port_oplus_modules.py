#!/usr/bin/env python3
"""把一加 5.10 时代的内核模块源码改到 AOSP common 6.1 的 API 上。

注意：android_oem_data* 千万不要改写成 android_vendor_data*。
本内核 CONFIG_ANDROID_VENDOR_OEM_DATA=y，两组数组同时存在，
高通 WALT 占的是 vendor（rq 里只有 1 个 u64），一加占的是 oem（rq 里 16 个）。
改写会让 oplus_rq 挤进高通的槽位——既编不过，编过了也是内存踩踏。

只做机械的、可解释的替换；不做任何"猜一个能编过的写法"。
用法： port_oplus_modules.py <目录> [<目录> ...]   （原地改，幂等）
"""
import re, sys, os

# (说明, 正则, 替换)
SUBS = [
    # 5.14: task_struct.state -> __state（volatile 拆分）
    ("task->state", re.compile(r"\b(p|t|tsk|task|curr|cur|owner|waiter_task|target|next|prev)->state\b"), r"\1->__state"),
    # 6.2 的改名在 AOSP 6.1 上已经落地
    ("task_running()", re.compile(r"\btask_running\("), "task_on_cpu("),
    # 5.16: task_struct.cpu 移除，改用 task_cpu()
    ("task->cpu", re.compile(r"\b(p|tsk|task)->cpu\b"), r"task_cpu(\1)"),
    # 6.1 的 VMA 改用 maple tree，mm->mmap / vma->vm_next 都没了
    ("mm->mmap 遍历", re.compile(r"for \(vma = mm->mmap; vma; vma = vma->vm_next\) \{"),
     "VMA_ITERATOR(vmi, mm, 0);\n\tfor_each_vma(vmi, vma) {"),
    # 5.16 起 page_referenced 系列钩子改传 folio
    ("page_referenced 钩子改 folio",
     re.compile(r"should_skip_page_referenced\(void \*data, struct page \*page, unsigned long nr_to_scan, int lru, bool \*bypass\)\n\{"),
     "should_skip_page_referenced(void *data, struct folio *folio, unsigned long nr_to_scan, int lru, bool *bypass)\n{\n\tstruct page *page = &folio->page;"),
]


def port_file(path):
    s = open(path, encoding="utf-8", errors="surrogateescape").read()
    orig, notes = s, []
    for name, pat, rep in SUBS:
        s, n = pat.subn(rep, s)
        if n:
            notes.append(f"{name}×{n}")
    if s != orig:
        open(path, "w", encoding="utf-8", errors="surrogateescape").write(s)
        print(f"  {os.path.basename(path)}: {', '.join(notes)}")
    return s != orig


def import_ns(path):
    """si_swapinfo 在 GKI 里属于 MINIDUMP 命名空间，模块要显式 import。"""
    s = open(path, encoding="utf-8", errors="surrogateescape").read()
    if "si_swapinfo(" not in s or "MODULE_IMPORT_NS(MINIDUMP)" in s:
        return False
    if "#include <linux/module.h>" not in s:
        s = "#include <linux/module.h>\n" + s
    s = s.rstrip("\n") + "\n\nMODULE_IMPORT_NS(MINIDUMP);\n"
    open(path, "w", encoding="utf-8", errors="surrogateescape").write(s)
    print(f"  {os.path.basename(path)}: 已加 MODULE_IMPORT_NS(MINIDUMP)")
    return True


def port_dir(d):
    hits = 0
    for f in sorted(os.listdir(d)):
        if f.endswith((".c", ".h")):
            hits += port_file(os.path.join(d, f))
        if f.endswith(".c"):
            hits += import_ns(os.path.join(d, f))
    print(f"== {os.path.basename(d)}: {hits} 个文件改动")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for d in sys.argv[1:]:
        port_dir(d.rstrip("/"))
