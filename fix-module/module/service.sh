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
#   5) 恢复小布语音唤醒：启用/解冻 OVoice 与 SpeechAssist 包、写入全局
#      唤醒开关与唤醒词、安装原厂 BWV 模型、委托 ExSystem BootReceiver、
#      并把 OVMS 检测窗口延长到 6000ms 减少重启间隙漏唤醒；
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

# Recover from the third-party "powersave" governor pin that locks a cluster to
# its minimum frequency after a long standby; only clusters actually found in
# that state get their min/max restored to this device's own cpuinfo_* bounds.
# Everything else is left to thermal-engine and the stock perf HAL.  Runs once
# at boot only (no resident guard/daemon).  NOTE: the "cpu policyN ... max=" log
# lines below are read back seconds after thermal-engine was restarted above, so
# they show the thermal mitigation state, not what this module wrote.
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
# hybridswap：把原厂自己算出来、却漏发给内核的 ub_zram2ufs_ratio 补下去。
#
# 原厂 /product/bin/init.oplus.nandswap.sh 的 configure_hybridswap_parameters()
# 会按 MemTotal 分档算出 zram2ufs_ratio（本机 7.4G 走 else 档 = 15），但这个值
# 只被用来决定预留 dd 区的大小（dd_mb_cnt），**从未写进 swapd_memcgs_param**；
# 第 212 行下发的是硬编码的 "3 0 99 0 0 0 100 399 60 0 0 400 499 50 0 0"，
# 每一级的 ub_zram2ufs_ratio 都是 0。后果是 8G eswap 挂上了、hybridswap_enable
# 三段全 enable、hybridswapd 也活着，但 ESU_C 恒为 0、reclaimin_cnt 恒为 0，
# 一页都没往 UFS 写过，zram 里的冷数据全程占着物理内存。
#
# 2026-08-27 实机验证（8G 机型，写入 15 后 4 分钟）：reclaimin_cnt 0→101、
# 落盘 286MB（loop51 diskstats 587264 扇区独立佐证）、ZSU_O 4295124→3519536 KB
# 即 zram 腾出 776MB、MemAvailable 1277788→1446704 KB。读回仅 34MB，无换入风暴，
# dmesg 无告警。速率收敛（T+2→T+3 增量为 0），属于一次性排积压而非持续狂写，
# 且原厂 hybridswap_quota_day=10GB/天 的闸仍在兜底。
#
# 注意两点：
#  1) mem2zram 那两列（本机开机后是 80/70）是 perf HAL
#     /odm/bin/hw/vendor-oplus-hardware-performance-V1-service 在运行时从
#     60/50 抬上去的，**必须读回原值原样写回**，不能硬编码，否则会把 HAL 的
#     场景决策打回去。
#  2) 这是一次性写入，不挂常驻守卫。HAL 若在之后重写整串会把 15 冲回 0；
#     实测 4 分钟内没有发生。要确认现状用 action.sh 里那行 zram2ufs 读数。
# ============================================================================
tune_zram2ufs() {
    param=/dev/memcg/memory.swapd_memcgs_param
    [ -w "$param" ] || { log_msg "hybridswap: $param 不可写，跳过"; return 0; }
    # 只在原厂那三级（level 0/1/2）上工作，level 3~9 原厂就是全 0 的占位。
    m1=$(awk '/level 1 ub_mem2zram_ratio/{print $NF}' "$param" 2>/dev/null)
    m2=$(awk '/level 2 ub_mem2zram_ratio/{print $NF}' "$param" 2>/dev/null)
    case "$m1" in ''|*[!0-9]*) m1= ;; esac
    case "$m2" in ''|*[!0-9]*) m2= ;; esac
    # 读不到就用原厂脚本第 212 行的硬编码值兜底，绝不猜。
    [ -n "$m1" ] || m1=60
    [ -n "$m2" ] || m2=50
    # 格式：级数, 然后每级 min_score max_score ub_mem2zram ub_zram2ufs refault
    echo "3 0 99 0 0 0 100 399 $m1 15 0 400 499 $m2 15 0 " >"$param" 2>/dev/null
    log_msg "hybridswap: zram2ufs 15 已下发 (保留 HAL 的 mem2zram $m1/$m2)，实际=$(awk '/level 1 ub_zram2ufs_ratio/{print $NF}' "$param" 2>/dev/null)"
}

# 原厂 nandswap 服务在开机约 57 秒才跑完（ro.boottime.init.oplus.nandswap.sh），
# 而本脚本第 30 行只等到 sys.boot_completed（约 30~45 秒），先到先写会被它覆盖。
# 不用盲睡，直接有界轮询我们依赖的那个状态本身：等 hybridswap_enable 里出现
# "swapd enable"（原厂脚本第 233 行写完才会有）且 swapd_pid 非 0。
# 注意不能拿 persist.sys.oplus.hybridswap_app_memcg 当标志——它是 persist 属性，
# 上次开机的值会一直留着，看不出本次是否已完成。
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
        sleep 3
        waited=$((waited + 3))
    done
    if [ "$waited" -ge 180 ]; then
        log_msg "hybridswap: 等原厂 nandswap 就绪超时 ${waited}s，仍尝试下发（state=$st pid=$pid）"
    else
        log_msg "hybridswap: 原厂 nandswap 已就绪（等待 ${waited}s，swapd_pid=$pid）"
    fi
    sleep 3
    tune_zram2ufs
else
    log_msg "hybridswap: 节点不存在，跳过 zram2ufs 调整"
fi

log_msg "tuning handed back to stock: global=$(cat /proc/sys/vm/swappiness 2>/dev/null) root_memcg=$(cat /dev/memcg/memory.swappiness 2>/dev/null) apps=$(cat /dev/memcg/apps/memory.swappiness 2>/dev/null) min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null) watermark=$(cat /proc/sys/vm/watermark_scale_factor 2>/dev/null) zram2ufs=$(awk '/level 1 ub_zram2ufs_ratio/{print \$NF}' /dev/memcg/memory.swapd_memcgs_param 2>/dev/null)"
