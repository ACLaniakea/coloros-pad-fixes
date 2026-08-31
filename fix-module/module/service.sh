#!/system/bin/sh

MODDIR=${0%/*}


. "$MODDIR/common.sh"

# ============================================================================
# 联想平板 Pro GT - ColorOS 基础修复 · service（开机完成后）阶段
# 实际修复与作用：
#   1) 重载移植 ROM 上时序不对的 source-device 性能 HAL（perf2-hal、
#      vendor.perfservice）——是 stop+start 重载，不是停用，两者必须保持
#      running，否则 AIDL 调用会失败并打满 system_server 的 binder 线程；
#   2) 重载 thermal-engine，让 CPU 策略在模块挂载后生效；
#   3) 把每个 CPU policy 的 scaling_max_freq 一次性恢复为内核硬件上限
#      （充电/CPU 热限频由 thermal-engine_battery_0/2.conf 覆盖策略接管，
#      不再使用常驻守护脚本）；
#   4) 保持 Tango 32 位 zygote 停止，启用 horae，避免移植运行时不兼容；
#   5) 永久关闭本机不兼容的小布 BWV 唤醒，保留 SpeechAssist 的非唤醒能力；
#   6) 开启电池健康入口（数据由 LSPosed BatteryHealthBridge 从 sysfs 桥接）；
#   7) 允许 Dolby Bridge 后台运行，避免原厂控制页仍在前台时服务被 app-idle
#      回收，导致 UI 仅写入设置但没有实时下发 DAP；
#   8) 清理已合并的旧 AON 独立包；启动 horae/gameopt；
#   9) 8GB 机型把首次解锁后的 cached 进程保护期归零；
#  10) 按需执行应用建议协议修复与序列号补齐。
# ============================================================================

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
log_msg "late service start"

if ! is_supported_device; then
    log_msg "unsupported device; skipped Lenovo Pad Pro GT service fixes"
    exit 0
fi

# Reassert once after Android property services are available.
#
# ★ 这一条**不是**内核缺口清理的对象，是永久保留项。HMBIRD 是一加基于
#   sched_ext/scx 的调度框架，需要 hmbird_sched 系列 ko 与内核的
#   CONFIG_SCHED_CLASS_EXT；它跟我们已加载的 oplus_cpu_sched_* 完全是两回事，
#   后者只是一组 restricted vendor hook。实机确认 /sys/kernel/hmbird*、
#   /proc/hmbird*、/sys/kernel/sched_ext* 一个都不存在，lsmod 里也没有
#   任何 hmbird 模块。与 oplus_bsp_healthinfo 同等处理：缺就让它缺，
#   把管理器关掉，免得它反复重试并回落到杀缓存。这不是常驻监视器。
#
# persist.sys.oplus.nandswap used to be pinned false here too. It is not a
# switch but a mirror of Settings.Secure.customize_ram_swap_state, so pinning
# it only desynchronised the UI from the zram that was running anyway.
setprop sys.oplus.hmbird.manager.enable 0

# AOSP/ColorOS protects excessive cached processes for ten minutes after the
# first user unlock. The 12 GB source phone can absorb that burst, but on the
# 8 GB tablet it keeps the whole CE restore set resident while kswapd and AMS
# compete for roughly a minute. Newer AOSP defaults this grace period to zero.
# Apply that upstream behavior only to <=9 GB variants; 12 GB devices retain
# the stock ten-minute cache warmth and normal Android process semantics.
#
# 这一段只剩 cached 进程保护期这一件事。原先与它绑在一起的整套 VM 调参
# （swappiness / min_free_kbytes / watermark_scale_factor / KGSL 回收批量）
# 已随 post-fs-data 那边一起撤销，交还原厂与 OSense。
ram_kb=$(awk '/MemTotal:/{print $2; exit}' /proc/meminfo 2>/dev/null)
case "$ram_kb" in ''|*[!0-9]*) ram_kb=0 ;; esac
if [ "$ram_kb" -gt 0 ] && [ "$ram_kb" -le 9437184 ]; then
    cache_grace=$(device_config get activity_manager \
        no_kill_cached_processes_post_boot_completed_duration_millis 2>/dev/null)
    if [ "$cache_grace" != 0 ]; then
        device_config put activity_manager \
            no_kill_cached_processes_post_boot_completed_duration_millis 0 \
            >/dev/null 2>&1
    fi
    log_msg "8GB cache trim policy active: post-unlock grace=0ms"
else
    log_msg "12GB-class cache trim policy preserved"
fi

# KernelSU can replace the stable LSPosed payload without updating the ordinary
# PackageManager package.  That leaves running processes on an older Hook APK
# even though the module directory contains newer code.  Reconcile versionCode
# before pinning the LSPosed path; only an upgrade causes a package update.
HOOK_PACKAGE=com.aclaniakea.colorosostatsguard
HOOK_EXPECTED_FILE="$MODDIR/hook/versionCode"
HOOK_EXPECTED=$(tr -dc '0-9' <"$HOOK_EXPECTED_FILE" 2>/dev/null)
HOOK_INSTALLED=$(dumpsys package "$HOOK_PACKAGE" 2>/dev/null | \
    sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)
case "$HOOK_EXPECTED$HOOK_INSTALLED" in *[!0-9]*|'') HOOK_EXPECTED=0; HOOK_INSTALLED=0 ;; esac
if [ "$HOOK_EXPECTED" -gt "$HOOK_INSTALLED" ] 2>/dev/null && \
        [ -f "$MODDIR/hook/BaseFix-Hook.apk" ]; then
    # `cmd package install` is rejected by this port's package binder when
    # called from a KernelSU service.  The platform `pm` frontend uses the
    # accepted install path and was verified on-device for a Hook upgrade.
    if pm install -r "$MODDIR/hook/BaseFix-Hook.apk" >>"$LOGFILE" 2>&1; then
        log_msg "Hook APK upgraded $HOOK_INSTALLED -> $HOOK_EXPECTED"
        # Processes already spawned with the old dex must not keep stale UI
        # hooks after the package update.  These two are safe to restart only
        # on an actual Hook upgrade.
        am force-stop --user 0 com.android.settings >/dev/null 2>&1
        am force-stop --user 0 com.oplus.ovoicemanager.wakeup >/dev/null 2>&1
    else
        log_msg "ERROR: Hook APK upgrade $HOOK_INSTALLED -> $HOOK_EXPECTED failed"
    fi
fi

# PackageManager may rewrite LSPosed's module path after an APK update. Pin it
# again once Android is up so the following cold boot starts from the stable
# module copy, even before post-fs-data performs its own early check.
if [ -f "$MODDIR/bin/lsposed-path-sync.jar" ] && \
        [ -f "$MODDIR/hook/BaseFix-Hook.apk" ] && \
        [ -f /data/adb/lspd/config/modules_config.db ]; then
    if CLASSPATH="$MODDIR/bin/lsposed-path-sync.jar" app_process /system/bin \
            com.aclaniakea.tools.LsposedPathSync \
            /data/adb/lspd/config/modules_config.db \
            "$MODDIR/hook/BaseFix-Hook.apk" >>"$LOGFILE" 2>&1; then
        log_msg "LSPosed hook path persisted for next boot"
    else
        log_msg "ERROR: late LSPosed hook path pin failed"
    fi
fi

# Settings uses the external BaseFix package as its Dolby control service.
# ColorOS marks that package idle about one minute after install/start and then
# stops the service even while DolbyMainActivity is still visible.  Keep this
# narrowly scoped bridge eligible for background execution so each UI change
# continues to reach the live session-0 DAP effect.
DOLBY_BRIDGE_PACKAGE=com.aclaniakea.colorosostatsguard
if pm path "$DOLBY_BRIDGE_PACKAGE" >/dev/null 2>&1; then
    pm enable --user 0 "$DOLBY_BRIDGE_PACKAGE" >/dev/null 2>&1
    pm enable --user 0 \
        "$DOLBY_BRIDGE_PACKAGE/com.aclaniakea.dolbybridge.DolbyBridgeService" \
        >/dev/null 2>&1
    cmd package unstop --user 0 "$DOLBY_BRIDGE_PACKAGE" >/dev/null 2>&1
    cmd deviceidle whitelist "+$DOLBY_BRIDGE_PACKAGE" >/dev/null 2>&1
    cmd appops set "$DOLBY_BRIDGE_PACKAGE" RUN_IN_BACKGROUND allow >/dev/null 2>&1
    cmd appops set "$DOLBY_BRIDGE_PACKAGE" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    am set-inactive "$DOLBY_BRIDGE_PACKAGE" false >/dev/null 2>&1
    log_msg "Dolby bridge enabled, unstopped, and allowed in background"
else
    log_msg "Dolby bridge package missing; background policy deferred"
fi

# Scene's ColorOS "关闭无障碍服务" switch writes the OEM whitelist through
# com.oplus.romupdate.provider.db.  Debloat state on the port can leave the
# stock provider package disabled (enabled=2), in which case Scene reports
# "Could not find provider" even though the authority exists in its manifest.
# Restore only this required OEM package; preserve the user's switch value.
ROMUPDATE_PACKAGE=com.oplus.romupdate
if pm path "$ROMUPDATE_PACKAGE" >/dev/null 2>&1; then
    pm enable --user 0 "$ROMUPDATE_PACKAGE" >/dev/null 2>&1
    cmd package unstop --user 0 "$ROMUPDATE_PACKAGE" >/dev/null 2>&1
    log_msg "ROMUpdate provider enabled for Scene accessibility policy"
fi

# perf HAL 的 stop/start 重载已删除（2026-08-29），改成只核对状态。
#
# 我们确实overlay了一份改正过的 SoC-696（六核）targetconfig.xml，但它是
# post-fs-data 第 97~98 行 bind mount 上去的，而两个 perf HAL 属于 `class hal`，
# 由 init 在 `on boot` 才拉起 —— 晚于 post-fs-data。也就是说它们**第一次**
# open 到的就已经是我们的文件，根本不存在"读到旧配置"的窗口。
#
# 而这次重载的代价是实打实的：两个 sleep 2 白占 4 秒串行时间，且正好落在
# boot_completed 之后最拥挤的那几秒——此时 system_server 正在恢复 CE 数据、
# 几十个自启应用在抢内存。把 perfservice 摘掉再插回去，中间那 2 秒里所有
# 走 AIDL 的升频提示全部失败，等于在最需要升频的时刻自断一条腿。
#
# 保留的只有状态核对：两者必须 running，否则 composer/framework 的升频提示
# 会变成失败 IPC 风暴并打满 system_server 的 binder 线程。真停了才补一次 start。
for _svc in vendor.perfservice perf2-hal-1-0; do
    [ "$(getprop "init.svc.$_svc")" = running ] || start "$_svc"
done
log_msg "perf HALs: perfservice=$(getprop init.svc.vendor.perfservice) perf2hal=$(getprop init.svc.perf2-hal-1-0)（SoC-696 targetconfig 由 post-fs-data bind，无需重载）"

# thermal-engine 的重载已删除（2026-08-29）。
#
# 原注释说"thermal-engine 可能在 KernelSU 挂完模块覆盖层之前就读了配置"——那是
# 配置还走 KernelSU system/ 覆盖层时代的结论。现在四份 thermal-engine_*.conf 是
# 由 post-fs-data 显式 bind mount 上去的，而 thermal-engine 是 `class main`、
# 由 `on boot` 启动，晚于 post-fs-data，所以它**第一次**读到的就已经是我们的配置。
#
# 更要命的是原厂 rc 自己就会重载一次：
#     /vendor/etc/init/init_thermal-engine-v2.rc
#         on property:sys.boot_completed=1
#             restart thermal-engine
# 而本脚本同样由 boot_completed 触发，于是我们这次 stop/start 是**第三次**加载，
# 并且和原厂那次 restart 抢同一个窗口，互相打断。删掉之后交还原厂时序。
#
# 撤销判据：若日后热区里再次找不到 skin-msm-therm-usr（由 post-fs-data 的
# oplus_shell_temp_compat.ko 提供），说明 bind/insmod 时序又变了，那时才需要恢复。

# Recover from the third-party "powersave" governor pin that locks a cluster to
# its minimum frequency after a long standby; only clusters actually found in
# that state get their min/max restored to this device's own cpuinfo_* bounds.
# Everything else is left to thermal-engine and the stock perf HAL.  Runs once
# at boot only (no resident guard/daemon).
#
# 注意：这一条修的是**运行期**现象（第三方调频器长待机后把 governor 留在
# powersave），冷启动时通常是 no-op。读回日志已挪到 apply_sched_baseline 之后，
# 否则读到的是温控建仓状态而不是模块写进去的值。
normalize_cpu

# ============================================================================
# 1+4+1 六核拓扑的调度基线
#
# 这些原先住在 SM8650Q-Scene-Scheduler 的 set_common()/apply_irq_topology() 里，
# 只有 Scene 调用 /data/powercfg.sh <mode> 时才会落地。2026-08-25 之后 Scene 不再
# 调用（scheduler.log 三天无新记录），于是全部静默失效：IRQ 全回 CPU0、
# default_smp_affinity=03、sched_upmigrate 回到 95、input_boost 关着、
# cpuset background 回到 0,3-4。用户直接感受到的就是"依旧卡顿"。
#
# 判据是「Scene 卸载了这条改动是否还必须成立」——是，所以搬到这里，开机一次性
# 写入，不做常驻轮询。调度模块只保留随模式变化的量（fmax_cap、rate_limit、
# hispeed、group_migrate、input_boost 的 ms/freq、kgsl min_freq）。
# ============================================================================
# 写入计数器：_sched_ok 落值成功、_sched_skip 节点不存在/不可写、
# _sched_same 已经是目标值。以前这些写全是静默的，日志只有最后一行读回，
# 于是"某个节点在这台机器上根本不存在"和"写了但被覆盖"看起来一模一样。
_sched_ok=0
_sched_skip=0
_sched_same=0

sched_write() {
    _v=$1
    _n=$2
    [ -w "$_n" ] || { _sched_skip=$((_sched_skip + 1)); return 0; }
    [ "$(cat "$_n" 2>/dev/null)" = "$_v" ] && { _sched_same=$((_sched_same + 1)); return 0; }
    if echo "$_v" >"$_n" 2>/dev/null; then
        _sched_ok=$((_sched_ok + 1))
    else
        _sched_skip=$((_sched_skip + 1))
        log_msg "sched baseline: 写入被拒 $_n <- $_v"
    fi
}

sched_irq_by_name() {
    awk -v name="$1" '$NF == name { gsub(":", "", $1); print $1 }' /proc/interrupts 2>/dev/null
}

sched_set_irq() {
    _cpu=$1
    for _irq in $(sched_irq_by_name "$2"); do
        _node="/proc/irq/$_irq/smp_affinity_list"
        [ -w "$_node" ] || continue
        [ "$(cat "/proc/irq/$_irq/effective_affinity_list" 2>/dev/null)" = "$_cpu" ] && continue
        echo "$_cpu" >"$_node" 2>/dev/null
    done
}

apply_sched_baseline() {
    # 只认这台机器的拓扑：SM8650Q + present=0-5 + 无 policy7。
    #
    # 刻意**不查 policy3**：policy 编号是 cpufreq 的 leader CPU 号，不是性能簇
    # 序号。本机中核拆成 policy1(CPU1-2) + policy3(CPU3-4) 两个频域，但把四颗
    # 中核合成一个 policy1 的内核同样是合法的 1+4+1，查 policy3 会把那种内核
    # 误判掉。这里写的都是 /proc/sys/walt 和 IRQ 亲和性，本来也不按 policy 分发。
    case "$(getprop ro.soc.model)/$(cat /sys/devices/system/cpu/present 2>/dev/null)" in
        *SM8650Q*/0-5) ;;
        *) log_msg "sched baseline: 拓扑不匹配，跳过"; return 0 ;;
    esac
    [ -d /sys/devices/system/cpu/cpufreq/policy7 ] && { log_msg "sched baseline: 检出 policy7，跳过"; return 0; }

    # --- IRQ 拓扑 ---------------------------------------------------------
    # 源机 SM8850 的 bootargs 带 irqaffinity=0-1，在 1+4+1 上会把可迁移中断全部
    # 压到唯一的弱核 CPU0（容量 379，中核 867、X4 1024）。默认掩码改为四颗中核。
    sched_write 1e /proc/irq/default_smp_affinity
    sched_set_irq 1 glink-native-adsp
    sched_set_irq 1 apps_rsc-drv-2
    sched_set_irq 1 ipcc_0
    sched_set_irq 2 hfi
    sched_set_irq 2 ufshcd
    sched_set_irq 2 dwc3
    sched_set_irq 2 msm-vidc          # 硬件编解码与其固件接口 hfi 同核，避免每帧跨核
    sched_set_irq 3 msm_drm
    sched_set_irq 3 NVT-ts
    sched_set_irq 3 spi_geni
    sched_set_irq 4 240b7400.qcom,bwmon-llcc
    sched_set_irq 4 24091000.qcom,bwmon-ddr
    sched_set_irq 4 msm_serial_geni0
    # i2c_geni 有多个实例（触控以外的传感器/PMIC 总线），统一挪到 CPU1。
    for _irq in $(awk 'index($NF, "i2c_geni") == 1 { gsub(":", "", $1); print $1 }' /proc/interrupts 2>/dev/null); do
        sched_write 1 "/proc/irq/$_irq/smp_affinity_list"
    done
    # WLAN 的 14 条中断（合计约 43.6 万次）同样钉在 CPU0，但 QCA 驱动给它们置了
    # IRQ_NO_BALANCING，写 smp_affinity 一律 EIO。硬中断搬不动，只能靠下面的 RPS
    # 把随后的 softirq 协议栈处理转到中核。这里不做注定失败的写。

    # --- wlan/p2p RPS -----------------------------------------------------
    # 原厂 init.qcom.post_boot.sh 只给 rmnet 配了 RPS，wlan 从未配置；源机八核上
    # QCA 自己的 NAPI 亲和能把 CE/DP 铺到大核簇，本机没有这个余地。
    # RPS 只搬 softirq、不触碰硬中断，属标准内核机制，清零即回滚。
    if [ -w /proc/sys/net/core/rps_sock_flow_entries ]; then
        _cur=$(cat /proc/sys/net/core/rps_sock_flow_entries 2>/dev/null)
        case "$_cur" in ''|*[!0-9]*) _cur=0 ;; esac
        # rps_flow_cnt 只有在全局 rps_sock_flow_entries 非零时才允许写，顺序不能反。
        [ "$_cur" -lt 32768 ] && sched_write 32768 /proc/sys/net/core/rps_sock_flow_entries
    fi
    _rps=0
    for _dev in wlan0 p2p0; do
        [ -d "/sys/class/net/$_dev" ] || continue
        for _q in /sys/class/net/"$_dev"/queues/rx-*; do
            [ -w "$_q/rps_cpus" ] || continue
            [ "$(cat "$_q/rps_cpus" 2>/dev/null)" = 1e ] && continue
            sched_write 1e "$_q/rps_cpus"
            sched_write 4096 "$_q/rps_flow_cnt"
            _rps=$((_rps + 1))
        done
    done

    # --- cpuset 拓扑修正 ---------------------------------------------------
    # 中间四核虽被固件拆成 policy1/policy3 两个频域，调度上仍是同容量的一簇。
    # 原厂给 background 的 0,3-4 会无故闲置 CPU1/CPU2，并在解锁时形成积压突发。
    sched_write '0-4' /dev/cpuset/background/cpus
    sched_write '0-4' /dev/cpuset/system-background/cpus
    sched_write '0-5' /dev/cpuset/foreground/cpus
    sched_write '0-5' /dev/cpuset/top-app/cpus
    # 144Hz 合成线程避开唯一的弱小核，同时可按需使用 Prime 核。
    sched_write '1-5' /dev/cpuset/sf/cpus

    # --- walt 常量 ---------------------------------------------------------
    sched_write 1 /proc/sys/walt/sched_sbt_enable
    sched_write 119 /proc/sys/walt/walt_rtg_cfs_boost_prio
    sched_write 400 /proc/sys/walt/sched_pipeline_util_thres
    sched_write 325 /proc/sys/walt/walt_low_latency_task_threshold
    sched_write 'libunity.so, libfb.so' /proc/sys/walt/sched_lib_name
    sched_write UnityMain /proc/sys/walt/sched_lib_task
    sched_write 3000 /proc/sys/walt/sched_disable_mvp_thres
    sched_write 0 /proc/sys/walt/sched_boost

    # 清掉源机留下的过高最小频率；scaling_max_freq 仍归温控与原厂 HAL。
    for policy in 0:364800 1:499200 3:499200 5:480000; do
        _p=/sys/devices/system/cpu/cpufreq/policy${policy%%:*}
        [ -d "$_p" ] || continue
        chmod 0644 "$_p/scaling_min_freq" 2>/dev/null
        sched_write "${policy##*:}" "$_p/scaling_min_freq"
    done

    # --- 迁移门槛基线（balance 档实测值）------------------------------------
    # 三位分别是 CPU0→中核、中核→中核、中核→X4。第一位在源系统里是 90：SM8850
    # 有四颗小核可以铺开负载，本机只有 CPU0 一颗弱核，90% 意味着它必然先饱和才
    # 开始搬运（实测空闲下 CPU0 忙 62% 而 X4 只有 7%）。
    # 交叉采样（settings 冷启动 TotalTime，6 轮轮换取中位）：
    #   [90 95 82] 1136ms / [60 95 82] 939ms / [60 60 60] 942ms
    # 收益完全来自弱核这一个边界，后两位保持原值。
    # WALT 拒绝 down >= up 的中间状态，必须先把 up 临时抬到 100 再原子落值。
    sched_write '100 100 100' /proc/sys/walt/sched_upmigrate
    sched_write '50 85 70' /proc/sys/walt/sched_downmigrate
    sched_write '60 95 82' /proc/sys/walt/sched_upmigrate
    sched_write 90 /proc/sys/walt/sched_group_upmigrate
    sched_write 80 /proc/sys/walt/sched_group_downmigrate

    # --- WALT 调频上限解限 --------------------------------------------------
    # 2026-08-29 实测设备上是 `1804800 2707200 2707200 2147483647`——四个槽分别来
    # 自 powersave 档、balance 档和内核默认，是调度模块旧版本留下的僵尸拼盘。
    # Scene 自 2026-08-25 起不再调用调度模块，没人再覆盖它，于是 CPU0 被永久钉在
    # 1804800（cpuinfo_max 2265600，低 20%）、四颗中核钉在 2707200（低 8.4%）。
    # 后果就是"中小核爆满而 X4 闲置"：小核和中核实时频率正好贴死在 cap 上、忙
    # 63~73%，X4 却停在 902MHz、只忙 49%。手工写回硬件上限后当场变成
    # X4 满频 3302400、六核忙 20~27%。
    #
    # 这里写各簇 cpuinfo_max_freq，等价于不限频——真正的限频交还温控与原厂 HAL，
    # 这才是原厂语义。两个坑：
    #   1. 节点固定吃 4 个值，给不满会把剩余槽补 0（实测 `a b c` => `a b c 0`），
    #      而 0 是"钉死在最低频"，比不写还糟。必须永远写满 4 个。
    #   2. 槽位对应 WALT cluster 而非 policy，中间四核占两槽（policy1/policy3）。
    sched_write '2265600 2956800 2956800 3302400' /proc/sys/walt/sched_fmax_cap

    # 触摸升频基线。调度模块的 powersave 档会把它关掉，但那是用户主动选的；
    # 缺省绝不能是关着的——没有 input boost 时滑动起手必然从低频爬。
    sched_write 1 /proc/sys/walt/input_boost/sched_boost_on_input
    sched_write 120 /proc/sys/walt/input_boost/input_boost_ms
    sched_write '1248000 1497600 1497600 1497600 1497600 1478400 0 0' /proc/sys/walt/input_boost/input_boost_freq

    log_msg "sched baseline applied: irq_default=$(cat /proc/irq/default_smp_affinity 2>/dev/null) upmigrate=$(cat /proc/sys/walt/sched_upmigrate 2>/dev/null | tr '\t' ' ') downmigrate=$(cat /proc/sys/walt/sched_downmigrate 2>/dev/null | tr '\t' ' ') fmax_cap=$(cat /proc/sys/walt/sched_fmax_cap 2>/dev/null | tr '\t' ' ') boost=$(cat /proc/sys/walt/input_boost/sched_boost_on_input 2>/dev/null) bg_cpus=$(cat /dev/cpuset/background/cpus 2>/dev/null) sf_cpus=$(cat /dev/cpuset/sf/cpus 2>/dev/null) rps_queues=$_rps writes=${_sched_ok}写/${_sched_same}已是/${_sched_skip}跳过"
}

