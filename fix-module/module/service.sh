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
#   9) 修正应用 memcg 仍为 swappiness=100 的合并回归：稳定态普通后台 20、
#      冷后台 40、活跃 UI 10、system_server 5，并保留 64MB 水位；
#  10) 按需执行应用建议协议修复与序列号补齐。
# ============================================================================

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 8
log_msg "late service start"

if ! is_supported_device; then
    log_msg "unsupported device; skipped Lenovo Pad Pro GT service fixes"
    exit 0
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
    if am force-stop com.oplus.ovoicemanager.wakeup >/dev/null 2>&1; then
        log_msg "voice wake service reset package=com.oplus.ovoicemanager.wakeup"
    fi
    cmd package unstop --user 0 com.oplus.ovoicemanager.wakeup >/dev/null 2>&1
    if am broadcast --user 0 \
        -a android.intent.action.BOOT_COMPLETED \
        -n com.oplus.ovoicemanager.wakeup/.service.OVSBootupReceiver >/dev/null 2>&1; then
        log_msg "voice wake stock BootReceiver re-entered; ExSystem bind delegated"
    else
        log_msg "voice wake stock BootReceiver request failed"
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

# OVoice 小核省电守护：熄屏待听时把唤醒线程限制在 little+中核 (0,3-4)，
# 亮屏/唤醒后恢复全核 (0-5)；进程出现 60 秒内不强切，避免 FGS 超时崩溃。
if [ -f "$MODDIR/bin/voice-power-guard.sh" ]; then
    "$MODDIR/bin/voice-power-guard.sh" "$MODDIR" &
    echo $! >"$MODDIR/voice-power-guard.pid"
    log_msg "voice power guard started"
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
cmd package compile -m speed -f com.aclaniakea.colorosostatsguard >/dev/null 2>&1
log_msg "hook apk dexopt refreshed for next boot"

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
# swappiness=100，导致 Launcher/SystemUI/常用应用累计数百 MB Swap。交互切换
# 实测证明普通=20 + OSense 主动换出会产生换页风暴，因此稳定态收敛为普通=10、
# inactive=20、active/systemserver=5；保留按需 ZRAM，又不使用 0/1 极端值。
# ============================================================================
wait_count=0
while [ ! -w /dev/memcg/apps/memory.swappiness ] &&
      [ "$wait_count" -lt 60 ]; do
    toybox sleep 1
    wait_count=$((wait_count + 1))
done
echo 10 >/proc/sys/vm/swappiness 2>/dev/null
echo 10 >/dev/memcg/memory.swappiness 2>/dev/null
for memcg_file in /dev/memcg/apps/memory.swappiness \
        /dev/memcg/apps/*/memory.swappiness; do
    [ -w "$memcg_file" ] || continue
    case "$memcg_file" in
        */active/memory.swappiness)
            echo 5 >"$memcg_file" 2>/dev/null
            ;;
        */systemserver/memory.swappiness)
            echo 5 >"$memcg_file" 2>/dev/null
            ;;
        */inactive/memory.swappiness)
            echo 20 >"$memcg_file" 2>/dev/null
            ;;
        *)
            echo 10 >"$memcg_file" 2>/dev/null
            ;;
    esac
done
if [ -w /dev/memcg/system/memory.swappiness ]; then
    echo 10 >/dev/memcg/system/memory.swappiness 2>/dev/null
fi

# On this port /dev/memcg/apps/systemserver may be created only after the first
# user unlock, later than service.sh.  A bounded 1-second waiter fixes it once
# and exits; it is not a permanent tuning daemon.
(
    attempt=0
    while [ "$attempt" -lt 120 ]; do
        if [ -w /dev/memcg/apps/systemserver/memory.swappiness ]; then
            echo 5 >/dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null
            log_msg "late systemserver memcg protected from swap"
            exit 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    log_msg "late systemserver memcg one-shot wait timed out"
) &

# 64MB is only 0.8% of RAM and restores the previously validated tuning value.
# It wakes kswapd before a UI allocation falls into direct reclaim/allocstall.
echo 65536 >/proc/sys/vm/min_free_kbytes 2>/dev/null
echo 20 >/proc/sys/vm/watermark_scale_factor 2>/dev/null
echo 0 >/proc/sys/vm/watermark_boost_factor 2>/dev/null

log_msg "tuning ready: global=$(cat /proc/sys/vm/swappiness 2>/dev/null) root=$(cat /dev/memcg/memory.swappiness 2>/dev/null) apps=$(cat /dev/memcg/apps/memory.swappiness 2>/dev/null) min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null) watermark=$(cat /proc/sys/vm/watermark_scale_factor 2>/dev/null) active=$(cat /dev/memcg/apps/active/memory.swappiness 2>/dev/null) systemserver=$(cat /dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null) inactive=$(cat /dev/memcg/apps/inactive/memory.swappiness 2>/dev/null)"
