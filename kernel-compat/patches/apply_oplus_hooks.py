#!/usr/bin/env python3
"""把一加内核里 GKI 没有的 vendor hook 补进 AOSP common 6.1，并修 5.10 遗留 API。

已验证：打完这两组补丁后
  * vendor ABI 门禁仍是 0 处 CRC 不匹配（289 个 Lenovo 模块照常可加载）；
  * 一加 frame_boost 五个源文件干净编出 .ko，无未定义符号。

用法： apply_oplus_hooks.py <内核源码根> [模块源码目录]
不带模块目录时只打内核补丁。脚本幂等，可重复执行。
"""
import sys, os

def patch(path, checks):
    """checks: [(已存在标记, 锚点, 替换文本)]，锚点必须唯一命中。"""
    s = open(path).read(); orig = s
    for mark, anchor, repl in checks:
        if mark in s:
            print(f"  {os.path.basename(path)}: {mark[:48]}… 已存在"); continue
        if anchor not in s:
            print(f"  !! {os.path.basename(path)}: 锚点未命中，未修改"); return False
        s = s.replace(anchor, repl, 1)
        print(f"  {os.path.basename(path)}: 已打补丁")
    if s != orig:
        open(path, "w").write(s)
    return True


def patch_kernel(src):
    ok = True
    # 1. android_vh_binder_proc_transaction_end
    #    GKI 只有 _entry / _finish，一加的 frame_boost 与 sched_assist 都注册 _end。
    hook = (
        "DECLARE_HOOK(android_vh_binder_proc_transaction_end,\n"
        "\tTP_PROTO(struct task_struct *caller_task, struct task_struct *binder_proc_task,\n"
        "\t\tstruct task_struct *binder_th_task, unsigned int code,\n"
        "\t\tbool pending_async, bool sync),\n"
        "\tTP_ARGS(caller_task, binder_proc_task, binder_th_task, code,\n"
        "\t\tpending_async, sync));\n")
    anchor = "DECLARE_HOOK(android_vh_binder_looper_state_registered,"
    ok &= patch(src + "/include/trace/hooks/binder.h",
                [("android_vh_binder_proc_transaction_end", anchor, hook + anchor)])

    call_anchor = ("\ttrace_android_vh_binder_proc_transaction_finish(proc, t,\n"
                   "\t\tthread ? thread->task : NULL, pending_async, !oneway);\n")
    call_add = ("\ttrace_android_vh_binder_proc_transaction_end(current, proc->tsk,\n"
                "\t\tthread ? thread->task : NULL, t->code, pending_async, !oneway);\n")
    ok &= patch(src + "/drivers/android/binder.c",
                [("trace_android_vh_binder_proc_transaction_end",
                  call_anchor, call_anchor + call_add)])

    exp = "EXPORT_TRACEPOINT_SYMBOL_GPL(android_vh_binder_proc_transaction_finish);"
    ok &= patch(src + "/drivers/android/vendor_hooks.c",
                [("EXPORT_TRACEPOINT_SYMBOL_GPL(android_vh_binder_proc_transaction_end);",
                  exp, exp + "\nEXPORT_TRACEPOINT_SYMBOL_GPL(android_vh_binder_proc_transaction_end);")])

    # 2. struct binder_transaction 补一格 OEM 数据
    #    一加的 ipc 模块把 struct oplus_binder_transaction（8 字节）存在
    #    t->android_oem_data1 里；GKI 的 binder_transaction 只有 vendor 数据。
    #    已确认 289 个 Lenovo vendor 模块无一引用 binder 符号，加字段不影响它们。
    bi = src + "/drivers/android/binder_internal.h"
    s2 = open(bi).read()
    if "android_oem_data1" in s2 or "ANDROID_OEM_DATA_ARRAY" in s2:
        print("  binder_internal.h: 已存在")
    else:
        import re as _re
        m = _re.search(r"(struct binder_transaction \{.*?\n)\};", s2, _re.S)
        if not m:
            print("  !! binder_internal.h: 找不到 struct binder_transaction"); ok = False
        else:
            body = m.group(0)
            new_body = body[:-2] + "\tANDROID_OEM_DATA_ARRAY(1, 1);\n};"
            s2 = s2.replace(body, new_body, 1)
            if "#include <linux/android_vendor.h>" not in s2:
                s2 = s2.replace("#include <linux/list.h>",
                                "#include <linux/list.h>\n#include <linux/android_vendor.h>", 1)
            open(bi, "w").write(s2)
            print("  binder_internal.h: struct binder_transaction 已加 ANDROID_OEM_DATA_ARRAY(1, 1)")

    # 2. TRIM_UNUSED_KSYMS 白名单：不加这两条，符号会被裁掉，模块链接时未定义。
    wl = src + "/abi_symbollist.raw"
    lines = open(wl).read().split("\n")
    add = [l.replace("binder_proc_transaction_finish", "binder_proc_transaction_end")
           for l in lines if "binder_proc_transaction_finish" in l]
    add = [a for a in add if a and a not in lines]
    if add:
        open(wl, "w").write("\n".join(lines + add).strip("\n") + "\n")
        print("  abi_symbollist.raw: 新增", add)
    else:
        print("  abi_symbollist.raw: 已包含")
    return ok


def patch_modules(mod):
    """一加 frame_boost 停留在 5.10 API，三处漂移。"""
    p = mod + "/frame_group.c"
    if not os.path.exists(p):
        print("  跳过模块补丁：找不到 frame_group.c"); return True
    s = open(p).read(); orig = s
    # account_group_exec_runtime 在 include/linux/sched/cputime.h，
    # 单独包含 kernel/sched/sched.h 时不会被带进来。
    if "linux/sched/cputime.h" not in s:
        s = s.replace("#include <kernel/sched/sched.h>",
                      "#include <linux/sched/cputime.h>\n#include <kernel/sched/sched.h>")
    s = s.replace("task_running(rq, p)", "task_on_cpu(rq, p)")      # 6.1 改名
    s = s.replace("p->state == TASK_RUNNING", "p->__state == TASK_RUNNING")  # 5.14 改名
    if s != orig:
        open(p, "w").write(s); print("  frame_group.c: 已打补丁")
    else:
        print("  frame_group.c: 已是 6.1 API")
    return True


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    print("=== 内核补丁 ===")
    good = patch_kernel(sys.argv[1].rstrip("/"))
    if len(sys.argv) > 2:
        print("=== 模块补丁 ===")
        good &= patch_modules(sys.argv[2].rstrip("/"))
    sys.exit(0 if good else 1)