# ============================================================================
# 必须等原厂的 post-boot 脚本先跑完，否则我们是在和它掷骰子。
#
# 高通的两个 oneshot 服务同样挂在 `sys.boot_completed=1` 上：
#     init.qti.kernel.rc:  on property:sys.boot_completed=1
#                              start kernel-boot / kernel-post-boot / memory-post-boot
#     init.qcom.rc:        on property:sys.boot_completed=1
#                              start qcom-post-boot
# kernel-post-boot 最终会按 soc_id 分派到 init.kernel.post_boot-pineapple.sh
# （本机 ro.soc.id=696 命中该分支），那个脚本才是原厂写 WALT 参数、cpuset、
# input_boost、各簇 min/max freq 和 governor 的地方。
#
# init **不保证** property trigger 之间的先后，所以本脚本此前与它完全并发：
# 我们写的 upmigrate/cpuset/min_freq 可能在几百毫秒后被原厂整体覆盖，而
# "sched baseline applied" 那行日志读回的只是那一瞬间的快照，看着成功而已。
# 这正是"基线明明写了却像没写"的根因，不是节点不可写。
#
# 改成等它们跑完：oneshot 服务执行结束后 init.svc.<name> 会变成 stopped。
# 有界轮询最多 60 秒，超时也照写（写晚了总比不写强），并把等待时长记进日志，
# 这样下次看日志就能判断是否真的排在了原厂之后。不挂常驻守护。
# ============================================================================
wait_for_stock_post_boot() {
    _waited=0
    while [ "$_waited" -lt 60 ]; do
        _kpb=$(getprop init.svc.kernel-post-boot)
        _qpb=$(getprop init.svc.qcom-post-boot)
        # 服务不存在时 getprop 返回空串，视同"无需等待"。
        case "$_kpb" in running) ;; *) case "$_qpb" in running) ;; *) break ;; esac ;; esac
        sleep 1
        _waited=$((_waited + 1))
    done
    log_msg "stock post_boot settled after ${_waited}s (kernel-post-boot=${_kpb:-absent} qcom-post-boot=${_qpb:-absent})"
}

