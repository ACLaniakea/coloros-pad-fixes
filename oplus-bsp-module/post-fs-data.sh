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
#
# 备忘（2026-08-28）：sched_assist 的源码其实就在内核树 drivers/oplus/cpu/sched/
#   sched_assist/ 里（一个没接进 drivers/Makefile 的孤儿目录），实测可以编进
#   vmlinux 内建。但**不采纳**：设备上这个 ko 本来就装得好好的，内建版与预编 ko
#   撞名 411 个符号、二选一，等于用刷内核的风险换零功能增量；而且原厂 ColorOS
#   上它本来就是 vendor_dlkm 里的 ko，内建反而偏离原厂。保持 ko 形式。
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
ua_cpu_ioctl
oplus_bsp_game_opt
oplus_hans
oplus_freeze_process
kp_freeze_detect
oplus_bsp_lz4k
cpufreq_effiency
oplus_resctrl
"

# ---------------------------------------------------------------------------
# 末尾三个是自行补编的（sweep 脚本当年漏掉了它们）：
#
# ua_cpu_ioctl —— frame_boost 的 proc/ioctl 那半边。依赖
#   oplus_cpu_sched_frame_boost（在上面），所以必须排它后面。建
#   /proc/oplus_frame_boost/{ctrl,sys_ctrl,stune_boost,info,game_ed_info}
#   与 /proc/oplus_cpu/ua_ctrl，system_server 靠这些节点标记 UX 线程。
#   源码审计：零 vendor hook，init 失败路径干净，可 rmmod。低风险。
#
# oplus_bsp_game_opt —— /proc/game_opt/*，
#   /odm/bin/hw/vendor.oplus.hardware.gameopt-service 的对端。
#   ★ 单向门：注册 8 个 hook，其中 2 个 rvh（show_max_freq、
#   try_to_wake_up_success），而 game_ctrl_exit 一个都不注销 →
#   装上就永远不能 rmmod。try_to_wake_up_success 的另一槽被
#   oplus_cpu_waker_identify 占着，两槽正好用满，别再往这个钩子上加东西。
#   2026-08-27 手动 insmod 实测通过（25 个 proc 节点、dmesg 干净）。
#
# oplus_hans —— ColorOS 后台冻结子系统的内核侧（2026-08-28 补编）。
#   源码在 modules/vendor/oplus/kernel/hans/，自带 Kbuild，模块名是
#   oplus_hans 而不是 oplus_bsp_hans。注册 genl family "oplus_hans"，
#   /system_ext/bin/hans 靠它建通道。
#
#   ★ 缺它的后果比看上去严重得多：hans 守护进程 genl_get_family_id 失败
#   → 自己退出 → system_server 的 OplusHansManager 收到 MSG_HANS_DISABLED
#   → 整套后台冻结关闭 → 后台应用一个不冻、全在跑 → swapd 把它们换出去、
#   它们立刻踩回来 → 静置态 100% refault 颠簸（pswpin 733/s，熄屏更是
#   6219/s），这是卡顿的主因。详见 project_hans_freezer_missing 记录。
#
#   风险低：五个钩子全是普通 vh（binder_preset/trans/reply/
#   alloc_new_buf_locked、do_send_sig_info），init 失败会自己回滚，
#   没有 rvh，可 rmmod。无依赖，位置随意。
#
# ---- 2026-08-28 第二批：从 53 个自编 ko 里筛出来的 5 个 ----
# 筛选过程见 task #49。全部单独 insmod 实测通过、且 rmmod 得掉（可回退）。
# 各自 20~32 KB，都不建顶层 /proc 节点，无依赖，顺序随意。
#
# oplus_freeze_process —— 冻结钩子。dmesg 报
#   "[freeze_process_hook] module init successfully!"。冲的是断点二：
#   HANS 已经算出冻结名单（enter FF, frzUids:[...]）但没有一个进程真被冻。
#   这一批里唯一有明确目标的，其余四个是顺带。
# kp_freeze_detect —— 冻结相关的内核异常检测，配合上面那个。
# oplus_bsp_lz4k —— lz4k 压缩算法。zram comp_algorithm 列表里已经列着 lz4k
#   但当前选的是 [lz4]；装上它才真正可选。**暂不切算法**，先备着。
# cpufreq_effiency —— "cpufreq bouncing" 抑制，5 个模块参数，全用默认。
# oplus_resctrl —— cache/带宽分区。
#
# 同批被否掉的，别再试：
#   oplus_bsp_healthinfo      撞 oplus_cpu_sched_sched_info 的 ohm_get_cur_cpuload
#   oplus_bsp_binder_strategy 撞 oplus_ipc 的 oblist_dequeue_topapp_change
#   oplus_procs_load          init_module 里 Out of memory + 内核 oops，危险
#   crypto_zstdn              本内核没编 crypto_register_scomp
# ---------------------------------------------------------------------------

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
