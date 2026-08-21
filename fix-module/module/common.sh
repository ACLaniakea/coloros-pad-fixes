#!/system/bin/sh

LOGFILE="$MODDIR/fix-module.log"

log_msg() {
    echo "[$(date '+%F %T')] $*" >>"$LOGFILE"
}

is_supported_device() {
    soc=$(getprop ro.soc.model)
    platform=$(getprop ro.board.platform)
    case "$soc/$platform" in
        *SM8650Q*/*pineapple*) return 0;;
        *) return 1;;
    esac
}

module_enabled() {
    module_id="$1"
    [ -f "/data/adb/modules/$module_id/module.prop" ] &&
        [ ! -e "/data/adb/modules/$module_id/disable" ]
}

valid_serial() {
    case "$1" in
        ""|unknown|UNKNOWN|null|NULL|0|00000000|0123456789ABCDEF) return 1 ;;
        *) return 0 ;;
    esac
}

find_source_serial() {
    for prop in ro.boot.serialno ro.serialno ro.vendor.serialno \
        vendor.boot.serialno persist.radio.serialno sys.usb.serialno; do
        value="$(getprop "$prop")"
        if valid_serial "$value"; then
            SOURCE_SERIAL="$value"
            SOURCE_PROP="$prop"
            return 0
        fi
    done
    return 1
}

fill_serial_if_missing() {
    prop="$1"
    current="$(getprop "$prop")"
    valid_serial "$current" && return 0
    resetprop -n "$prop" "$SOURCE_SERIAL"
}

apply_serial_fix() {
    if module_enabled coloros_serial_lag_fix; then
        log_msg "serial: dedicated module enabled, skipped"
        return 0
    fi
    find_source_serial || {
        log_msg "serial: no valid source, skipped"
        return 0
    }
    for target in ro.serialno ro.boot.serialno ro.vendor.serialno \
        vendor.boot.serialno persist.radio.serialno; do
        fill_serial_if_missing "$target"
    done
    log_msg "serial aliases verified from $SOURCE_PROP"
}

# 把每个 cpufreq 簇从 powersave 钉频（governor 锁最低频、min=max 钉最低）恢复
# 到硬件真实频率范围。第三方调频器（Uperf Game Turbo，由 Scene/vtools 控制）在
# 长待机后会把 governor 留在 powersave，而本模块停用的源机型 perf HAL 已不会再
# 把它拉回正常。这里只纠正 powersave，不覆盖 balance/performance 正常使用的
# schedutil/performance，避免与其他调频器争夺。
normalize_cpu() {
    target=schedutil
    for g in schedutil walt; do
        if grep -qw "$g" /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors 2>/dev/null; then
            target=$g
            break
        fi
    done
    changed=0
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [ -d "$policy" ] || continue
        gov_file="$policy/scaling_governor"
        [ -e "$gov_file" ] || continue
        # min/max 频率节点默认 0444，需先 chmod 才能写（与旧版 max_freq 恢复一致）。
        chmod 0644 "$gov_file" "$policy/scaling_min_freq" "$policy/scaling_max_freq" 2>/dev/null
        if [ "$(cat "$gov_file" 2>/dev/null)" = powersave ]; then
            echo "$target" >"$gov_file" 2>/dev/null
            changed=1
        fi
        hw_min=$(cat "$policy/cpuinfo_min_freq" 2>/dev/null)
        hw_max=$(cat "$policy/cpuinfo_max_freq" 2>/dev/null)
        case "$hw_min" in ''|*[!0-9]*) hw_min= ;; esac
        case "$hw_max" in ''|*[!0-9]*) hw_max= ;; esac
        [ -n "$hw_min" ] && echo "$hw_min" >"$policy/scaling_min_freq" 2>/dev/null
        [ -n "$hw_max" ] && echo "$hw_max" >"$policy/scaling_max_freq" 2>/dev/null
    done
    if [ "$changed" = 1 ]; then
        log_msg "cpu governor normalized powersave -> $target"
    fi
    return 0
}
