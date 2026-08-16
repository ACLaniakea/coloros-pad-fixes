#!/system/bin/sh

MODDIR=${0%/*}
LOGFILE="$MODDIR/tuning.log"

# ============================================================================
# 联想平板 Pro GT - ColorOS 调优 · service 阶段
# 实际修复与作用：
#   1) 降低全局 swappiness（100→60）并统一 apps memcg 树：减少后台匿名页
#      被激进换入 zram。长待机唤醒后解锁/动画卡顿的主因是大量 swap-in
#      （major fault）与 direct-reclaim；持续压缩换出也拖续航；
#   2) 适度提高 min_free_kbytes（11.5MB→24MB），给内核保留回收水位余量，
#      减少空闲内存跌破 min 时触发的同步 direct-reclaim / allocstall 停顿；
#   3) 所有 memcg 使用同一 swappiness，避免局部覆盖把回收压力挤到其它
#      cgroup（2.0.3-2.0.5 的教训）。
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

SWAPPINESS=60
MIN_FREE_KB=24576

# 1) 全局 VM 基线
echo "$SWAPPINESS" >/proc/sys/vm/swappiness 2>/dev/null
echo "$MIN_FREE_KB" >/proc/sys/vm/min_free_kbytes 2>/dev/null

# 2) apps memcg 树统一 swappiness（apps 根 + active/systemserver + 各 app）
for m in /dev/memcg/apps/memory.swappiness /dev/memcg/apps/*/memory.swappiness; do
    [ -w "$m" ] && echo "$SWAPPINESS" >"$m" 2>/dev/null
done

echo "[$(date '+%F %T')] service ready swappiness=$(cat /proc/sys/vm/swappiness 2>/dev/null) min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null) active=$(cat /dev/memcg/apps/active/memory.swappiness 2>/dev/null) systemserver=$(cat /dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null)"
