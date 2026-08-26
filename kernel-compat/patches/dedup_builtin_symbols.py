#!/usr/bin/env python3
"""把一加模块编进内核（built-in）时，解决重复符号。

模块各自是独立 .ko 时，同名的非 static 函数互不干扰；一旦编进 vmlinux，
它们和内核本体、以及彼此之间就会撞名。一加自己也是编成 .ko 的，所以源码里
本来就没处理这件事。

做法：凡是定义在 drivers/oplus/ 下的重复符号，就在**它所在的叶子目录内**
整体改名加前缀（引用都在同一模块内，所以目录内改名是自洽的）；
内核本体那一份原样不动。

用法： dedup_builtin_symbols.py <链接日志> <内核源码根>
"""
import re, sys, os, glob

def parse(log_path):
    """→ {symbol: [定义它的文件绝对路径, ...]}"""
    text = open(log_path, errors="ignore").read()
    out = {}
    cur = None
    for line in text.split("\n"):
        m = re.search(r"duplicate symbol: (\S+)", line)
        if m:
            cur = m.group(1); out.setdefault(cur, [])
            continue
        if cur:
            m = re.search(r">>> defined at \S+ \((/\S+?):\d+\)", line)
            if m:
                out[cur].append(m.group(1))
    return out


def prefix_for(path, src):
    rel = os.path.relpath(os.path.dirname(path), os.path.join(src, "drivers/oplus"))
    return "o_" + rel.replace("/", "_") + "_"


def rename_in_dir(d, sym, new):
    pat = re.compile(r"\b%s\b" % re.escape(sym))
    n = 0
    for f in glob.glob(d + "/*.c") + glob.glob(d + "/*.h"):
        s = open(f, encoding="utf-8", errors="surrogateescape").read()
        s2, k = pat.subn(new, s)
        if k:
            open(f, "w", encoding="utf-8", errors="surrogateescape").write(s2)
            n += k
    return n


def main(log_path, src):
    dups = parse(log_path)
    if not dups:
        print("  没有重复符号"); return 0
    oplus_root = os.path.join(src, "drivers/oplus")
    total = 0
    for sym, files in dups.items():
        dirs = {os.path.dirname(f) for f in files if f.startswith(oplus_root)}
        if not dirs:
            print(f"  !! {sym} 的定义都不在 drivers/oplus，跳过"); continue
        for d in sorted(dirs):
            new = prefix_for(d + "/x.c", src) + sym
            k = rename_in_dir(d, sym, new)
            total += k
            print(f"  {sym} -> {new}  ({os.path.relpath(d, src)}, {k} 处)")
    print(f"共改写 {total} 处")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2].rstrip("/")))