wait_for_stock_post_boot
apply_sched_baseline

# 读回 CPU 策略放在基线之后，否则显示的是原厂/温控的建仓状态，
# 反映不出模块自己写进去的 min_freq，属于误导性日志。
for policy in /sys/devices/system/cpu/cpufreq/policy*; do
    log_msg "cpu $(basename "$policy") gov=$(cat "$policy/scaling_governor" 2>/dev/null) min=$(cat "$policy/scaling_min_freq" 2>/dev/null) max=$(cat "$policy/scaling_max_freq" 2>/dev/null)"
done

# ============================================================================
# 这里曾经有 tune_zram2ufs()：把 ub_zram2ufs_ratio 补成 15，让 zram 往 UFS 回写。
# **已删除，2026-08-31。**
#
# 当时的依据是"原厂 configure_hybridswap_parameters() 按 MemTotal 算出了 15，
# 却只拿去算 dd 预留区、从没写进 swapd_memcgs_param"，据此认为补 15 更接近原厂
# 本意。拿到原厂 PKX110 实测之后这个推断站不住：
#
#   原厂 level 0/1/2 的 ub_zram2ufs_ratio 全是 0
#   原厂 reclaimin_cnt = 0，ESU_C = 0   ← 从来没发生过一次 zram→UFS 回写
#
# 也就是说原厂就是发 0、就是不做回写，"算了却漏发"是我们把中间变量当成了本意。
# 用户明确要求以还原原厂机制为准，故撤销。
# ============================================================================


