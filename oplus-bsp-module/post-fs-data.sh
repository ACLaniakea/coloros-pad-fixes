#!/system/bin/sh
# ============================================================================
# OPlus BSP 内核模块加载器 —— TB710FU / 自建 GKI 6.1.128
#
# 设计原则：只负责"把 ko 尽早装上"，一行启用逻辑都不写。
#
# 因为 /product/bin/init.oplus.nandswap.sh 里有这么一句：
#     if [ -f /sys/block/zram0/hybridswap_core_enable ]; then
#         hybridswap_has_insmod=1
#     fi
# 只要 oplus_mm_hybridswap_zram.ko 在 `on property:sys.boot_completed=1`
# 之前装好，原厂脚本会自己走完整套 hybridswap 分支——建 swapfile、losetup、
# nandswap_tool 开 dio、mkswap/swapon、memcg 参数、写 hybridswap_loop_device
# 与 hybridswap_enable。这正是"回归 ColorOS 原逻辑"该有的样子。
#
# 不含 oplus_synchronize：mutex_list_add 谎报入链导致延迟 panic，
# 且 unregister_rtmutex_vendor_hooks() 是空函数体，装上就再也 rmmod 不掉。
# 详见 kernel-compat/一加模块移植进度.md。
# ============================================================================
MODDIR=${0%/*}
LOGFILE=/data/adb/oplus_bsp.log
KODIR="$MODDIR/ko"

log_msg() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOGFILE" 2>/dev/null
}

# 日志一律追加，绝不清空。
# 上一版这里是 `: >"$LOGFILE"`，结果开机失败那一次的模块日志被下一次开机
# 冲掉了，只能回头去啃 ECC 已经报损的 ramoops。失败现场只有一份，别自己删。
# 只在超过 512 KB 时滚动一次，避免无限增长。
if [ -f "$LOGFILE" ] && [ "$(wc -c <"$LOGFILE" 2>/dev/null || echo 0)" -gt 524288 ]; then
    mv -f "$LOGFILE" "$LOGFILE.1" 2>/dev/null
fi
log_msg ""
log_msg "======== boot @ $(cat /proc/uptime 2>/dev/null | cut -d. -f1)s uptime ========"
log_msg "=== oplus_bsp post-fs-data start (kernel $(uname -r)) ==="

# ---------------------------------------------------------------------------
# 保险丝：上次开机没走到 boot_completed 就自禁用
#
# service.sh 在 sys.boot_completed=1 之后删掉 .boot_pending。所以进到这里还
# 看得见它，只能说明上一次开机中途死了。装 22 个 BSP 模块是高风险动作，
# 宁可这次不生效，也不能让用户抱着砖去 9008。
# ---------------------------------------------------------------------------
if [ -f "$MODDIR/.boot_pending" ]; then
    log_msg "FUSE: previous boot never reached boot_completed; disabling this module"
    rm -f "$MODDIR/.boot_pending"
    touch "$MODDIR/disable"
    exit 0
fi
touch "$MODDIR/.boot_pending"
sync

# ---------------------------------------------------------------------------
# 摘掉移植包补的替代壳。正常情况下这里一个都不在（本模块 id 以 a 开头，跑在
# coloros_port_fix 之前），留着是为了手动 rerun 时也能干净重来。
# ---------------------------------------------------------------------------
for shim in oplus_sched_assist oplus_mm_compat; do
    if grep -q "^$shim " /proc/modules 2>/dev/null; then
        if rmmod "$shim" 2>>"$LOGFILE"; then
            log_msg "shim removed: $shim"
        else
            log_msg "WARN: could not rmmod shim $shim"
        fi
    fi
done

# ---------------------------------------------------------------------------
# 依赖拓扑序。顺序是实测出来的，不要随意调整：
# sched_assist 导出的符号被后面一大票 cpu_sched 模块依赖，oplus_ipc 要在
# 它之后，hybridswap 依赖 mm_osvelte / sched_info，exit_mm_optimize 依赖
# hybridswap。
# ---------------------------------------------------------------------------
MODULES="
oplus_cpu_sched_sched_assist
oplus_ipc
oplus_cpu_detection
oplus_cpu_sched_task_cpustats
oplus_mm_mm_osvelte
oplus_mm_dump_tasks_mem
oplus_mm_levelprotect
oplus_mm_memload_opt_mapped_protect
oplus_mm_sigkill_diagnosis
oplus_mm_process_reclaim
oplus_mm_async_reclaim_opt_pcppages_opt
oplus_mm_async_reclaim_opt_kshrink_slabd
oplus_cpu_sched_sched_info
oplus_mm_dynamic_readahead
oplus_cpu_sched_qos_sched
oplus_mm_uxmem_opt
oplus_cpu_sched_eas_opt
oplus_cpu_sched_frame_boost
oplus_cpu_sched_task_sched
oplus_cpu_waker_identify
oplus_mm_hybridswap_zram
oplus_mm_exit_mm_optimize
"

# ---------------------------------------------------------------------------
# 给 hybridswap 腾位置：卸掉标准 zram
#
# oplus_mm_hybridswap_zram 不改名、不改符号，冲突只在运行期全局资源上——
# 块设备名 zram / 主设备号、zram-control class、debugfs 目录、静态
# CPUHP_ZCOMP_PREPARE。标准 zram.ko 一从内存里出去，这些立刻全部释放，
# 于是共存补丁一个都不需要。
#
# zram.ko / zsmalloc.ko 在 /system_dlkm 下且**不在任何 modules.load 里**，
# 所以卸掉之后没有任何东西会把它装回来。
# ★ 绝不能连坐卸 zsmalloc：hybridswap 自己也要用它。
#
# 此刻卸掉之后到 boot_completed 之间系统没有 swap。已确认这不影响开机。
# ---------------------------------------------------------------------------
drop_stock_zram() {
    grep -q '^zram ' /proc/modules 2>/dev/null || {
        log_msg "stock zram not loaded; nothing to drop"; return 0; }

    if grep -q '^/dev/block/zram0 ' /proc/swaps 2>/dev/null; then
        used=$(awk '$1 == "/dev/block/zram0" { print $4 }' /proc/swaps 2>/dev/null)
        log_msg "stock zram0 is swapped on (${used:-?} KB used); swapping off"
        swapoff /dev/block/zram0 2>>"$LOGFILE" || {
            log_msg "ERROR: swapoff zram0 failed; leaving stock zram in place"
            return 1
        }
    fi
    [ -d /sys/block/zram0 ] && echo 1 >/sys/block/zram0/reset 2>/dev/null

    if rmmod zram 2>>"$LOGFILE"; then
        log_msg "stock zram.ko removed (zsmalloc kept)"
        return 0
    fi
    log_msg "ERROR: rmmod zram failed; hybridswap will not be able to load"
    return 1
}

# ---------------------------------------------------------------------------
loaded=0
failed=0
for m in $MODULES; do
    ko="$KODIR/$m.ko"
    if [ ! -f "$ko" ]; then
        log_msg "MISSING: $m.ko"
        failed=$((failed + 1))
        continue
    fi
    if grep -q "^$m " /proc/modules 2>/dev/null; then
        log_msg "already loaded: $m"
        loaded=$((loaded + 1))
        continue
    fi

    # hybridswap 之前先让标准 zram 退场
    if [ "$m" = oplus_mm_hybridswap_zram ]; then
        drop_stock_zram || { log_msg "SKIP: $m (stock zram still resident)"; failed=$((failed + 1)); continue; }
    fi

    # 不吞 stderr：ELF 损坏、符号缺失、版本不匹配的真实原因都在这里面
    if insmod "$ko" 2>>"$LOGFILE"; then
        log_msg "loaded: $m"
        loaded=$((loaded + 1))
    else
        log_msg "FAILED: $m (rc=$?)"
        failed=$((failed + 1))
    fi
done

log_msg "=== oplus_bsp: $loaded loaded, $failed failed ==="

if [ -f /sys/block/zram0/hybridswap_core_enable ]; then
    log_msg "hybridswap sysfs present; init.oplus.nandswap.sh will take over at boot_completed"
else
    log_msg "WARN: /sys/block/zram0/hybridswap_core_enable absent; stock script will fall back to plain zram"
fi
