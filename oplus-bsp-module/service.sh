#!/system/bin/sh
# ============================================================================
# 保险丝的另一半 + 事后验收记录
#
# 走到 boot_completed 就把 .boot_pending 摘掉，告诉下一次开机"上次是好的"。
# 随后等原厂 init.oplus.nandswap.sh 跑完，把最终状态记进日志，方便事后复盘。
# ============================================================================
MODDIR=${0%/*}
LOGFILE=/data/adb/oplus_bsp.log

log_msg() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOGFILE" 2>/dev/null
}

# 等开机完成。给到 5 分钟——这台机器冷启动本来就慢，
# 超时判失败反而是过去踩过的坑。
i=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$i" -lt 300 ]; do
    sleep 1
    i=$((i + 1))
done

if [ "$(getprop sys.boot_completed)" != 1 ]; then
    log_msg "WARN: boot_completed not seen after ${i}s; leaving fuse armed"
    exit 0
fi

rm -f "$MODDIR/.boot_pending"
sync
log_msg "boot_completed after ${i}s; fuse disarmed"

# 原厂脚本由 boot_completed 触发，给它时间跑完（建 swapfile + losetup 较慢）
sleep 45

Z=/sys/block/zram0
log_msg "--- post-boot state ---"
log_msg "modules: $(grep -c . /proc/modules) loaded; oplus_* = $(grep -c '^oplus_' /proc/modules)"
log_msg "swaps: $(awk 'NR>1 { printf "%s(%s/%s KB) ", $1, $4, $3 }' /proc/swaps 2>/dev/null)"
if [ -f "$Z/hybridswap_enable" ]; then
    log_msg "hybridswap_enable: $(cat "$Z/hybridswap_enable" 2>/dev/null)"
    log_msg "disksize: $(cat "$Z/disksize" 2>/dev/null)  loop: $(cat "$Z/hybridswap_loop_device" 2>/dev/null)"
    log_msg "meminfo: $(tr '\n' ' ' <"$Z/hybridswap_meminfo" 2>/dev/null)"
    log_msg "swapd_pid: $(cat /dev/memcg/memory.swapd_pid 2>/dev/null)"
else
    log_msg "hybridswap sysfs absent"
fi
log_msg "nandswap props: init=$(getprop sys.oplus.nandswap.init) app_memcg=$(getprop persist.sys.oplus.hybridswap_app_memcg) swapsize=$(getprop persist.sys.oplus.nandswap.swapsize.curr)"
log_msg "--- end ---"
