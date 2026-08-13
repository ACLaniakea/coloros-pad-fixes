#!/system/bin/sh

MODDIR=${0%/*}


. "$MODDIR/common.sh"

# ============================================================================
# 联想平板 Pro GT - ColorOS 基础修复 · service（开机完成后）阶段
# 实际修复与作用：
#   1) 停用移植 ROM 不兼容的 source-device 性能 HAL（perf2-hal、
#      vendor.perfservice），保留 OPlus 性能 HAL，避免 CPU 频点被错误封顶；
#   2) 重载 thermal-engine，让 CPU 策略在模块挂载后生效；
#   3) 把每个 CPU policy 的 scaling_max_freq 恢复为内核硬件上限，并常驻
#      cpu-limit-guard 防止运行期被再次压低；
#   4) 保持 Tango 32 位 zygote 停止，启用 horae，避免移植运行时不兼容；
#   5) 恢复小布语音唤醒：启用/解冻 OVoice 与 SpeechAssist 包、写入全局
#      唤醒开关与唤醒词、安装原厂 BWV 模型、委托 ExSystem BootReceiver、
#      并把 OVMS 检测窗口延长到 6000ms 减少重启间隙漏唤醒；
#   6) 开启电池健康入口（数据由 LSPosed BatteryHealthBridge 从 sysfs 桥接）；
#   7) 清理已合并的旧 AON 独立包；启动 horae/gameopt；
#   8) 按需执行应用建议协议修复与序列号补齐。
# ============================================================================

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 8
log_msg "late service start"

if ! is_supported_device; then
    log_msg "unsupported device; skipped Lenovo Pad Pro GT service fixes"
    exit 0
fi

# The port's incompatible source-device performance HALs
# persistently apply source-device CPU caps that do not match TB710FU's
# kernel frequency table. Keep the scheduler and thermal stack running.
stop perf2-hal-1-0
stop vendor.perfservice
sleep 2
log_msg "stopped incompatible source-device performance HALs; kept OPlus performance HAL for app compatibility"

# thermal-engine can read its configuration before KernelSU finishes mounting
# the module overlay. Reload it once so it picks up the CPU-only policy.
stop thermal-engine
sleep 2
start thermal-engine
sleep 3
log_msg "reloaded thermal-engine after module mounts"

# Restore each policy to the maximum exposed by this device's own kernel once.
for policy in /sys/devices/system/cpu/cpufreq/policy*; do
    max_file="$policy/scaling_max_freq"
    hw_max_file="$policy/cpuinfo_max_freq"
    [ -r "$hw_max_file" ] && [ -e "$max_file" ] || continue
    hw_max=$(cat "$hw_max_file" 2>/dev/null)
    case "$hw_max" in
        ''|*[!0-9]*) continue ;;
    esac
    chmod 0644 "$max_file" 2>/dev/null
    echo "$hw_max" >"$max_file" 2>/dev/null
    log_msg "cpu $(basename "$policy") max=$(cat "$max_file" 2>/dev/null)"
done

"$MODDIR/bin/cpu-limit-guard.sh" "$MODDIR" &
echo $! >"$MODDIR/cpu-limit-guard.pid"

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

log_msg "LSPosed Hook APK is external; KernelSU module payload contains no APK"
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
        --es from coloros_port_base_fix >/dev/null 2>&1
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
# 环境光自适应：根治注入时序，不再软重启 zygote。
# system_server 开机最早拉起，完整重启后偶发撞上 LSPosed 注入时 Hook APK
# dex 尚未优化的 I/O 竞态。开机后再触发一次 dexopt，保证下一次完整重启的
# 首次注入即成功（安装时 customize.sh 已预编译过一遍）。
# ============================================================================
cmd package compile -m speed -f com.aclaniakea.colorosostatsguard >/dev/null 2>&1
log_msg "hook apk dexopt refreshed for next boot"

sleep 5
latest_verbose=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
if [ -n "$latest_verbose" ] && grep -q "AmbientColorSensorBridge: installed" "$latest_verbose"; then
    log_msg "ambient light bridge loaded"
else
    log_msg "ambient light bridge not loaded this boot; dex precompiled, next full reboot will load it"
fi
