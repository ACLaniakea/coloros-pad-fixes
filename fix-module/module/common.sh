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
