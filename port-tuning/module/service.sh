#!/system/bin/sh

MODDIR=${0%/*}
LOGFILE="$MODDIR/tuning.log"

# ============================================================================
# 联想平板 Pro GT - ColorOS 调优 · service 阶段
# 实际修复与作用：
#   1) 恢复 ROM 基线 swappiness 继承：把 /dev/memcg 下 active 与
#      systemserver 的 memory.swappiness 重新对齐全局 VM 基线；
#   2) 早期版本（2.0.3-2.0.5）对 memcg 的局部 swappiness 覆盖会在本内核
#      把回收压力挤到其它 memcg，造成唤醒/解锁时的大规模 direct-reclaim
#      卡顿；本模块升级时一次性还原继承，之后不再写局部覆盖；
#   3) 保留全局 VM 值不变（swappiness/min_free_kbytes 等由 ROM 管理）。
# 仅适用于 SM8650Q / pineapple 平台。
# ============================================================================

soc=$(getprop ro.soc.model)
platform=$(getprop ro.board.platform)
case "$soc/$platform" in
    *SM8650Q*/*pineapple*) ;;
    *) echo "unsupported device: soc=$soc platform=$platform" >>"$LOGFILE"; exit 0;;
esac

exec >>"$LOGFILE" 2>&1
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 8
echo "[$(date '+%F %T')] service start"

# Keep the ROM-wide VM baseline unchanged. Versions 2.0.3-2.0.5 used local
# active/system_server swappiness overrides. On this kernel they displaced
# reclaim pressure into other memcgs and caused large direct-reclaim bursts
# during wake/unlock. Restore inheritance once for upgrades, then exit.
if [ -w /dev/memcg/apps/active/memory.swappiness ]; then
    cat /proc/sys/vm/swappiness >/dev/memcg/apps/active/memory.swappiness
fi

wait_count=0
while [ ! -w /dev/memcg/apps/systemserver/memory.swappiness ] &&
      [ "$wait_count" -lt 60 ]; do
    sleep 1
    wait_count=$((wait_count + 1))
done
if [ -w /dev/memcg/apps/systemserver/memory.swappiness ]; then
    cat /proc/sys/vm/swappiness >/dev/memcg/apps/systemserver/memory.swappiness
fi

echo "[$(date '+%F %T')] service ready inherited active_swappiness=$(cat /dev/memcg/apps/active/memory.swappiness 2>/dev/null) systemserver_swappiness=$(cat /dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null)"