# ============================================================================
# hybridswap：avail_buffers 保持原厂分档值（2026-08-30 起）
#
# 原厂 configure_hybridswap_parameters() 按 MemTotal 分四档给 avail_buffers
# （四个值依次是 avail / min / high / free_swap_threshold，单位 MB）：
#     ≤3G  "200  100  200  512"
#     ≤4G  "1200 1000 1200 716"
#     ≤6G  "2000 1600 2000 1536"
#     否则 "2200 1800 2200 1536"      ← 本机 7763232kB 落这一档
#
# ---- 这里曾经把它降到 1500/1200/1500，2026-08-30 已撤销 ----
#
# 当时的推理是：最后一档是开口的，8G 和 16G 共用 min=1800；源机 12/16G 空闲时
# 可用内存八九个 G，这个门槛一辈子碰不到，而本机 8G 的 MemAvailable 稳定在
# 1.78~2.13G、满载低点 1577M，**永远在门槛以下**，于是 swapd 进入永久追赶。
# 当时用 60 秒空闲窗口测，min 从 1800 降到 1200 后 pswpout 降了约 80%，看着很像
# 一个干净的胜利。
#
# 这个测法漏掉了真正要紧的那一段：**解锁瞬间**。三点法（息屏时 / 解锁前 /
# 解锁后各打一次快照，把息屏段和解锁段分开量）之后才看清：
#
#   解锁段            1500/1200        2200/1800（原厂）
#   pgsteal_direct    332,747 (2599/s) 17,167 (188/s)   −93%
#   allocstall          5,490   (43/s)    242 (2.7/s)   −94%
#   pswpin            218,867          54,553           −75%
#   pgmajfault        227,933          57,396           −75%
#   息屏段
#   pgsteal_kswapd    353,548          86,167           −76%
#   pswpout            92,819          39,110           −58%
#
# 关键是 pgsteal_direct：它是**在分配路径上同步做的回收**，谁申请内存谁就卡在
# 那里等，这才是"长待机后第一次解锁卡一下"的物理来源。两种配置在息屏段的
# direct 都是 0；解锁瞬间桌面 +123MB、system_server +137MB 一起要内存，缓冲垫
# 只有 1200 时没有现成空闲页可给，只能自己下去刨 1.3GB。
#
# 而且"少留缓冲 = 少回收"这个直觉在这里是反的：缓冲垫砍掉三分之一之后，息屏段
# 的 kswapd 回收量反而涨了 4 倍（86k → 353k）—— 水位一直贴着线颠簸，换出去马上
# 又缺页换回来（息屏没人操作却有 71,965 次 pswpin）。当初那个 60 秒窗口只看到
# pswpout 降了，没看到 pswpin 和 refault 同步在涨。
#
# 连带修好的还有 zram→UFS 回写：它长期 reclaimin_cnt=0 不是"压力不够属正常"，
# 而是 MemAvailable(≈2.1G) 从来碰不到被我们调低的那道闸。抬回 1800 之后立刻
# reclaimin_cnt 0→13、ESU_C 0→38MB，zram 原始占用 2.90G→2.81G，
# 等于把 zram 压着的物理内存还了一部分回来。
#
# 结论：本机就该用原厂那一档。本函数保留，但写的是原厂值，只在 nandswap patch
# 没生效、参数退回别的档时兜底。
# ============================================================================
tune_avail_buffers() {
    node=/dev/memcg/memory.avail_buffers
    [ -w "$node" ] || { log_msg "hybridswap: $node 不可写，跳过"; return 0; }
    # free_swap_threshold 是原厂按档给的，读回来原样写回，不猜也不硬编码。
    fst=$(awk '/free_swap_threshold/{print $NF}' "$node" 2>/dev/null)
    case "$fst" in ''|*[!0-9]*) fst=1536 ;; esac
    before=$(tr '\n' ' ' <"$node" 2>/dev/null)
    # 写入格式必须是**四个**数：avail min high free_swap_threshold。三个数会被拒。
    echo "2200 1800 2200 $fst" >"$node" 2>/dev/null
    log_msg "hybridswap: avail_buffers 写回原厂档 2200/1800/2200/$fst（原 [$before]），实际=[$(tr '\n' ' ' <"$node" 2>/dev/null)]"
}

# 原厂 nandswap 服务在开机约 53 秒才跑完（ro.boottime.init.oplus.nandswap.sh），
# 而本脚本开头只等到 sys.boot_completed（约 30~45 秒），先到先写会被它覆盖。
# 不用盲睡，直接有界轮询我们依赖的那个状态本身：等 hybridswap_enable 里出现
# "swapd enable"（原厂脚本第 233 行写完才会有）且 swapd_pid 非 0。
# 注意不能拿 persist.sys.oplus.hybridswap_app_memcg 当标志——它是 persist 属性，
# 上次开机的值会一直留着，看不出本次是否已完成。
#
# 位置：这一段必须排在 service.sh 靠前，理由见文件末尾那段注释。轮询本身
# 就是它的同步点，提前放不会抢在原厂前面，只会少等前面那堆 pm 命令。
if [ -e /sys/block/zram0/hybridswap_enable ]; then
    waited=0
    while [ "$waited" -lt 180 ]; do
        st=$(cat /sys/block/zram0/hybridswap_enable 2>/dev/null)
        pid=$(cat /dev/memcg/memory.swapd_pid 2>/dev/null)
        case "$st" in
            *"swapd enable"*)
                case "$pid" in
                    ''|0|*[!0-9]*) ;;
                    *) break ;;
                esac
                ;;
        esac
        sleep 1
        waited=$((waited + 1))
    done
    if [ "$waited" -ge 180 ]; then
        log_msg "hybridswap: 等原厂 nandswap 就绪超时 ${waited}s（state=$st pid=$pid）"
        # --------------------------------------------------------------------
        # 自愈：超时到这一步，说明 init 压根没跑成 nandswap 服务，整次开机
        # 没有 eswap —— 这比参数不对严重得多，光补写节点没有任何意义
        # （swapd 都没起来）。已知一种成因：我们 bind 上去的脚本落在 nosuid
        # 挂载上，init 转不进 nandswap 域（详见 post-fs-data.sh 里的注释和
        # sepolicy.rule）。那种情况下脚本本身是好的，只是没人执行它。
        #
        # 所以这里手动跑一次。实测以 root 身份跑得通，收尾 state 会变成
        # "hybridswap enable reclaim_in enable swapd enable"；少数几个写入
        # （swapd_bind、oplus_healthinfo/swappiness_para）会因为域不同或节点
        # 不存在而失败，都不影响主链路。
        #
        # 只跑一次，跑完立刻复检；不轮询、不常驻。
        # --------------------------------------------------------------------
        if [ -x /product/bin/init.oplus.nandswap.sh ]; then
            sh /product/bin/init.oplus.nandswap.sh boot_completed >/dev/null 2>&1
            st=$(cat /sys/block/zram0/hybridswap_enable 2>/dev/null)
            pid=$(cat /dev/memcg/memory.swapd_pid 2>/dev/null)
            log_msg "hybridswap: 已手动补跑原厂脚本一次，state=$st pid=$pid"
        fi
    else
        log_msg "hybridswap: 原厂 nandswap 已就绪（等待 ${waited}s，swapd_pid=$pid）"
    fi
    # 原厂脚本写完 enable 之后还会继续写参数，留 1 秒交接窗口即可（原为 3 秒）。
    sleep 1

    # ------------------------------------------------------------------------
    # 2026-08-29：这里原本无条件调 tune_avail_buffers 抢写。
    # 现在 post-fs-data 阶段已经把 /product/bin/init.oplus.nandswap.sh 本身
    # patch 过并 bind 上去了（avail_buffers 保持原厂 2200/1800/2200/1536，且把它
    # $zram2ufs_ratio 真正下发到 swapd_memcgs_param），所以正常路径下原厂脚本
    # 出来的值**就已经是对的**，不需要我们再写一遍。
    #
    # 这里只做核对：值对就什么都不干，只记一行；值不对（说明 bind 没成、
    # 或 ROM 更新后特征串没匹配上被跳过了）才回落到运行时写入。
    # 一个坏掉的 patch 不该让参数悄悄退回原厂的 16G 档。
    # ------------------------------------------------------------------------
    # 判据是"不低于本机档位"，不是"等于"：perf HAL 在运行时把它抬高是原厂行为，
    # 抬高的值要放行；只有明显偏低（说明 nandswap patch 没生效、退回了别的档）
    # 才兜底写一次。
    _a_now=$(awk '/^avail_buffers/{print $NF}' /dev/memcg/memory.avail_buffers 2>/dev/null)
    case "$_a_now" in
        ''|*[!0-9]*) log_msg "hybridswap: avail_buffers 读不出来（[$_a_now]），不动它" ;;
        *)
            if [ "$_a_now" -ge 2200 ]; then
                log_msg "hybridswap: avail_buffers=$_a_now 不低于本机档位，放行"
            else
                log_msg "hybridswap: avail_buffers=$_a_now 低于本机档位 2200，兜底写一次"
                tune_avail_buffers
            fi
            ;;
    esac

    # ------------------------------------------------------------------------
    # 这里曾经有一段后台任务：等 perf HAL 在开机 +55~80 秒把 avail_buffers 推成
    # 2300/2000/2300 之后，再按脚本分档写回 2200/1800/2200。**已删除，2026-08-31。**
    #
    # 拿原厂 PKX110 对照才看清这是偏离：原厂 12G 机型的脚本分档同样是
    # 2200/1800/2200，而实机跑的是 **2500/2200/2500** —— 也是 perf HAL 在运行时
    # 抬上去的，**原厂让它抬**。我们却把 HAL 的运行时决策按回静态值。
    #
    # 而且方向还不利：这等于把内存缓冲垫调低。之前实测过缓冲垫直接影响解锁卡顿
    # （2200/1800 相比 1500/1200，解锁瞬间的 pgsteal_direct 降 93%），HAL 抬到
    # 2300/2000 只会更好。
    #
    # 所以这一处撤销同时满足"更像原厂"和"数据上更优"，没有取舍。
    # 上面那段核对逻辑也一并放宽：只在值明显不对（说明 nandswap patch 没生效、
    # 退回了别的档）时才兜底写一次，HAL 抬高的值一律放行。
    # ------------------------------------------------------------------------
else
    log_msg "hybridswap: 节点不存在，跳过 zram2ufs 调整"
fi

