#!/system/bin/sh

MODDIR=${0%/*}
LOGFILE="$MODDIR/tuning.log"

# ============================================================================
# 联想平板 Pro GT - ColorOS 调优 · service 阶段
# 实际修复与作用：
#   1) 关键进程防换出：active/systemserver memcg swappiness 降到 10，长待机
#      时不再被压入 zram，点亮后无需大量 swap-in；
#   2) 后台 apps swappiness 降到 40，配合 post-fs-data 关闭 osense 主动换出，
#      消除实测约 120MB/s 换出、76MB/s 换入的 zram 抖动；
#   3) min_free_kbytes 提到 64MB，给内核留足水位余量，避免 direct-reclaim /
#      allocstall 停顿；
#   4) 常驻轻量守护每 30s 复校一次，防止 osense/其他服务回改。
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

GLOBAL_SWAPPINESS=40
CRITICAL_SWAPPINESS=10
MIN_FREE_KB=65536

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
