#!/system/bin/sh

MODDIR=${0%/*}


. "$MODDIR/common.sh"

# ============================================================================
# 联想平板 Pro GT - ColorOS 基础修复 · service（开机完成后）阶段
# 实际修复与作用：
#   1) 停用移植 ROM 不兼容的 source-device 性能 HAL（perf2-hal、
#      vendor.perfservice），保留设备 performance 与 Thermal HAL；
#   2) 重载 thermal-engine，让 CPU 策略在模块挂载后生效；
#   3) 把每个 CPU policy 的 scaling_max_freq 一次性恢复为内核硬件上限
#      （充电/CPU 热限频由 thermal-engine_battery_0/2.conf 覆盖策略接管，
#      不再使用常驻守护脚本）；
#   4) 保持 Tango 32 位 zygote 停止，启用 horae，避免移植运行时不兼容；
#   5) 恢复小布语音唤醒：启用/解冻 OVoice 与 SpeechAssist 包、写入全局
#      唤醒开关与唤醒词、安装原厂 BWV 模型、委托 ExSystem BootReceiver、
#      并把 OVMS 检测窗口延长到 6000ms 减少重启间隙漏唤醒；
#   6) 开启电池健康入口（数据由 LSPosed BatteryHealthBridge 从 sysfs 桥接）；
#   7) 允许 Dolby Bridge 后台运行，避免原厂控制页仍在前台时服务被 app-idle
#      回收，导致 UI 仅写入设置但没有实时下发 DAP；
#   8) 清理已合并的旧 AON 独立包；启动 horae/gameopt；
#   9) 修正应用 memcg 仍为 swappiness=100 的合并回归：普通/冷后台 50，
#      活跃 UI、system_server 与 native system 禁止匿名页换出，并保留64MB水位；
#  10) 按需执行应用建议协议修复与序列号补齐。
# ============================================================================

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
log_msg "late service start"

if ! is_supported_device; then
    log_msg "unsupported device; skipped Lenovo Pad Pro GT service fixes"
    exit 0
fi

# Reassert once after Android property services are available. The source-ROM
# hmbird manager targets kernel facilities absent on this device and otherwise
# retries or falls back to cache killing. This is not a resident watcher.
#
# persist.sys.oplus.nandswap used to be pinned false here too. It is not a
# switch but a mirror of Settings.Secure.customize_ram_swap_state (see
# publish_ram_expansion_settings below), so pinning it only desynchronised the
# UI from the zram that was running anyway.
setprop sys.oplus.hmbird.manager.enable 0

# AOSP/ColorOS protects excessive cached processes for ten minutes after the
# first user unlock. The 12 GB source phone can absorb that burst, but on the
# 8 GB tablet it keeps the whole CE restore set resident while kswapd and AMS
# compete for roughly a minute. Newer AOSP defaults this grace period to zero.
# Apply that upstream behavior only to <=9 GB variants; 12 GB devices retain
# the stock ten-minute cache warmth and normal Android process semantics.
ram_kb=$(awk '/MemTotal:/{print $2; exit}' /proc/meminfo 2>/dev/null)
case "$ram_kb" in ''|*[!0-9]*) ram_kb=0 ;; esac
vm_swappiness=50
configure_oplus_memory_compat() {
    [ -d /proc/oplus_mem ] || return 0
    if [ -w /proc/oplus_mem/swappiness_para ]; then
        echo 'vm_swappiness=50' >/proc/oplus_mem/swappiness_para 2>/dev/null
        echo 'direct_swappiness=10' >/proc/oplus_mem/swappiness_para 2>/dev/null
    fi
    [ -w /proc/oplus_mem/dynamic_swappiness ] && \
        echo '50 1024 30 512' >/proc/oplus_mem/dynamic_swappiness 2>/dev/null
    [ -w /proc/oplus_mem/alloc_adjust_ctrl ] && \
        echo 0 >/proc/oplus_mem/alloc_adjust_ctrl 2>/dev/null
}
if [ "$ram_kb" -gt 0 ] && [ "$ram_kb" -le 9437184 ]; then
    vm_watermark=10
    cache_grace=$(device_config get activity_manager \
        no_kill_cached_processes_post_boot_completed_duration_millis 2>/dev/null)
    if [ "$cache_grace" != 0 ]; then
        device_config put activity_manager \
            no_kill_cached_processes_post_boot_completed_duration_millis 0 \
            >/dev/null 2>&1
    fi
    log_msg "8GB cache trim policy active: post-unlock grace=0ms"
else
    vm_watermark=20
    log_msg "12GB-class cache trim policy preserved"
fi

# Close the gap before the bounded memcg handoff below. Vendor init may have
# overwritten post-fs-data's VM values while Android was still starting.
echo "$vm_swappiness" >/proc/sys/vm/swappiness 2>/dev/null
configure_oplus_memory_compat
echo 65536 >/proc/sys/vm/min_free_kbytes 2>/dev/null
echo "$vm_watermark" >/proc/sys/vm/watermark_scale_factor 2>/dev/null
if [ -w /sys/class/kgsl/kgsl/page_reclaim_per_call ]; then
    echo 1024 >/sys/class/kgsl/kgsl/page_reclaim_per_call 2>/dev/null
fi

# PackageManager may rewrite LSPosed's module path after an APK update.  Pin it
# again once Android is up so the following cold boot already starts from the
# stable module copy, even before post-fs-data performs its own early check.
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

# The port ships the source-phone's perf HAL targetconfig (8-core pineapple).
# This module overlays a corrected SoC-696 (6-core) targetconfig so the perf
# HAL understands TB710FU's real topology and stops applying wrong source-device
# CPU caps.  Reload both perf HALs so they pick it up, and KEEP them running:
# the display composer / framework send CPU boost hints through perfservice on
# every large composition; leaving them stopped turns that path into a
# failed-AIDL IPC storm ("perf aidl service doesn't exist") that pegs
# system_server's binder threads — the actual cause of the post-boot CPU
# pressure, not the memory policy and not the powersave governor.
stop perf2-hal-1-0
stop vendor.perfservice
sleep 2
start vendor.perfservice
start perf2-hal-1-0
sleep 2
log_msg "reloaded perf HALs with corrected pineapple SoC-696 topology"

# thermal-engine can read its configuration before KernelSU finishes mounting
# the module overlay. Reload it once so it picks up the CPU-only policy.
stop thermal-engine
sleep 2
start thermal-engine
sleep 3
log_msg "reloaded thermal-engine after module mounts"

# Restore each policy's min/max to the hardware bounds of this device's own
# kernel once, and recover from the third-party "powersave" governor pin that
# locks every cluster to its minimum frequency after a long standby.  Runs once
# at boot only (no resident guard/daemon).
normalize_cpu
for policy in /sys/devices/system/cpu/cpufreq/policy*; do
    log_msg "cpu $(basename "$policy") gov=$(cat "$policy/scaling_governor" 2>/dev/null) min=$(cat "$policy/scaling_min_freq" 2>/dev/null) max=$(cat "$policy/scaling_max_freq" 2>/dev/null)"
done

# The port's tango translator repeatedly aborts on this tablet's 32-bit
# runtime. Keep the native secondary zygote stopped instead of respawning it.
stop zygote_tango
sleep 2
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
# 内存扩展：开机后的一次复核
#
# 真正的权威是 Settings.Secure.customize_ram_swap_state（开关）与
# customize_ram_swap_value（档位）—— 反编译 Athena 的 u3.i1 确认，
# persist.sys.oplus.nandswap 只是这两个键的派生镜像，由 u3.i1$a / u3.i1$b 两个
# ContentObserver 写出。post-fs-data 已经按同一套规则从 Secure 推导过一次镜像，
# 并把 zram 调成了用户选的档位。
#
# 这里只做两件 post-fs-data 做不了的事：
#   * settings 表要等 system_server 起来才能读写；
#   * 万一 ROM 的 init 在 post-fs-data 之后又把 zram 重新 swapon 上去，而用户其实
#     是关闭状态，这里还有一次纠正机会（受同样的已用量上限保护）。
#
# 不再有「恢复用户意图」那段逻辑。当时以为是 nandswap 自检把开关写回 false，
# sepolicy.rule 补齐放行后拒绝数确实降为 0，但开关照样回落 —— 因为那根本不是
# 自检，而是 observer 按 Secure 里的值重写镜像。既然现在以 Secure 为准，镜像被
# 谁重写都不再是问题，那段猜测性的回写整段删掉。
# ============================================================================
# 与 post-fs-data 同一份语义：persist.* 必须用 -p（同时落盘 /data/property），
# -n 只改内存，重启后会被磁盘上的旧值覆盖。写完回读校验。
set_persist_prop() {
    key="$1"; value="$2"
    [ "$(getprop "$key")" = "$value" ] && return 0
    resetprop -p "$key" "$value" 2>/dev/null
    [ "$(getprop "$key")" = "$value" ] && return 0
    log_msg "WARN: ram-expand: failed to set $key=$value (still '$(getprop "$key")')"
    return 1
}

# 「内存扩展」显示的必须是真正在服务 swap 的那个设备。装上 aclswap 之后 zram0 的
# disksize 归零、也不在 /proc/swaps 里，仍然去读它只会得到「已关闭」。
# 这里解析 /proc/swaps 找出实际设备，aclswap 优先。
active_swap_sysfs() {
    for dev in /dev/block/aclswap0 /dev/block/zram0; do
        if grep -q "^$dev " /proc/swaps 2>/dev/null; then
            echo "/sys/block/$(basename "$dev")"
            return 0
        fi
    done
    return 1
}

publish_ram_expansion_settings() {
    swap_sys=$(active_swap_sysfs)
    if [ -z "$swap_sys" ]; then
        # 一个 swap 都没挂：如实报告关闭，而不是沿用上一次的值。
        swap_sys=/sys/block/zram0
        [ -d "$swap_sys" ] || return 0
    fi
    swap_dev="/dev/block/$(basename "$swap_sys")"

    state=$(settings get secure customize_ram_swap_state 2>/dev/null | tr -d '\r')
    case "$state" in ''|null|NULL|*[!0-9]*) state="" ;; esac

    swap_on=0
    grep -q "^$swap_dev " /proc/swaps 2>/dev/null && swap_on=1

    # 复核：用户明确关掉了，却发现 swap 又被挂上，说明 init 在我们之后重建过。
    # state 未写过时不动 —— 那是「保留默认」，不是「关」。
    if [ "$state" = 0 ] && [ "$swap_on" = 1 ]; then
        used=$(awk -v d="$swap_dev" '$1 == d { print $4 }' /proc/swaps)
        case "$used" in ''|*[!0-9]*) used=0 ;; esac
        if [ "$used" -le 131072 ]; then
            if swapoff "$swap_dev" 2>/dev/null; then
                echo 1 >"$swap_sys/reset" 2>/dev/null
                swap_on=0
                log_msg "ram-expand: zram was re-enabled after post-fs-data; disabled again"
            fi
        else
            log_msg "ram-expand: toggle off but zram holds ${used}KB; cannot detach safely now"
        fi
    fi

    # 镜像属性在这里、也只在这里推导 —— post-fs-data 那时 init 还没 swapon 完，
    # 读到的大小是 0，据此推导会误判成「已关闭」。到 boot_completed 才是事实。
    # 规则与 Athena 的 u3.i1$a / u3.i1$b 两个 observer 完全一致，用户改设置时以
    # observer 为准，两边不会给出不同的答案。
    if [ "$swap_on" = 1 ]; then
        actual_bytes=$(cat "$swap_sys/disksize" 2>/dev/null)
        case "$actual_bytes" in ''|*[!0-9]*) actual_bytes=0 ;; esac
        curr=$(awk -v b="$actual_bytes" 'BEGIN{ printf "%d", b / 1073741824 }')
    else
        curr=0
    fi
    case "$curr" in ''|*[!0-9]*) curr=0 ;; esac
    [ "$curr" -gt 0 ] && mirror=true || mirror=false

    set_persist_prop persist.sys.oplus.nandswap "$mirror"
    set_persist_prop persist.sys.oplus.nandswap.swapsize "$curr"
    set_persist_prop persist.sys.oplus.nandswap.swapsize.curr "$curr"
    cfg=$(getprop persist.sys.oplus.nandswap.cfg)
    if [ "$curr" -gt 0 ] && [ -n "$cfg" ]; then
        actual_lvl=$(echo "$cfg" | tr ',' '\n' | awk -v g="$curr" '
            { gsub(/[^0-9]/, "", $0); if ($0 + 0 == g) { print NR - 1; exit } }')
        if [ -n "$actual_lvl" ]; then
            set_persist_prop persist.sys.oplus.nandswap.lvl "$actual_lvl"
        else
            # 实际大小不在档位表里：如实报告大小，但不谎报一个不存在的档位。
            log_msg "ram-expand: active ${curr}GiB is not one of [$cfg]; level left as-is"
        fi
    fi
    lvl=$(getprop persist.sys.oplus.nandswap.lvl)
    case "$lvl" in ''|*[!0-9]*) lvl="" ;; esac
    [ "$mirror" = true ] && record=1 || record=0

    # 「该功能在本机可用」，与开/关无关。Athena 的 u3.i1.s() 把它写到 Secure、值
    # 是字符串 "true"（Settings$Secure.putString(..., String.valueOf(true))）；这句
    # 原本被 u3.i1.q() 的能力门挡着，Hook 打开门之后 Athena 自己也会写，这里代写
    # 一次只是不必等它，值与格式照抄，重复写是幂等的。
    [ "$(settings get secure nandswap_ui_feature_state 2>/dev/null | tr -d '\r')" = true ] ||
        settings put secure nandswap_ui_feature_state true >/dev/null 2>&1
    [ "$(settings get global record_oplus_nandswap 2>/dev/null | tr -d '\r')" = "$record" ] ||
        settings put global record_oplus_nandswap "$record" >/dev/null 2>&1
    if [ -n "$lvl" ] &&
       [ "$(settings get global record_oplus_nandswap_lvl 2>/dev/null | tr -d '\r')" != "$lvl" ]; then
        settings put global record_oplus_nandswap_lvl "$lvl" >/dev/null 2>&1
    fi
    log_msg "ram-expand: mirrors published from $(basename "$swap_sys") (secure state=${state:-unset}, swap_on=$swap_on, size=${curr}GiB, lvl=${lvl:-unset})"
    return 0
}

# ============================================================================
# aclswap 写回驱动
#
# 光有 writeback 能力不够——必须有人周期性地把冷页推出去，否则压缩池照样只涨
# 不落。原厂 HybridSwap 就是这么干的，这里补上等价的那一环。
#
# 这是本模块少有的常驻循环，理由是它本质上就是周期性的：每 5 分钟醒一次，读三
# 个 sysfs 文件，绝大多数时候什么都不做就继续睡。判据是压缩池占用超过上限的
# 六成才动手，且只写回 idle 超过 ACLSWAP_IDLE_AGE 秒的页（按页面年龄，靠模块编
# 入的 CONFIG_ZRAM_MEMORY_TRACKING 支持；没有它 idle 只接受 "all"，那等于把热
# 页也一起推到闪存）。
#
# 闪存写入必须封顶：writeback_limit 每轮重新发放固定额度，写完即止。不设限的
# 话一个内存压力大的下午就能写掉几十 GB。
# ============================================================================
ACLSWAP_SYS=/sys/block/aclswap0
ACLSWAP_DEV=/dev/block/aclswap0
# 参数按实测调整过一轮：最初 300 秒巡检 + 600 秒冷页判据，在连开 24 个应用的
# 压测里一次都没来得及触发，池子直接顶到上限，换出开始失败，反而比不设限更糟
# （psi_mem 22.7s -> 40.4s）。写回必须比压力上涨更快才有意义。
ACLSWAP_IDLE_AGE=300          # 秒；这么久没被访问过的页才算冷
ACLSWAP_CYCLE=60              # 秒；巡检间隔
ACLSWAP_WB_PAGES_PER_CYCLE=65536   # 每轮最多写回 65536 页 = 256MB
ACLSWAP_TRIGGER_PCT=60        # 池子占到上限这个百分比才触发

aclswap_active() {
    [ -d "$ACLSWAP_SYS" ] || return 1
    grep -q "^$ACLSWAP_DEV " /proc/swaps 2>/dev/null
}

aclswap_writeback_once() {
    limit=$(cat "$ACLSWAP_SYS/mem_limit" 2>/dev/null)
    used=$(awk '{print $3}' "$ACLSWAP_SYS/mm_stat" 2>/dev/null)
    case "$limit" in ''|0|*[!0-9]*) return 0 ;; esac
    case "$used" in ''|*[!0-9]*) return 0 ;; esac
    threshold=$(awk -v l="$limit" -v p="$ACLSWAP_TRIGGER_PCT" 'BEGIN{ printf "%.0f", l * p / 100 }')
    [ "$used" -gt "$threshold" ] || return 0

    echo 1 >"$ACLSWAP_SYS/writeback_limit_enable" 2>/dev/null
    echo "$ACLSWAP_WB_PAGES_PER_CYCLE" >"$ACLSWAP_SYS/writeback_limit" 2>/dev/null
    echo "$ACLSWAP_IDLE_AGE" >"$ACLSWAP_SYS/idle" 2>/dev/null
    echo idle >"$ACLSWAP_SYS/writeback" 2>/dev/null

    after=$(awk '{print $3}' "$ACLSWAP_SYS/mm_stat" 2>/dev/null)
    case "$after" in ''|*[!0-9]*) after=$used ;; esac
    if [ "$after" -lt "$used" ]; then
        log_msg "aclswap: wrote back $(( (used - after) / 1048576 ))MB from the pool (now $(( after / 1048576 ))MB, bd=$(awk '{print $1}' "$ACLSWAP_SYS/bd_stat" 2>/dev/null) pages on flash)"
    fi
    return 0
}

# 必须 setsid 脱离进程组：KernelSU 的 service.sh 跑完退出时会回收整个进程组，
# 单纯 `... &` 的子进程会被一起带走。首次实测就是这样——日志写着“驱动已启动”，
# 而 writeback_limit_enable 始终是 0，进程列表里也找不到它。
start_aclswap_writeback_driver() {
    aclswap_active || return 0
    driver="$MODDIR/bin/aclswap-writeback.sh"
    [ -x "$driver" ] || chmod 0755 "$driver" 2>/dev/null
    if [ -f "$driver" ]; then
        setsid "$driver" "$MODDIR" </dev/null >/dev/null 2>&1 &
        log_msg "aclswap: writeback driver started (age=${ACLSWAP_IDLE_AGE}s cycle=${ACLSWAP_CYCLE}s cap=${ACLSWAP_WB_PAGES_PER_CYCLE}pages/cycle)"
    else
        log_msg "WARN: aclswap: writeback driver script missing"
    fi
}

# init 会在 post-fs-data 之后按 fstab 把 zram0 重新挂回来，而且用的是高优先级
# （实测 32758）。两个 swap 同时在线时内核只喂优先级高的那个，于是带写回的
# aclswap 形同虚设。这里在开机完成后把它摘掉——此时它刚挂上不久，用量很小。
retire_stock_zram_for_aclswap() {
    aclswap_active || return 0
    grep -q '^/dev/block/zram0 ' /proc/swaps 2>/dev/null || return 0
    used=$(awk '$1 == "/dev/block/zram0" { print $4 }' /proc/swaps)
    case "$used" in ''|*[!0-9]*) used=0 ;; esac
    if [ "$used" -gt 262144 ]; then
        log_msg "WARN: aclswap: stock zram0 holds ${used}KB; leaving it mounted rather than forcing those pages back"
        return 1
    fi
    if swapoff /dev/block/zram0 2>/dev/null; then
        echo 1 >/sys/block/zram0/reset 2>/dev/null
        log_msg "aclswap: retired stock zram0 (held ${used}KB); aclswap is now the only swap"
        return 0
    fi
    log_msg "WARN: aclswap: could not swapoff stock zram0"
    return 1
}

# post-fs-data 里 aclswap 任何一步失败都会静默回落到原厂标准 zram：系统照常可
# 用，只是没有写回。这种情况必须在日志里留一条明显的记录，否则它只会表现为
# 「最近好像又变卡了」，而 action.sh 的输出里看不出任何异常。
if ! aclswap_active; then
    log_msg "WARN: aclswap: not in /proc/swaps; running on stock zram with no writeback"
fi

# 顺序有意为之：先让 aclswap 成为唯一 swap，再发布镜像，否则 UI 上报的会是
# 那个马上就要被摘掉的 zram0。
aclswap_retire_attempts=0
while [ "$aclswap_retire_attempts" -lt 6 ]; do
    retire_stock_zram_for_aclswap && break
    aclswap_retire_attempts=$((aclswap_retire_attempts + 1))
    toybox sleep 20
done

publish_ram_expansion_settings


start_aclswap_writeback_driver


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

# The port ships the genuine OVoice service but the source ROM leaves its
# companion SpeechAssist package disabled. Keep the user-facing XiaoBu path
# available and restore the same global switches used by the source ROM.
ensure_voice_wakeup() {
    for package in com.oplus.ovoicemanager.wakeup com.heytap.speechassist; do
        if pm enable --user 0 "$package" >/dev/null 2>&1; then
            log_msg "voice wake package enabled package=$package"
        else
            log_msg "voice wake package enable failed package=$package"
        fi
        if cmd package unstop --user 0 "$package" >/dev/null 2>&1; then
            log_msg "voice wake package unstopped package=$package"
        else
            log_msg "voice wake package unstop failed package=$package"
        fi
    done
    for key in hotword_detection_enabled voice_to_wakeup; do
        current=$(settings get global "$key" 2>/dev/null | tr -d '\r')
        if [ "$current" != 1 ]; then
            settings put global "$key" 1 >/dev/null 2>&1
            log_msg "voice wake global enabled key=$key"
        fi
    done
    wakeup_word=$(settings get global wakeup_word 2>/dev/null | tr -d '\r')
    case "$wakeup_word" in
        ''|null|NULL|unknown|UNKNOWN)
            settings put global wakeup_word '小布小布' >/dev/null 2>&1
            log_msg "voice wake word restored"
            ;;
    esac

    voice_model_dir=/data/user/0/com.oplus.ovoicemanager.wakeup/files
    install_voice_model() {
        voice_model_payload="$1"
        voice_model_target="$2"
        voice_model_label="$3"
        if [ -r "$voice_model_payload" ] && [ -d "$voice_model_dir" ]; then
            voice_model_hash=$(sha256sum "$voice_model_payload" 2>/dev/null | awk '{print $1}')
            installed_model_hash=$(sha256sum "$voice_model_target" 2>/dev/null | awk '{print $1}')
            if [ "$voice_model_hash" != "$installed_model_hash" ]; then
                voice_model_tmp="$voice_model_target.tmp"
                rm -f "$voice_model_tmp" 2>/dev/null
                if cp "$voice_model_payload" "$voice_model_tmp" 2>/dev/null; then
                    voice_model_owner=$(stat -c '%u:%g' "$voice_model_dir" 2>/dev/null || echo 10108:10108)
                    chown "$voice_model_owner" "$voice_model_tmp" 2>/dev/null
                    chmod 0600 "$voice_model_tmp" 2>/dev/null
                    if [ -r "$voice_model_dir/profileInstalled" ]; then
                        chcon --reference="$voice_model_dir/profileInstalled" "$voice_model_tmp" 2>/dev/null
                    fi
                    if mv -f "$voice_model_tmp" "$voice_model_target" 2>/dev/null; then
                        log_msg "$voice_model_label installed sha256=$voice_model_hash bytes=$(stat -c '%s' "$voice_model_target" 2>/dev/null)"
                    else
                        log_msg "$voice_model_label install failed"
                    fi
                else
                    log_msg "$voice_model_label copy failed"
                fi
            else
                log_msg "$voice_model_label verified sha256=$installed_model_hash"
            fi
        else
            log_msg "$voice_model_label payload or OVoice data directory missing"
        fi
    }

    # The vendor UIM is readable by root but is labelled vendor_configs_file,
    # so an OVoice app process cannot reliably open it. Stage the native model
    # in app-private storage and make the Hook read this copy instead.
    install_voice_model \
        "$MODDIR/payload/voice/sm8_gr3UsMFCN230612eAIv34ENPUv4Float.uim" \
        "$voice_model_dir/lenovo.uim" \
        "voice wake native Lenovo UIM"

    # Keep the phone model available for explicit offline analysis, but never
    # leave a stale one-shot probe armed across boots.
    install_voice_model \
        "$MODDIR/payload/voice/oppo21001_20211124.bin" \
        "$voice_model_dir/codex-qcom-oppo21001.bin" \
        "voice wake Qualcomm diagnostic model"
    for probe_file in \
        /data/local/tmp/coloros_cdsp_breeno_probe_once \
        /data/local/tmp/coloros_cdsp_capture_no_mmap_once \
        /data/local/tmp/coloros_cdsp_hotword_qcom_probe_once \
        /data/local/tmp/coloros_cdsp_qcom_probe_once \
        /data/local/tmp/coloros_cdsp_qc_probe_once; do
        if [ -e "$probe_file" ]; then
            rm -f "$probe_file" 2>/dev/null
            log_msg "cleared stale voice wake probe flag=$probe_file"
        fi
    done

    # Re-enter the stock boot receiver after package state is repaired.  It
    # sends the ExSystem status broadcast; ExSystem then binds
    # OplusAppServicesManagerClient, whose onBind() starts the foreground
    # service and the genuine OVoice manager.  Starting OVoice directly from
    # this late service bypasses that bind and triggers Android's foreground
    # service timeout.
    if pidof com.oplus.ovoicemanager.wakeup >/dev/null 2>&1; then
        log_msg "voice wake process already running; stock boot lifecycle preserved"
    else
        cmd package unstop --user 0 com.oplus.ovoicemanager.wakeup >/dev/null 2>&1
        if am broadcast --user 0 \
            -a android.intent.action.BOOT_COMPLETED \
            -n com.oplus.ovoicemanager.wakeup/.service.OVSBootupReceiver >/dev/null 2>&1; then
            log_msg "voice wake process absent; stock BootReceiver re-entered"
        else
            log_msg "voice wake stock BootReceiver request failed"
        fi
    fi
}

ensure_voice_wakeup

# Lengthen each BWV listening window from 2600ms to 6000ms so the engine
# restarts far less often and the wake word is not missed during restart gaps.
ovms_settings=/my_product/etc/OVMS_settings.xml
if [ -f "$MODDIR/my_product/etc/OVMS_settings.xml" ] && [ -f "$ovms_settings" ]; then
    if ! cmp -s "$MODDIR/my_product/etc/OVMS_settings.xml" "$ovms_settings"; then
        mount --bind "$MODDIR/my_product/etc/OVMS_settings.xml" "$ovms_settings" 2>/dev/null \
            || cp "$MODDIR/my_product/etc/OVMS_settings.xml" "$ovms_settings" 2>/dev/null
        log_msg "OVMS detection timeout raised to 6000ms"
    fi
fi

# Expose the ColorOS battery-health entry; its data is bridged by the
# LSPosed BatteryHealthBridge from the real power-supply sysfs.
if [ "$(settings get system os.charge.settings.batterysettings.batteryhealth 2>/dev/null | tr -d '\r')" != 1 ]; then
    settings put system os.charge.settings.batterysettings.batteryhealth 1 >/dev/null 2>&1
    log_msg "battery health entry enabled"
fi

log_msg "stable LSPosed Hook payload verified"
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
if [ -x "$MODDIR/bin/voice-power-guard.sh" ]; then
    "$MODDIR/bin/voice-power-guard.sh" "$MODDIR" &
    echo $! >"$MODDIR/voice-power-guard.pid"
    log_msg "event-driven voice power guard started"
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

sleep 8
latest_verbose=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
if [ -n "$latest_verbose" ] && grep -q "AmbientColorSensorBridge: installed" "$latest_verbose"; then
    log_msg "ambient light bridge loaded"
else
    log_msg "ambient light bridge not loaded this boot; no destructive recovery attempted"
fi

# ============================================================================
# 调优部分（原 coloros_port_tuning）：service 阶段
# 实机发现合并版只写 active/systemserver，apps 根分组和每个应用子分组仍为
# swappiness=100，导致 Launcher/SystemUI/常用应用累计数百 MB Swap。长待机
# 现场进一步确认 system_server/SystemUI/SurfaceFlinger 分别已有约243/154/43MB
# Swap，唤醒时集中重大缺页。修正zsmalloc/CMA争抢后，稳定态收敛为普通/冷后台=50，
# active/systemserver/native-system=0；缓存进程上限由 system_server Hook 在
# 8GB 机型从源手机的96压到48，12GB机型保持原值。
# ============================================================================
# post-fs-data already applies the final 0/50 split and keeps clamping newly
# created first-unlock groups. Retain a bounded overlap before handoff so late
# CE groups cannot regain ColorOS' high defaults; no all-zero phase remains.
log_msg "tuning settle window start: maintaining split memcg policy for 90s"
sleep 90

# OSense may publish its phone defaults after boot_completed. Reassert the
# tested standard-zram caps once at handoff; the kernel hard ceiling remains
# effective afterwards, so no resident policy watcher is needed.
configure_oplus_memory_compat
setprop sys.oplus.hmbird.manager.enable 0

# Do not terminate post-fs-data's bounded guard merely because Android has
# been up for 90 seconds. The user may perform the first unlock later; the
# helper exits by itself after four minutes，覆盖较晚发生的首次解锁；它只改变
# 进程后续分配的 memcg 归属，不批量搬运已经计费的页面，也不常驻。

wait_count=0
while [ ! -w /dev/memcg/apps/memory.swappiness ] &&
      [ "$wait_count" -lt 60 ]; do
    toybox sleep 1
    wait_count=$((wait_count + 1))
done
echo "$vm_swappiness" >/proc/sys/vm/swappiness 2>/dev/null
echo "$vm_swappiness" >/dev/memcg/memory.swappiness 2>/dev/null
for memcg_file in /dev/memcg/apps/memory.swappiness \
        /dev/memcg/apps/*/memory.swappiness; do
    [ -w "$memcg_file" ] || continue
    case "$memcg_file" in
        */active/memory.swappiness|*/launcher/memory.swappiness)
            echo 0 >"$memcg_file" 2>/dev/null
            ;;
        */systemserver/memory.swappiness)
            echo 0 >"$memcg_file" 2>/dev/null
            ;;
        */inactive/memory.swappiness)
            echo "$vm_swappiness" >"$memcg_file" 2>/dev/null
            ;;
        *)
            echo "$vm_swappiness" >"$memcg_file" 2>/dev/null
            ;;
    esac