# ============================================================================
# 常驻系统进程的 memcg 豁免（原厂缺口）
#
# ColorOS 的 per-uid memcg 模式下，`memory.app_score` 只有一个写入者：
# OplusOsenseCompressAction.setMemcgAppScore()，由 Nirvana 在**前台 app 切换**时
# 调用——前台那一个 uid 写 0，切走写回 300。链路本身是通的（我们在 2.x 补的三个
# 属性，见 post-fs-data.sh），实测桌面在前台时 uid_10091=0、切走后被框架改回 300。
#
# 缺口在于：**system_server 和 SystemUI 永远不会成为"前台 app"**，
# 框架的 uid 观察器根本不覆盖它们，于是它们永久停在内核默认的 300，
# 落进 level 1（score 100-399, ub_mem2zram_ratio=80）被当成普通后台按 80% 压。
#
# 实测代价（L1117/L1119，静置 60s 窗口）：
#
#   写豁免前  com.android.systemui  majflt 339.3/s  swap 178 MB   adj=-800
#             system_server         majflt  35.5/s  swap 222 MB   adj=-900
#   写豁免后  com.android.systemui  majflt   5.6/s
#             system_server         majflt   5.2/s
#
# 60 倍。而且这是 100% refault（换进来的每一页都是刚换出去的），纯颠簸零收益；
# SystemUI 是状态栏/通知/最近任务，每一次触摸都要它，这就是可感知卡顿的直接来源。
#
# 两个已核实的前提：
#  1. app_score 对**全局 kswapd** 同样有效。同窗口 hybridswapd +0 tick、
#     pgscan_direct=0，扫页的全是 kswapd0（3918/s），写完分数照样降 60 倍——
#     一加内核把 app_score 接进了全局回收路径，不只是 swapd 的分档。
#  2. 写进去**不会被框架改回**。同一 60s 窗口里框架把 uid_10091 从 0 改成了 300，
#     却没碰 uid_1000 / uid_10094，证明它在写、只是不管这两个。
#     所以一次性写入即可，不需要守护脚本。
#
# 取值 0 而不是 -1：0 正是 Nirvana 给前台写的值，落进 level 0（ratio=0，完全豁免），
# 语义与原厂一致；-1 是原厂给 /dev/memcg/apps/{active,launcher,systemserver}
# 那三个**包名模式专用、本机全空**的死目录用的。
#
# 名单不写死 uid（各机安装后 appid 不同），按 oom_score_adj <= -700 现场枚举：
# 这正好是 AOSP 的 PERSISTENT_PROC_ADJ(-800) / SYSTEM_ADJ(-900) / PERSISTENT_SERVICE_ADJ(-700)
# 三档，即"内核眼里绝不该被换出"的那批。普通应用最低也只到 0（前台）。
# ============================================================================
exempt_persistent_memcg() {
    [ -d /dev/memcg/apps ] || { log_msg "memcg-exempt: /dev/memcg/apps 不存在，跳过"; return 0; }

    _n=0
    _seen=" "
    _names=""
    for _p in /proc/[0-9]*; do
        _adj=$(cat "$_p/oom_score_adj" 2>/dev/null) || continue
        case "$_adj" in
            -*) ;;                     # 只看负 adj，其余直接跳过
            *) continue ;;
        esac
        [ "$_adj" -le -700 ] 2>/dev/null || continue

        _uid=$(awk '/^Uid:/{print $2; exit}' "$_p/status" 2>/dev/null)
        [ -n "$_uid" ] || continue
        # 同一个 uid 下往往有几十个进程（uid_0/uid_1000 尤其），去重，否则重复写几百次
        case "$_seen" in *" $_uid "*) continue ;; esac
        _seen="$_seen$_uid "

        _d=/dev/memcg/apps/uid_$_uid
        [ -f "$_d/memory.app_score" ] || continue

        # uid 层与它下面所有 pid 层都写：匿名页是记在叶子 memcg 上的。
        echo 0 > "$_d/memory.app_score" 2>/dev/null
        for _pd in "$_d"/pid_*/; do
            [ -f "$_pd/memory.app_score" ] && echo 0 > "$_pd/memory.app_score" 2>/dev/null
        done

        if [ "$(cat "$_d/memory.app_score" 2>/dev/null)" = 0 ]; then
            _n=$((_n + 1))
            _names="$_names $_uid"
        fi
    done

    if [ "$_n" -gt 0 ]; then
        log_msg "memcg-exempt: 已给 $_n 个常驻 uid 写 app_score=0 —— uid:$_names"
    else
        log_msg "WARN: memcg-exempt: 没找到任何 adj<=-700 的 uid memcg，前台豁免链路可能没起来"
    fi
}

exempt_persistent_memcg

# The port's tango translator repeatedly aborts on this tablet's 32-bit
# runtime. Keep the native secondary zygote stopped instead of respawning it.
stop zygote_tango
resetprop -p persist.sys.horae.enable 1
apply_serial_fix

# ============================================================================
# 停用源机遗留的幽灵热区
#
# 移植 ROM 的设备树带来一批本机没有的传感器。它们并未被 thermal-engine 用作
# 策略源（CPU 用 cpuss-0..3、电池用 skin-msm-therm，见 2.0.x 的修复），但仍以
# enabled 状态出现在 /sys/class/thermal 里，被监控类应用当成真实温度读走：
#
#   wls-therm      -40960 (-41.0°C)   无线充电线圈，本机无此硬件
#   wireless       -40000 (-40.0°C)   同上
#   其余六个        恒为 29500        对应 ADC 通道未接，29.5°C 是缺省填充值
#
# 实测室温 20°C 以上，-41/-40 已在物理上不可能；29500 在整个采样窗口内一分不动，
# 而同期真实热区在 36~67°C 间正常起伏。
#
# 判据分两层，任一不满足就跳过该热区：
#
#   共同前提  名称在显式白名单内，且读数确实不可能（负值，或恰为 29500 缺省值）。
#             将来若 ROM 把这些通道接上并开始上报有效值，本段自动不再动它们。
#
#   未绑定    直接停用。
#   已绑定    额外要求最低 trip 点比当前读数高出 30°C 以上，用以证明这些绑定在
#             当前读数下永远不可能触发——停用它不会解开任何实际生效的保护。
#             本机 xo-therm 绑 36 个 cooling device、最低 trip 78°C，
#             pm8550vs_g_tz 绑 3 个、最低 trip 95°C，读数均恒为 29.5°C，
#             余量分别为 48.5°C 与 65.5°C，属于已经死掉的绑定。
#
# 明确不处理 vbat / ibat / socd：它们分别是电池电压(V)、电池电流(A) 与 SoC 降频
# 百分比(0~100)，HAL 已用正确的 mType 6/7/8 上报，是监控应用把它们渲染成 °C。
# 覆盖这些值等于破坏真实数据。
# ============================================================================
GHOST_THERMAL_ZONES="wls-therm wireless cam-flash-therm wlan-therm rear-tof-therm usb-therm pm8550b_tz pm8550vs_c_tz xo-therm pm8550vs_g_tz"
GHOST_TRIP_MARGIN=30000

lowest_trip_temp() {
    zone="$1"
    low=""
    for trip in "$zone"/trip_point_*_temp; do
        [ -r "$trip" ] || continue
        tv=$(cat "$trip" 2>/dev/null)
        case "$tv" in ''|*[!0-9-]*) continue ;; esac
        [ "$tv" -le 0 ] && continue
        if [ -z "$low" ] || [ "$tv" -lt "$low" ]; then low="$tv"; fi
    done
    echo "$low"
}

disable_ghost_thermal_zones() {
    disabled_list=""
    for zone in /sys/class/thermal/thermal_zone*; do
        [ -r "$zone/type" ] || continue
        zname=$(cat "$zone/type" 2>/dev/null)
        [ -n "$zname" ] || continue

        listed=0
        for ghost in $GHOST_THERMAL_ZONES; do
            [ "$zname" = "$ghost" ] && listed=1 && break
        done
        [ "$listed" = 1 ] || continue

        value=$(cat "$zone/temp" 2>/dev/null)
        case "$value" in ''|*[!0-9-]*) continue ;; esac
        # 只处理物理上不可能的两种形态：负值，或恰为未接通道的 29500 缺省值。
        if [ "$value" -ge 0 ] && [ "$value" -ne 29500 ]; then
            log_msg "thermal: $zname now reports $value; left enabled"
            continue
        fi

        if [ -e "$zone/cdev0" ]; then
            low=$(lowest_trip_temp "$zone")
            if [ -z "$low" ]; then
                log_msg "thermal: $zname is bound but has no usable trip; left enabled"
                continue
            fi
            if [ "$((low - value))" -lt "$GHOST_TRIP_MARGIN" ]; then
                log_msg "thermal: $zname trip $low too close to $value; left enabled"
                continue
            fi
        fi

        [ "$(cat "$zone/mode" 2>/dev/null)" = disabled ] && continue
        chmod 644 "$zone/mode" 2>/dev/null
        if echo disabled >"$zone/mode" 2>/dev/null &&
           [ "$(cat "$zone/mode" 2>/dev/null)" = disabled ]; then
            disabled_list="$disabled_list $zname"
        else
            log_msg "WARN: thermal: failed to disable $zname"
        fi
    done
    [ -n "$disabled_list" ] && log_msg "thermal: disabled ghost zones:$disabled_list"
    return 0
}

disable_ghost_thermal_zones

# ============================================================================
# 让框架的 Thermal Status 不再从开机起就报 SEVERE
#
# QTI 的 thermal HAL 把 skin-msm-therm 这个 zone 的 trip_point_0 当作 skin 传感器
# 的 SEVERE 阈值上报给框架，而 thermal-engine 又把同一个 trip 当成自己的通知触发
# 点在不停改写——实测它就在 43000/45000/48000/51000/54000 之间跳，正是 GPU 降频
# 梯子的那几级。于是主板 43°C（本机待机温度）就被框架当成"严重降频"，此时真实
# 外壳温只有 35°C。
#
# 上面的配置覆盖已经把所有生效的策略块从 skin-msm-therm 迁到 shell-therm，
# 因此不再有人改写这个 trip，可以把它设成一个真正有意义的值。这一步在内核侧是
# 惰性的：本机 skin-msm-therm 的 trip0 没有绑定任何 cooling device（cdev0 绑在
# trip2/60°C，且是 gpu-dump-skip-cdev 这个空壳），过热保护仍由 trip1(95°C)、
# trip2 以及 cpuss-*/gpuss-* 各自的配置负责。
#
# 只在确认 trip0 未绑定任何 cooling device 时才动它。
# ============================================================================
SKIN_STATUS_TRIP=68000

