#!/usr/bin/env bash
# TB710FU / SM8650Q：以零偏离配置重编 GKI 6.1.128（build 13606743 对应源码），
# 编完立刻跑 vendor ABI 门禁。已在真机源码树上验证：3033 个目标、5m28、0 报错、
# 门禁 0 处 CRC 不匹配。
#
# 前置：
#   $S/aosp/src              AOSP common android14-6.1 @ 5c2cea985a84
#   $S/toolchain/clang-r487747c   与 /proc/version 字符串逐字节一致的 Android Clang
#   $W/tmp/running-config    真机 /proc/config.gz 解出的配置（作为参考 .config）
#   $W/tmp/vendor_modules/modules  289 个 Lenovo vendor 模块
#   abi_symbollist.raw       源码树内，TRIM_UNUSED_KSYMS 的白名单
#   主机侧需要 bc、pahole（BTF 用）
set -o pipefail
S=${S:-$HOME/oplus-src}
W=${W:-/run/media/ACLaniakea/IXUNICS/pad}
OUT=${OUT:-$S/out-ref}
export PATH="$S/toolchain/clang-r487747c/bin:$PATH"
SRC=$S/aosp/src

# 主机工具修补：resolve_btfids 依赖的 tools/lib/bpf/libbpf.c 在新版编译器下
# 因 strchr() 返回 const char* 触发 -Werror。只影响主机工具，不进内核产物。
python3 - "$SRC/tools/lib/bpf/libbpf.c" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old, new = "next_path = strchr(s, ':');", "next_path = (char *)strchr(s, ':');"
if new not in s and old in s:
    open(p, "w").write(s.replace(old, new, 1)); print("libbpf.c: 已打主机工具补丁")
PY

mkdir -p "$OUT"
[ -f "$OUT/.config" ] || cp "$W/tmp/running-config" "$OUT/.config"
make -j"$(nproc)" O="$OUT" ARCH=arm64 LLVM=1 olddefconfig 2>&1 | tail -3
# 不做任何 config 削减：TRIM_UNUSED_KSYMS / DEBUG_INFO_BTF 必须保持开启，
# 关掉任何一个都会让导出符号集合和 CRC 与官方内核对不上。
grep -E "^CONFIG_(TRIM_UNUSED_KSYMS|UNUSED_KSYMS_WHITELIST|DEBUG_INFO_BTF)" "$OUT/.config"

cd "$SRC" || exit 1
time make -j"$(nproc)" O="$OUT" ARCH=arm64 LLVM=1 Image modules_prepare > "$S/ref.log" 2>&1
N=$(grep -cE '^  (CC|LD|AS)' "$S/ref.log")
echo "编译目标数 $N  报错 $(grep -cE 'error:' "$S/ref.log") 条"
if [ "$N" -lt 1000 ]; then
    echo "目标数不足四位：这不是一次全量编译，结论作废" >&2
    tail -25 "$S/ref.log" >&2
    exit 1
fi

python3 "$W/coloros-pad-fixes/kernel-compat/tools/verify_vendor_abi.py" \
    --modules-dir "$W/tmp/vendor_modules/modules" \
    --symvers "$OUT/vmlinux.symvers" --show 8