done
if [ -w /dev/memcg/system/memory.swappiness ]; then
    echo 0 >/dev/memcg/system/memory.swappiness 2>/dev/null
fi

# On this port /dev/memcg/apps/systemserver may be created only after the first
# user unlock, later than service.sh.  A bounded 1-second waiter fixes it once
# and exits; it is not a permanent tuning daemon.
(
    attempt=0
    while [ "$attempt" -lt 120 ]; do
        if [ -w /dev/memcg/apps/systemserver/memory.swappiness ]; then
            echo 0 >/dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null
            log_msg "late systemserver memcg protected from swap"
            exit 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    log_msg "late systemserver memcg one-shot wait timed out"
) &

# 64MB is only 0.8% of RAM. The watermark is RAM-aware: 10 on the 8GB model
# prevents premature background scanning, while 12GB retains the prior 20.
echo 65536 >/proc/sys/vm/min_free_kbytes 2>/dev/null
echo "$vm_watermark" >/proc/sys/vm/watermark_scale_factor 2>/dev/null
echo 0 >/proc/sys/vm/watermark_boost_factor 2>/dev/null
if [ -w /sys/class/kgsl/kgsl/page_reclaim_per_call ]; then
    echo 1024 >/sys/class/kgsl/kgsl/page_reclaim_per_call 2>/dev/null
fi

log_msg "tuning ready: global=$(cat /proc/sys/vm/swappiness 2>/dev/null) root=$(cat /dev/memcg/memory.swappiness 2>/dev/null) apps=$(cat /dev/memcg/apps/memory.swappiness 2>/dev/null) min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null) watermark=$(cat /proc/sys/vm/watermark_scale_factor 2>/dev/null) kgsl_reclaim=$(cat /sys/class/kgsl/kgsl/page_reclaim_per_call 2>/dev/null) active=$(cat /dev/memcg/apps/active/memory.swappiness 2>/dev/null) systemserver=$(cat /dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null) inactive=$(cat /dev/memcg/apps/inactive/memory.swappiness 2>/dev/null)"
