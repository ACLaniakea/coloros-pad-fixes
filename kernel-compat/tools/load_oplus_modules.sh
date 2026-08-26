#!/usr/bin/env bash
# 在设备上按依赖顺序加载一加内核模块。**第一次刷机后不要自动加载**，
# 用这个脚本手工一个个上，每上一个看一眼 dmesg。
#
# 用法（PC 侧）： adb push 模块目录 /data/local/tmp/oplusko
#                adb shell su -c 'sh /data/local/tmp/oplusko/load_oplus_modules.sh [--dry]'
#
# 不做拓扑排序：反复扫描，能插的就插，直到一轮下来没有进展为止。
# 这比手写顺序稳——顺序错了只是多转一圈，不会漏。
set -u
DIR=$(dirname "$0")
DRY=0
[ "${1:-}" = "--dry" ] && DRY=1

# hybridswap 单独一轮验证：它替换标准 zram，会碰交换路径
SKIP="oplus_mm_hybridswap_zram.ko"

loaded=0; round=0
while :; do
    round=$((round + 1)); progress=0
    for ko in "$DIR"/*.ko; do
        name=$(basename "$ko")
        case " $SKIP " in *" $name "*) continue ;; esac
        mod=${name%.ko}
        lsmod | grep -q "^$mod " && continue
        if [ "$DRY" = 1 ]; then echo "  [dry] $name"; progress=1; continue; fi
        if insmod "$ko" 2>/tmp/ins.err; then
            echo "  [第 $round 轮] 已加载 $name"
            loaded=$((loaded + 1)); progress=1
        else
            err=$(cat /tmp/ins.err)
            case "$err" in
                *"Unknown symbol"*|*"Invalid parameters"*) : ;;   # 依赖还没就位，下一轮再试
                *) echo "  [第 $round 轮] $name 失败: $err" ;;
            esac
        fi
    done
    [ "$progress" = 1 ] || break
    [ "$round" -gt 8 ] && break
done

echo "共加载 $loaded 个"
echo "--- 还没上的 ---"
for ko in "$DIR"/*.ko; do
    mod=$(basename "$ko" .ko)
    lsmod | grep -q "^$mod " || echo "  $mod"
done
echo "--- dmesg 里的模块相关报错 ---"
dmesg | tail -60 | grep -iE "oplus|kernelsu|Unknown symbol|BUG|WARN" | tail -20