relax_skin_status_trip() {
    for zone in /sys/class/thermal/thermal_zone*; do
        [ "$(cat "$zone/type" 2>/dev/null)" = skin-msm-therm ] || continue

        for bound in "$zone"/cdev*_trip_point; do
            [ -r "$bound" ] || continue
            if [ "$(cat "$bound" 2>/dev/null)" = 0 ]; then
                log_msg "thermal: skin trip0 has a cooling device bound; left alone"
                return 0
            fi
        done

        current=$(cat "$zone/trip_point_0_temp" 2>/dev/null)
        case "$current" in ''|*[!0-9]*) return 0 ;; esac
        [ "$current" -ge "$SKIN_STATUS_TRIP" ] && return 0

        chmod 644 "$zone/trip_point_0_temp" 2>/dev/null
        if echo "$SKIN_STATUS_TRIP" >"$zone/trip_point_0_temp" 2>/dev/null &&
           [ "$(cat "$zone/trip_point_0_temp" 2>/dev/null)" = "$SKIN_STATUS_TRIP" ]; then
            log_msg "thermal: skin SEVERE trip raised $current -> $SKIN_STATUS_TRIP"
        else
            log_msg "WARN: thermal: could not raise skin SEVERE trip from $current"
        fi
        return 0
    done
    return 0
}

relax_skin_status_trip

# ============================================================================
# 内存扩展 / 压缩交换：已交还给原厂
#
# 这里原本有三组东西，随 aclswap 一并退场：
#   * active_swap_sysfs / publish_ram_expansion_settings —— 读实际 swap 设备，
#     反推 persist.sys.oplus.nandswap* 三个镜像属性和 Secure 里的 record_* 键；
#   * aclswap 写回驱动（bin/aclswap-writeback.sh）与它的常驻巡检循环；
#   * retire_stock_zram_for_aclswap —— 开机后把原厂 zram0 摘掉。
#
# ★ 镜像属性尤其不能再由我们写。原厂 init.oplus.nandswap.sh 把
#   persist.sys.oplus.nandswap.swapsize 当作**输入**（disksize = swapsize +
#   zram_increase），而 hybridswap 的 disksize 本来就大于 swapsize；再按 disksize
#   反推回 swapsize 写回去，下次开机就会在原基础上再加一次 increase，逐次膨胀。
#   镜像本就该由 Athena 的 ContentObserver 从 Settings.Secure 派生，交还给它。
# ============================================================================


# ============================================================================
# 关闭 WLAN 固件诊断日志
#
# cnss_diag 是高通的 WLAN 诊断记录器，把主机驱动与固件日志写进
# /data/vendor/wifi/wlan_logs/。实测它持续写入约 1.3 MB/s——一小时约 4.7 GB
# 落到闪存上，本机已经堆了 400 MB 轮转日志。量产使用中没有人读这些日志。
#
# 收益只有闪存寿命和 I/O 这一条，**不要**当成性能优化：交叉 A/B 四轮（运行 vs
# 停止），六核合计占用中位数 193.3% vs 176.9%、PSI some 21.6 vs 23.1，方差
# （124–234%）远大于臂间差异，CPU 上测不出任何可信收益。
#
# 只停 cnss_diag 这一个。同一族的 vendor.diag-router 是承重墙而不是废物：实测
# 停掉它之后 ims-dataservice-daemon / imsdaemon / ims_rtp_daemon / cnd /
# vendor.dpmd / sensors.qti / sensors-service.multihal / bluetooth HAL /
# audio HAL 九个原生守护进程会集体陷入 binder 重试死循环，六核从 37–62% 直接
# 打满到 494% user，恢复该服务后立刻回落。hwdiag / nvram_diag / ipacm-diag 未
# 单独验证过，一并不动。cnss-daemon 更是管 WLAN 固件协同的，与诊断无关。
# ============================================================================
WLAN_LOG_DIR=/data/vendor/wifi/wlan_logs

disable_wlan_diag_logging() {
    [ "$(getprop init.svc.vendor.cnss_diag)" = running ] || return 0
    stop vendor.cnss_diag 2>/dev/null
    sleep 1
    if [ "$(getprop init.svc.vendor.cnss_diag)" = running ]; then
        log_msg "WARN: wlan-diag: cnss_diag still running after stop"
        return 1
    fi
    log_msg "wlan-diag: cnss_diag stopped (was writing ~1.3MB/s to flash)"

    # 回收它已经写下的轮转日志。只删自己那两组文件名，不动目录本身、也不碰
    # 同目录下别的东西；正在写的 *_current.txt 同样只在服务已停后才删。
    [ -d "$WLAN_LOG_DIR" ] || return 0
    freed=$(du -sk "$WLAN_LOG_DIR" 2>/dev/null | awk '{print $1}')
    case "$freed" in ''|*[!0-9]*) freed=0 ;; esac
    rm -f "$WLAN_LOG_DIR"/cnss_fw_logs_*.txt "$WLAN_LOG_DIR"/host_driver_logs_*.txt 2>/dev/null
    after=$(du -sk "$WLAN_LOG_DIR" 2>/dev/null | awk '{print $1}')
    case "$after" in ''|*[!0-9]*) after=0 ;; esac
    [ "$freed" -gt "$after" ] &&
        log_msg "wlan-diag: reclaimed $(( (freed - after) / 1024 ))MB of wlan logs"
    return 0
}

disable_wlan_diag_logging

# ============================================================================
# 停用无蜂窝机型上永不使用的电话栈
#
# 本机 ro.baseband=apq、ro.carrier=wifi-only，没有 modem 硬件。移植 ROM 却把
# 一加平板的整套电话用户态原样带了过来，并且用 lib-virtual-modem-radio.so 起了
# 两个"假 RIL"去喂框架，好让 telephony 不至于崩。这套东西 CPU 时间恒为
# 00:00:00，但吃内存（2026-08-28 实测）：
#
#   org.codeaurora.ims           RSS 37.9MB + zram 73.7MB  ≈ 111MB
#   subsys_daemon ×3（含两个假 RIL） RSS 14.1MB + zram  9.1MB
#   imsdaemon / ims_rtp_daemon / ims-dataservice-daemon
#                                RSS 16.9MB + zram  6.5MB
#   vendor.dpmd + dpmQmiMgr      RSS  5.4MB + zram  1.9MB
#
# 合计约 190MB 匿名内存、其中约 95MB 压在 zram 里。这机器只有 8GB，同期
# MemFree 只剩 282MB、换页速率 7668 in / 10360 out 页每秒——这批常驻是在
# 白占换页预算。此前 #43 判定"代价为零"，那次只看了 CPU 时间，漏了内存，是错的。
#
# 关的顺序有讲究，必须先摘 feature 再停守护进程：
#   framework 侧的 telephony 由 PackageManager 的 feature 位决定。feature 还在
#   的时候直接停 RIL，com.android.phone 会一直重连不上而刷屏甚至崩溃循环；
#   feature 摘掉后 telephony 栈根本不初始化，假 RIL 就没有客户端了。
#   feature 的摘除靠 system/etc/permissions/apq_excluded_telephony_features.xml
#   （ROM 自带、放在 noRil/ 子目录里从没被扫到的那份），开机时由 PackageManager
#   读取，见那个文件里的注释。所以本段必须在**该文件已生效的那次开机**才有意义。
#
# 明确不动的：cnss-daemon（WLAN 子系统守护，同名不同源）、per_mgr / per_proxy /
# qmipriod / ssgqmigd / nvram_qmi（共用 QMI 基础设施，GPS 与 WiFi 也挂在上面）、
# qti-modem-daemon-0（真 QTI radio service 装载器，留着当框架万一回头找 RIL 的
# 兜底）。也不动 com.android.phone——它是 persistent 进程，杀了会立刻重生。
#
# 分级开关：出问题就把 BASEBAND_STOP_LEVEL 调回 1（只停 IMS/DPM，保留假 RIL），
# 或调成 0 完全还原。全部是一次性 stop，不留守护脚本。
#
# ★ 2026-08-30 起默认 0（完全还原）。摘 telephony feature 把「通信共享」
#   （平板借手机 SIM 打电话/收短信/上网）整条入口打没了 —— 那个功能不需要真 modem，
#   但需要 telephony 框架层在册。换来的只是 com.android.phone + org.codeaurora.ims
#   那 225MB 冷内存，而这俩因为 FLAG_PERSISTENT 本来就杀不掉。不划算，已回滚。
#   post-fs-data.sh 里那条 feature 排除 bind 也一并注释掉了。
# ============================================================================
BASEBAND_STOP_LEVEL=0

BASEBAND_SVC_IMS="vendor.imsdaemon vendor.ims_rtp_daemon vendor.ims-dataservice-daemon vendor.dpmd dpmQmiMgr"
BASEBAND_SVC_RIL="virtual-ril-daemon-0 virtual-ril-daemon-1 qti-modem-daemon-0"

