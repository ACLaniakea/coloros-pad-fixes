#!/system/bin/sh

MODDIR=${0%/*}

# ============================================================================
# 联想平板 Pro GT - ColorOS 调优 · action（管理器按钮）诊断
# 仅打印当前 VM 与 memcg swappiness 状态及最近日志，不做任何写入。
# ============================================================================

soc=$(getprop ro.soc.model)
platform=$(getprop ro.board.platform)
case "$soc/$platform" in
    *SM8650Q*/*pineapple*) ;;
    *) echo "Unsupported device: soc=$soc platform=$platform"; exit 0;;
esac
echo "Global VM values (not managed):"
echo "  swappiness=$(cat /proc/sys/vm/swappiness)"
echo "  min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes)"
echo "Active UI cgroup swappiness=$(cat /dev/memcg/apps/active/memory.swappiness 2>/dev/null)"
echo "system_server cgroup swappiness=$(cat /dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null)"
tail -40 "$MODDIR/tuning.log" 2>/dev/null
