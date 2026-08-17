#!/system/bin/sh

MODDIR=${0%/*}
LOGFILE="$MODDIR/tuning.log"

# ============================================================================
# 联想平板 Pro GT - ColorOS 调优 · service 阶段
# 实际修复与作用：
#   1) 关键进程防换出：active（launcher/systemui/前台）/systemserver（system_server）
#      memcg swappiness 降到 20，长待机时不再被压入 zram；点亮后无需大量
#      swap-in，解锁与动画不再拖慢掉帧（实测 zram 中 system_server/launcher/
#      systemui 共被换出约 800MB，是长待机唤醒卡顿的直接来源）；
#   2) 后台 apps 保持较高 swappiness（60），回收压力自然落到可换出的后台应用；
#   3) min_free_kbytes 提到 32MB，给内核留足水位余量，避免关键 cgroup
#      低 swappiness 时触发同步 direct-reclaim / allocstall 停顿；
#   4) 全局 swappiness=60（ROM 基线 100 过激，会连系统进程一起换出）；
#   5) 常驻轻量守护每 30s 复校一次，防止 osense/其他服务回改。
# 仅适用于 SM8650Q / pineapple 平台。
# ============================================================================

soc=$(getprop ro.soc.model)
platform=$(getprop ro.board.platform)
case "$soc/$platform" in
    *SM8650Q*/*pineapple*) ;;
    *) echo "unsupported device: soc=$soc platform=$platform" >>"$LOGFILE"; exit 0;;
esac

# KernelSU service.sh 早期 PATH 可能缺 /system/bin，导致 getprop 找不到
export PATH="/sbin:/system/bin:/system/xbin:/vendor/bin:$PATH"

exec >>"$LOGFILE" 2>&1

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 8
echo "[$(date '+%F %T')] service start"

GLOBAL_SWAPPINESS=60
CRITICAL_SWAPPINESS=20
MIN_FREE_KB=32768

# 1) 全局 VM 基线
echo "$GLOBAL_SWAPPINESS" >/proc/sys/vm/swappiness 2>/dev/null
echo "$MIN_FREE_KB" >/proc/sys/vm/min_free_kbytes 2>/dev/null

# 2) apps memcg 树：active/systemserver 关键进程 20，其余后台 60
apply_memcg() {
    for m in /dev/memcg/apps/memory.swappiness /dev/memcg/apps/*/memory.swappiness; do
        [ -w "$m" ] || continue
        case "$m" in
            */active/memory.swappiness|*/systemserver/memory.swappiness)
                echo "$CRITICAL_SWAPPINESS" >"$m" 2>/dev/null
                ;;
            *)
                echo "$GLOBAL_SWAPPINESS" >"$m" 2>/dev/null
                ;;
        esac
    done
}
apply_memcg

# 3) 常驻守护：每 30s 复校全局 VM 与关键 memcg，防 osense 回改
if [ ! -f "$MODDIR/daemon.pid" ] || ! kill -0 "$(cat "$MODDIR/daemon.pid" 2>/dev/null)" 2>/dev/null; then
    (
        while :; do
            sleep 30
            [ "$(cat /proc/sys/vm/swappiness 2>/dev/null)" = "$GLOBAL_SWAPPINESS" ] || \
                echo "$GLOBAL_SWAPPINESS" >/proc/sys/vm/swappiness 2>/dev/null
            [ "$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null)" = "$MIN_FREE_KB" ] || \
                echo "$MIN_FREE_KB" >/proc/sys/vm/min_free_kbytes 2>/dev/null
            apply_memcg
        done
    ) &
    echo $! >"$MODDIR/daemon.pid"
fi

echo "[$(date '+%F %T')] service ready swappiness=$(cat /proc/sys/vm/swappiness 2>/dev/null) min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null) active=$(cat /dev/memcg/apps/active/memory.swappiness 2>/dev/null) systemserver=$(cat /dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null) inactive=$(cat /dev/memcg/apps/inactive/memory.swappiness 2>/dev/null)"