stop_dead_telephony_stack() {
    [ "$BASEBAND_STOP_LEVEL" -ge 1 ] 2>/dev/null || return 0

    # 前提校验：只在确实没有蜂窝硬件时动手。任一条不成立就整段跳过，
    # 这样万一将来换了带 modem 的底包，本段自动失效而不是把电话打瘸。
    baseband=$(getprop ro.baseband)
    case "$baseband" in
        apq|apq_*|""|unknown) ;;
        *) log_msg "telephony-strip: skipped, ro.baseband=$baseband"; return 0 ;;
    esac
    # 第二道 ro 判据。刻意**不**用 gsm.version.baseband：本机那个值是
    # "Virtual RILD Modem,Virtual RILD Modem"，是假 RIL 自己写上去的，
    # 2026-08-28 第一版拿"它非空"当"有真 modem"而整段跳过，等于让要清理的目标
    # 自己签发免死金牌。而且本段末尾会把这个属性抹成 unknown，再拿它当判据就
    # 变成了自我指涉。ro.baseband / ro.carrier 是底包烧死的只读属性，
    # 不受本模块任何操作影响，是唯一可靠的依据。
    carrier=$(getprop ro.carrier)
    case "$carrier" in
        wifi-only|""|unknown) ;;
        *) log_msg "telephony-strip: skipped, ro.carrier=$carrier"; return 0 ;;
    esac

    # feature 必须已经被摘掉才继续。判据故意用 telephony.ims 而不是
    # android.hardware.telephony：后者在本机开机时本来就不存在（联想 odm 没声明），
    # 拿它当判据等于没判。telephony.ims 是 /odm/etc/permissions/
    # android.hardware.telephony.ims.xml 声明的、当前确实在册的那一条——它消失了，
    # 才能证明我们那份 apq_excluded_telephony_features.xml 这次真的被扫到并生效了。
    if pm list features 2>/dev/null | grep -q '^feature:android.hardware.telephony.ims$'; then
        log_msg "telephony-strip: skipped, telephony.ims feature still present (permissions overlay not in effect)"
        return 0
    fi
    # telecom 是微信/QQ 语音通话要用的，必须还在。它没了说明排除清单误伤，立刻收手。
    if ! pm list features 2>/dev/null | grep -q '^feature:android.software.telecom$'; then
        log_msg "telephony-strip: ABORT, android.software.telecom was removed by mistake"
        return 0
    fi

    targets="$BASEBAND_SVC_IMS"
    [ "$BASEBAND_STOP_LEVEL" -ge 2 ] && targets="$targets $BASEBAND_SVC_RIL"

    stopped=""
    for svc in $targets; do
        [ "$(getprop "init.svc.$svc")" = running ] || continue
        stop "$svc" 2>/dev/null
        stopped="$stopped $svc"
    done
    [ -n "$stopped" ] && sleep 1

    still=""
    for svc in $stopped; do
        [ "$(getprop "init.svc.$svc")" = running ] && still="$still $svc"
    done

    # IMS 的应用侧进程 org.codeaurora.ims，全栈里最大的一块（实测 RSS 99.7MB +
    # zram 30.2MB）。2026-08-28 第一版只做 am force-stop，指望"feature 没了就不会
    # 再被 bind"——实测**杀了立刻回来**：这个包在 manifest 里是 persistent 的，
    # system_server 会无条件重启它，跟有没有 telephony feature 无关。
    # 所以必须改包状态。用 pm disable（而不是卸载）：一条 pm enable 就能原样恢复，
    # 不动 APK、不动数据。本机 ro.carrier=wifi-only、telephony feature 已全部摘除，
    # 这个 IMS 实现没有任何可服务的对象。
    ims_pid=$(pidof org.codeaurora.ims 2>/dev/null | awk '{print $1}')
    if [ -n "$ims_pid" ]; then
        ims_rss=$(awk '/^VmRSS:/{print $2}' "/proc/$ims_pid/status" 2>/dev/null)
        ims_swap=$(awk '/^VmSwap:/{print $2}' "/proc/$ims_pid/status" 2>/dev/null)
    fi
    # com.android.phone 是 org.codeaurora.ims 的上游宿主：它握着 ImsService /
    # QtiImsExtService / ImsRilService 三个 binding，只要它活着，IMS 就会被反复拉起。
    # 它自己也是纯电话栈（TeleService），本机没有 modem，整个进程无事可做。
    # 注意 telecom（微信/QQ 语音走的 ConnectionService）在 com.android.server.telecom
    # 里，是另一个包，本段完全不碰，已用 pm list packages 核实。
    #
    # 这个包带 PERSISTENT 标志。实测（2026-08-28）：当场 pm disable + kill -9 之后
    # 它仍然重生，logcat 明写 "Process com.android.phone has died: pers PER" —— AMS
    # 的常驻进程表是开机时一次性建好的，之后改包状态不会从表里摘掉它。所以这一刀
    # 只在**下次开机**生效（PMS 在建表前就把 disabled 的包滤掉了）。
    # 也就是说本函数当场杀不掉它是预期行为，不要据此判定失败。
    # 撤销：pm enable --user 0 com.android.phone && reboot
    phone_state=$(pm list packages -d 2>/dev/null | grep -c '^package:com.android.phone$')
    if [ "$phone_state" = 0 ]; then
        pm disable --user 0 com.android.phone >/dev/null 2>&1 &&
            log_msg "telephony-strip: disabled com.android.phone (takes effect next boot); revert with: pm enable --user 0 com.android.phone"
    fi

    ims_state=$(pm list packages -d 2>/dev/null | grep -c '^package:org.codeaurora.ims$')
    if [ "$ims_state" = 0 ]; then
        if pm disable --user 0 org.codeaurora.ims >/dev/null 2>&1; then
            log_msg "telephony-strip: disabled org.codeaurora.ims (was rss=${ims_rss:-0}kB swap=${ims_swap:-0}kB); revert with: pm enable --user 0 org.codeaurora.ims"
        else
            am force-stop --user 0 org.codeaurora.ims >/dev/null 2>&1
            log_msg "telephony-strip: WARN pm disable failed for org.codeaurora.ims, fell back to force-stop"
        fi
    else
        log_msg "telephony-strip: org.codeaurora.ims already disabled"
    fi
    am force-stop --user 0 org.codeaurora.ims >/dev/null 2>&1
    # force-stop 对它无效——实测 pm disable + am force-stop 之后 pid 一动不动，
    # 因为 com.android.phone 正握着三个活的 binding。补一刀 kill -9。
    # 但同样受上面 "pers PER" 那条限制：只要 com.android.phone 这一轮还活着，
    # 杀掉的 IMS 就会被它重新 bind 起来。两个包都 disabled 之后，下一次开机
    # 二者都不会再出现，这才是真正的了结。本轮杀一刀只为回收当前这份内存。
    for p in $(pidof org.codeaurora.ims 2>/dev/null); do
        kill -9 "$p" 2>/dev/null &&
            log_msg "telephony-strip: killed lingering org.codeaurora.ims pid=$p"
    done

    # 抹掉显示层残留的假基带字符串。设置里"关于本机 → 基带版本"读的就是这个属性，
    # 它是假 RIL 启动时自己写的；RIL 停掉之后这个值就是一句无主的谎话。
    # 这一步纯粹改显示、不省内存，且不是 persist 属性，重启即回到底包默认，
    # 由本脚本每次开机重新设置。判据不依赖它（见上面 ro.carrier 那段）。
    case "$(getprop gsm.version.baseband)" in
        ''|unknown) ;;
        *)
            resetprop gsm.version.baseband unknown 2>/dev/null &&
                log_msg "telephony-strip: masked stale gsm.version.baseband"
            ;;
    esac

    log_msg "telephony-strip: level=$BASEBAND_STOP_LEVEL stopped=[${stopped# }] still_running=[${still# }] memfree=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)kB"
}

stop_dead_telephony_stack

# BWV enablement is archived under payload/retired/voice-wakeup-bwv and is
# never sourced on this tablet: the vendor DSP model is incompatible.

disable_voice_wakeup() {
    # 用户明确不使用小布唤醒：关闭总开关并禁用仅负责唤醒的 OVMS 包。
    # SpeechAssist 本体保持启用，不影响小布的非唤醒能力。
    for key in hotword_detection_enabled voice_to_wakeup aclaniakea_xiaobu_wakeup_entry; do
        settings put global "$key" 0 >/dev/null 2>&1
    done
    am force-stop --user 0 com.oplus.ovoicemanager.wakeup >/dev/null 2>&1
    pm disable-user --user 0 com.oplus.ovoicemanager.wakeup >/dev/null 2>&1
    log_msg "voice wake package disabled and XiaoBu wake entry hidden by user policy"
}

disable_voice_wakeup

# BWV listening-window override is retired with the enable path above.  Do not
# bind OVMS_settings.xml: the stock file remains untouched while wakeup is off.

# Expose the ColorOS battery-health entry; its data is bridged by the
# LSPosed BatteryHealthBridge from the real power-supply sysfs.
if [ "$(settings get system os.charge.settings.batterysettings.batteryhealth 2>/dev/null | tr -d '\r')" != 1 ]; then
    settings put system os.charge.settings.batterysettings.batteryhealth 1 >/dev/null 2>&1
    log_msg "battery health entry enabled"
fi

# （删除了一行 log_msg "stable LSPosed Hook payload verified" —— 它上面没有任何
#   校验动作，是无条件打印的假日志，只会在排障时把人往错方向带。真正的路径固定
#   与校验在本脚本开头的 LsposedPathSync 那段，成功失败都有日志。）
if pm path com.aclaniakea.colorosaonlifecycle >/dev/null 2>&1; then
    if pm uninstall --user 0 com.aclaniakea.colorosaonlifecycle >>"$LOGFILE" 2>&1; then
        log_msg "removed standalone AON lifecycle package after integration"
    else
        log_msg "standalone AON lifecycle package removal deferred"
    fi
fi


start horae
start gameopt_hal_service-1-0

if perfetto --query 2>/dev/null | grep -q "Tracing sessions: [1-9]"; then
    am startservice --user 0 -n com.android.traceur/.OplusTraceService \
        -a oplus.intent.action.TRACEUR_STOP_TRACING \
        --es from coloros_port_fix >/dev/null 2>&1
fi

if module_enabled oplus_app_suggestion_protocol_fix; then
    log_msg "app suggestions: dedicated module enabled, skipped"
else
    "$MODDIR/bin/app-suggestion-service.sh" "$MODDIR" &
    echo $! >"$MODDIR/app-suggestion.pid"
fi

