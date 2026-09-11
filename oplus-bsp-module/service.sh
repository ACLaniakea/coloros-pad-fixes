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

# The tablet product script is an older branch and leaves zram_opt at 160/60.
# The reference phone's current osvelte profile resolves to 125/60.  Apply the
# same contextual policy once after the product script finishes; this is a
# one-shot boot action, not a resident daemon, and only runs when the genuine
# external zram_opt module has registered its control plane.
MEM_POLICY=/proc/oplus_mem/swappiness_para
MEM_DYNAMIC=/proc/oplus_mem/dynamic_swappiness
if [ -w "$MEM_POLICY" ]; then
    echo 'direct_swappiness=60' >"$MEM_POLICY" 2>/dev/null
    echo 'vm_swappiness=125' >"$MEM_POLICY" 2>/dev/null
    echo 'swapd_swappiness=125' >"$MEM_POLICY" 2>/dev/null
    [ -w "$MEM_DYNAMIC" ] && echo '125 0 125 0' >"$MEM_DYNAMIC" 2>/dev/null
    log_msg "zram_opt policy aligned to reference: vm=125 direct=60 swapd=125 dynamic=125/0/125/0"
fi

# ============================================================================
# 帧组的 SurfaceFlinger 提频/迁移加成：补回本机缺的两项（2026-09-12）
#
# /proc/oplus_frame_boost/stune_boost 是 frame_boost 的每帧组 boost 表。
# 实机对照（本机 vs PKX110，同一时刻各读一次）：
#
#   grp 1~4   两台完全相同
#   grp 5~9   本机  9 项：fps / min_threshold / min_obtain_view / min_timeout
#                        / ed_min_duration / ed_min_util / ed_max_duration
#                        / ed_max_util / ed_timeout
#             对照 11~12 项：以上 + sf_migr_gpu:30 + sf_freq_gpu:30
#                        （部分组还有 sf_freq_nongpu:30，两台对照机自己也不一致，
#                          属运行态而非配置，故不跟）
#
# 也就是说：本机的帧组能正确识别（/proc/oplus_frame_boost/info 里
# RenderThread + 应用主线程、SF COMPOSITION GROUP 都在），但应用帧组活跃时
# **不给 SurfaceFlinger 任何提频与迁移加成**。合成跟不上就直接表现为掉帧。
#
# 这两个值由 ColorOS 框架在运行时决定，两台设备的 /system /vendor /product
# 里都搜不到对应配置文件，所以只能在框架写完之后补写。本脚本在
# boot_completed 之后执行，晚于框架的初始化。
#
# 写入格式是从 ua_cpu_ioctl.ko 的 proc_stune_boost_write 实测出来的：
#     echo "<grp_id> <boost_type索引> <值>" > stune_boost
# 索引即读出来那一行的字段序号（0=migr 1=freq 2=fps … 9=sf_migr_gpu
# 10=sf_freq_gpu …）。写错会被直接拒绝，不会写坏别的项。
#
# 实测：五组全部写入成功，且经 20 秒滑动 + 一次应用切换后没有被框架改回 0。
# 撤销：写回 0（`echo "$g 9 0"`），或删掉本段。
# ============================================================================
FBG_STUNE=/proc/oplus_frame_boost/stune_boost
if [ -w "$FBG_STUNE" ]; then
    _n=0
    for _g in 5 6 7 8 9; do
        echo "$_g 9 30"  >"$FBG_STUNE" 2>/dev/null
        echo "$_g 10 30" >"$FBG_STUNE" 2>/dev/null
        _n=$((_n + 1))
    done
    log_msg "fbg stune: 已对 grp5-9 写入 sf_migr_gpu=30 sf_freq_gpu=30（$_n 组），对齐对照机"
else
    log_msg "fbg stune: $FBG_STUNE 不可写，跳过"
fi

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