# Preserve the original screen-off OVoice power policy with an events-log
# subscription. It blocks between real screen/process edges and therefore
# makes no periodic shell -> system_server IPC calls.
if [ -r "$MODDIR/voice-power-guard.pid" ]; then
    kill "$(cat "$MODDIR/voice-power-guard.pid" 2>/dev/null)" 2>/dev/null
    rm -f "$MODDIR/voice-power-guard.pid"
fi
# 唤醒已由用户关闭；不要再启动其事件订阅守护，避免无意义的 logcat 连接和 taskset 调用。
log_msg "voice power guard skipped: XiaoBu wakeup disabled"

# ============================================================================
# KGSL 显存前后台状态同步
#
# 移植包里没有任何组件写 /sys/class/kgsl/kgsl/proc/<pid>/state，导致高通自带的
# GPU 显存回收从未运行过一次（所有进程恒为 foreground、gpumem_reclaimed 全 0）。
# 详细定性与"为什么这里必须破例常驻"写在 bin/kgsl-state-sync.sh 的文件头。
# ============================================================================
if [ -r "$MODDIR/kgsl-state-sync.pid" ]; then
    kill "$(cat "$MODDIR/kgsl-state-sync.pid" 2>/dev/null)" 2>/dev/null
    rm -f "$MODDIR/kgsl-state-sync.pid"
fi
if [ -x "$MODDIR/bin/kgsl-state-sync.sh" ] && [ -d /sys/class/kgsl/kgsl/proc ]; then
    "$MODDIR/bin/kgsl-state-sync.sh" "$MODDIR" &
    echo $! >"$MODDIR/kgsl-state-sync.pid"
    log_msg "kgsl state sync started (page_alloc=$(($(cat /sys/class/kgsl/kgsl/page_alloc 2>/dev/null || echo 0) / 1048576))MB)"
fi

# ============================================================================
# 这里曾经有 protect_ui_memcg()：把桌面 / system_server / SurfaceFlinger 所在
# memcg 的 swappiness 与 app_score 压成 0。**已删除，2026-08-31。**
#
# 当时的动机是对的（动画期这三个进程每秒上万次主缺页），但做法是加法而不是还原：
# 拿到一台原厂 PKX110（SM8750 / 12G / ColorOS V16.1.0）逐项比对后发现，原厂
# 的 active / systemserver 组同样是 swappiness=100、app_score=300，并**不**做
# 这种保护，原厂桌面照样换出 699MB。
#
# 真正的病根是另一处：我们自己在 post-fs-data 里以 root 预建了
# /dev/memcg/apps/{active,systemserver,launcher}，只 chown 了目录没管里面的
# cgroup.procs，于是 system_server（system uid）搬进程时一直 EACCES，OPlus
# 那套按应用状态分组的机制从来没能生效。删掉预建之后，桌面/SystemUI 自动进
# active、system_server 自动进 systemserver，与原厂形态完全一致。
#
# 病根修好之后这段保护就没有存在理由了 —— 留着只会掩盖真实行为，也让以后再
# 出问题时分不清是原厂机制的表现还是我们的补丁在兜。
# ============================================================================


# ============================================================================
# 手电亮度调节：信箱守望
#
# SystemUI(platform_app) 写不了 led:torch_*/brightness —— 节点 chmod 0666 +
# chcon vendor_sysfs_graphics，再把 file/dir/lnk_file 三类权限全放行之后 open()
# 仍然 EACCES，且 dmesg 一条 avc 都不落（AOSP 对 appdomain 碰 sysfs 有
# dontaudit）。不再跟 SELinux 较劲，改成：Hook 把目标电流写进它自己的 DE 数据
# 目录，这里挂 inotifyd 听着，一变就由 root 落到两颗灯上。
#
# 常驻代价：一个阻塞在 read() 上的 inotifyd，不轮询，只有拖滑条时才醒。
# 目录等待放在后台子 shell 里，避免拖慢 service.sh 这条串行链。
# ============================================================================
if [ -r "$MODDIR/torch-level.pid" ]; then
    kill "$(cat "$MODDIR/torch-level.pid" 2>/dev/null)" 2>/dev/null
    rm -f "$MODDIR/torch-level.pid"
fi
if [ -f "$MODDIR/bin/torch_level_apply.sh" ] && [ -e /sys/class/leds/led:torch_0/brightness ]; then
    chmod 0755 "$MODDIR/bin/torch_level_apply.sh" 2>/dev/null
    (
        _dir=/data/user_de/0/com.android.systemui/files
        _i=0
        while [ ! -d "$_dir" ] && [ "$_i" -lt 60 ]; do sleep 2; _i=$((_i + 1)); done
        [ -d "$_dir" ] || exit 0
        inotifyd "$MODDIR/bin/torch_level_apply.sh" "$_dir:w" >/dev/null 2>&1 &
        echo $! >"$MODDIR/torch-level.pid"
    ) &
    log_msg "torch level watcher arming"
fi

log_msg "identity=$(getprop ro.product.brand)/$(getprop ro.product.name)/$(getprop ro.product.device)/$(getprop ro.product.model)"
log_msg "zygote_tango=$(getprop init.svc.zygote_tango) horae=$(getprop init.svc.horae) gameopt=$(getprop init.svc.gameopt_hal_service-1-0)"
log_msg "late service end"

# ============================================================================
# 环境光自适应：只做诊断，不从模块 service 中杀 lspd/system_server。
# LSPosed 的注入生命周期由 Zygisk/LSPosed 管理；在 service.sh 中手动杀这两个
# 进程会让 system_server 进入反复崩溃/重启，结果是所有 Hook（不仅是环境光）
# 一起失效。若本次冷启动未注入，应保留证据，交给下一次完整重启或手动诊断。
# ============================================================================
# Hook dexopt is performed once by customize.sh at installation. Recompiling
# the registered package on every boot needlessly wakes PackageManager and
# dex2oat while Launcher is still restoring its working set.

# 这只是一条**诊断**日志，却曾经用一个前台 sleep 8 把整条脚本卡住 8 秒。
# 挪进后台子 shell：它不影响任何后续步骤，也没人依赖它的结果。
# 不是守护进程——睡一次、读一次日志、写一行、退出。
(
    sleep 8
    latest_verbose=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
    if [ -n "$latest_verbose" ] && grep -q "AmbientColorSensorBridge: installed" "$latest_verbose"; then
        log_msg "ambient light bridge loaded"
    else
        log_msg "ambient light bridge not loaded this boot; no destructive recovery attempted"
    fi
) &

# ============================================================================
# 调优部分（原 coloros_port_tuning）：service 阶段 —— 已整体撤销
#
# 这里原本会在 boot_completed 后再睡 90 秒，然后把全局 / 根 memcg /
# apps 及其全部子分组的 swappiness 按 0/50 重写一遍，另外补写
# min_free_kbytes、watermark_scale_factor、watermark_boost_factor 与 KGSL
# 回收批量，最后还挂一个 120 秒的守卫等 apps/systemserver 出现再写一次 0。
#
# 全部删除，理由与 post-fs-data 那半边相同：22 个一加 BSP ko 已经在跑，
# /dev/memcg 下那套 swapd 参数由原厂 init.oplus.nandswap.sh 写，
# 按场景抬换出额度的权力交还 OSense。我们这套手调值是在跟原厂抢同一批旋钮，
# 留着只会互相覆盖，而且实机观察到最终稳定态本来就是原厂值（swappiness=100、
# watermark_scale_factor=100），说明这层覆盖早已是空转。
#
# 顺带删掉的还有那 90 秒 sleep —— 它唯一的作用就是给上面这套写入留交接窗口。
# ============================================================================

# HMBIRD 管理器：本机确无 hmbird_sched / sched_ext，开机后再压一次，
# 防止 OSense 在 boot_completed 之后把源手机默认值发布回来。
setprop sys.oplus.hmbird.manager.enable 0

# ============================================================================
# hybridswap 的两处修正已上移到本脚本开头（2026-08-29）。
#
# 原因：它们是**开机路径上的内存策略**，越早生效越好，可原先排在 900 多行
# 包管理/语音唤醒/LSPosed 诊断之后，实测要到开机 +79~82 秒才落地，而用户
# 在 +89 秒就解锁了 —— 等于整个开机换页高峰全程跑的是源机 12/16G 的门槛。
# service.sh 是一条串行脚本，谁排在前面谁先生效，这就是唯一的杠杆。
#
# 上移是安全的：那段本来就自带有界轮询，等的是
# /sys/block/zram0/hybridswap_enable 出现 "swapd enable" 且 swapd_pid 非 0，
# 也就是原厂 init.oplus.nandswap.sh 真正跑完的标志，与它在文件里的位置无关。
# 提前之后它会阻塞在轮询上而不是空等前面的 pm 命令，净收益约 25 秒。
# ============================================================================

log_msg "tuning handed back to stock: global=$(cat /proc/sys/vm/swappiness 2>/dev/null) root_memcg=$(cat /dev/memcg/memory.swappiness 2>/dev/null) apps=$(cat /dev/memcg/apps/memory.swappiness 2>/dev/null) min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null) watermark=$(cat /proc/sys/vm/watermark_scale_factor 2>/dev/null) zram2ufs=$(awk '/level 1 ub_zram2ufs_ratio/{print \$NF}' /dev/memcg/memory.swapd_memcgs_param 2>/dev/null) avail_buffers=[$(tr '\n' ' ' </dev/memcg/memory.avail_buffers 2>/dev/null)]"

# ============================================================================
# 联想 aispeech uuid 实验的安全网已撤（2026-08-29）
#
# 这里原本有一段开机 +90s 检查 ADSP 崩溃计数、超标就自动摘掉 lenovo_uuid_enable
# 的一次性任务。实验换了投递方式（改模块自带的 my_product/etc/OVMS_settings.xml
# 覆盖文件，而不是 mount --bind），uuid 现在原地可改可回滚、不需要重启，
# 也就不需要开机安全网了。开关文件 lenovo_uuid_enable 一并作废。
# ============================================================================
