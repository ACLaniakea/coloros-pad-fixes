#!/system/bin/sh

MODDIR=${0%/*}

# ============================================================================
# 联想手写笔桥接 Root 服务 · service（开机完成后）阶段
# 实际修复与作用：
#   1) 开机按已绑定手写笔地址直接调用原厂 CoreService 的 CONNECT_PENCIL
#      BLE 连接（不以 Hall/CPS 为前提），并做有限次重试；
#   2) 设置页断开时调用 DISCONNECT_PENCIL，由配套 LSPosed Hook 执行
#      BluetoothGatt.disconnect()，使设备/HID 真实离开连接态；
#   3) 常驻监控真实 ACL/GATT/Hall/CPS 事件，驱动 ColorOS 设置页手写笔
#      卡片、磁吸胶囊、电量与存在状态（状态只跟随真实事件，不强行回放）；
#   4) CPS 开机上电：仅在真实磁吸 Hall 对为 0:1 时保持 CPS GPIO，磁吸只
#      负责充电与弹窗；不使用自定义内核模块；
#   5) PenHidCtl（priv-app HID 控制器）开机授予蓝牙运行时权限，只调后台
#      PenHidService，无启动器入口；
#   6) 屏幕唤醒后延迟回放真实 Hall/连接状态，避免唤醒瞬间写入 DSI/panel
#      节点导致黑屏；pen_wakeup_* 节点只在开机按需写一次；
#   7) 刷新率策略由 post-fs-data 绑定，本服务不管理亮度/背光/屏幕电源。
# 仅适用于 SM8650Q / pineapple 平台；LSPosed Hook APK 独立安装。
# ============================================================================

LOGFILE="$MODDIR/pen-bridge.log"
MODE=/proc/pen_wakeup_mode
SWITCH=/proc/pen_wakeup_switch
UEVENT=/sys/devices/virtual/lenovo_penraw/lenovo_penraw/uevent
PEN1_HALL=/sys/devices/virtual/factory/interface/hw_info/pen1_hall
PEN2_HALL=/sys/devices/virtual/factory/interface/hw_info/pen2_hall
CPS_GPIOCHIP=gpiochip0
CPS_GPIODEV=/dev/gpiochip0
CPS_GPIOSET=/system/bin/gpioset
CPS_HELPER="$MODDIR/bin/pen-cps-gpio"
CPS_PEN_HALL=/sys/devices/virtual/factory/interface/hw_info/pen2_hall
CPS_UEVENT=/sys/devices/platform/soc/9c0000.qcom,qupv3_i2c_geni_se/98c000.i2c/i2c-2/2-0041/uevent
CPS_PIDFILE="$MODDIR/cps-gpio.pid"
CPS_DISABLED="$MODDIR/disable"
HALL_STATE_FILE="$MODDIR/pen-hall.state"
CAPSULE_DEDUP_FILE="$MODDIR/pen-capsule.last"
CAPSULE_DEDUP_SECONDS=4
SERVICE_LOCK="$MODDIR/.service.lock"
HIDCTL_SERVICE=com.aclaniakea.penhidctl/.PenHidService
OEM_CORE_SERVICE=com.oplus.ipemanager/.btadsorb.CoreService
OEM_CONNECT_ACTION=com.oplus.ipemanager.action.CONNECT_PENCIL
OEM_DISCONNECT_ACTION=com.oplus.ipemanager.action.DISCONNECT_PENCIL
PEN_CONNECT_DEDUP_FILE="$MODDIR/pen-connect.last"
PEN_BOOT_READY_FILE="$MODDIR/pen-boot-ready"
SCREEN_STATE_FILE="$MODDIR/pen-screen.state"
HIDCTL_PERMISSION_FILE="$MODDIR/pen-hid-permissions.ready"
HIDCTL_LAUNCHER_FILE="$MODDIR/pen-hid-launcher.hidden"
PEN_USER_DISCONNECT_KEY=lenovo_pen_user_disconnect_requested

# Respawn guard: if the real service exits (crash, OOM, kill), bring it
# back after a short delay. KernelSU starts the script once; this first
# invocation only owns the respawn loop.
if [ -z "$PEN_SERVICE_RESPAWN" ]; then
    export PEN_SERVICE_RESPAWN=1
    (
        while [ ! -e "$CPS_DISABLED" ]; do
            sh "$0"
            sleep 8
        done
    ) &
    exit 0
fi

# KernelSU normally starts one copy, but manual module refreshes can leave
# old service.sh instances alive.  Keep one owner of the Hall/CPS monitors so
# one physical edge cannot produce duplicate capsule requests.
if ! mkdir "$SERVICE_LOCK" 2>/dev/null; then
    old_pid=$(cat "$SERVICE_LOCK/pid" 2>/dev/null)
    case "$old_pid" in
        ''|*[!0-9]*) ;;
        *)
            if kill -0 "$old_pid" 2>/dev/null; then
                cmd=$(tr "\0" " " < /proc/$old_pid/cmdline 2>/dev/null)
                case "$cmd" in
                    *lenovo_pen_bridge/service.sh*) exit 0 ;;
                esac
            fi
            ;;
    esac
    rm -f "$SERVICE_LOCK/pid" 2>/dev/null
    rmdir "$SERVICE_LOCK" 2>/dev/null || exit 0
    mkdir "$SERVICE_LOCK" 2>/dev/null || exit 0
fi
echo $$ >"$SERVICE_LOCK/pid"
cleanup_service_lock() {
    rm -f "$SERVICE_LOCK/pid" 2>/dev/null
    rmdir "$SERVICE_LOCK" 2>/dev/null
}
trap cleanup_service_lock EXIT INT TERM

soc=$(getprop ro.soc.model)
platform=$(getprop ro.board.platform)
case "$soc/$platform" in
    *SM8650Q*/*pineapple*) ;;
    *) echo "unsupported device: soc=$soc platform=$platform" >"$LOGFILE"; exit 0;;
esac

exec >>"$LOGFILE" 2>&1
echo "[$(date '+%F %T')] service start"

# Use the root runtime's BusyBox first.  On this ROM /system/bin/toybox can
# appear executable while its mount namespace is still settling; invoking it
# then fails and a polling loop can spin at 100% CPU.
sleep_sec() {
    for sleep_backend in \
            /data/adb/ksu/bin/busybox \
            /data/adb/magisk/busybox \
            /system/bin/toybox; do
        [ -x "$sleep_backend" ] || continue
        if "$sleep_backend" sleep "$1" >/dev/null 2>&1; then
            return 0
        fi
    done
    echo "[$(date '+%F %T')] no working sleep backend; stopping service to avoid a busy loop"
    exit 0
}

count=0
while { [ ! -e "$MODE" ] || [ ! -e "$SWITCH" ]; } && [ "$count" -lt 60 ]; do
    sleep_sec 1
    count=$((count + 1))
done

apply_pen_wake() {
    mode=$(cat "$MODE" 2>/dev/null | tr -d '\r')
    wake=$(cat "$SWITCH" 2>/dev/null | tr -d '\r')
    mode_write=0
    switch_write=0

    # system_server cannot write these vendor proc nodes on this ROM.  The
    # Root service is the only writer, and writes each node only when it is
    # not already enabled.  This avoids the old repeated DSI/panel writes
    # while still enabling the CPS pen-wakeup path once after boot.
    if [ "$mode" != 1 ] && [ -w "$MODE" ]; then
        if printf '1\n' >"$MODE" 2>/dev/null; then
            mode_write=1
        fi
    fi
    if [ "$wake" != 1 ] && [ -w "$SWITCH" ]; then
        if printf '1\n' >"$SWITCH" 2>/dev/null; then
            switch_write=1
        fi
    fi

    mode=$(cat "$MODE" 2>/dev/null | tr -d '\r')
    wake=$(cat "$SWITCH" 2>/dev/null | tr -d '\r')
    echo "[$(date '+%F %T')] pen wake root apply mode=$mode switch=$wake wrote_mode=$mode_write wrote_switch=$switch_write"
}

# The CPS driver or vendor power manager can reset these proc switches after
# boot. Keep the kernel wake path enabled while the module is active, but only
# rewrite a node after observing that it has been turned off.
monitor_pen_wake() {
    while [ ! -e "$CPS_DISABLED" ]; do
        mode=$(cat "$MODE" 2>/dev/null | tr -d '\r')
        wake=$(cat "$SWITCH" 2>/dev/null | tr -d '\r')
        if [ -n "$mode" ] && [ -n "$wake" ] && { [ "$mode" != 1 ] || [ "$wake" != "Pen Wakeup SWITCH 1!" ]; }; then
            echo "[$(date '+%F %T')] pen wake node reset detected mode=$mode switch=$wake"
            apply_pen_wake
        fi
        sleep_sec 30
    done
}

# v1.0.61 wrote a connected snapshot immediately after requesting GATT.  That
# snapshot could survive a module update and make the next boot look connected
# even when Bluetooth had no live link.  Start this revision from a neutral
# mirror; the OEM ACL/GATT and real Hall/CPS events repopulate it afterward.
# Keep the explicit user disconnect choice and the last valid battery sample
# intact. The operational latch is normalized separately at boot: an ACL
# disconnect emitted during a normal reboot must not block the next boot.
# Bluetooth recovery below is intentionally independent of the physical Hall
# pair; Hall is only used by the CPS and magnetic-capsule paths.
reset_pen_state_mirror() {
    for key in \
            lenovo_pen_link_connected \
            ipe_pencil_connect_state \
            ipe_pencil_connection_state \
            PENCIL_CONNECT_STATE \
            pencil_connect_state; do
        settings put global "$key" 0 >/dev/null 2>&1
    done
    settings put global lenovo_pen_refresh_active 0 >/dev/null 2>&1
    settings put global settings_enable_oppo_pencil 0 >/dev/null 2>&1
    settings put global ipe_pencil_present 0 >/dev/null 2>&1
    settings put global ipe_pencil_charging_state 0 >/dev/null 2>&1
    settings put global lenovo_pen_physical_docked 0 >/dev/null 2>&1
    settings put global lenovo_pen_hardware_battery_valid 0 >/dev/null 2>&1
    settings put global lenovo_pen_oem_charge_valid 0 >/dev/null 2>&1
    echo "[$(date '+%F %T')] stale v1.0.61 connection mirror cleared"
}

normalize_disconnect_latch() {
    user_requested=$(settings get global "$PEN_USER_DISCONNECT_KEY" 2>/dev/null | tr -d '\r')
    case "$user_requested" in
        1|0) ;;
        *) user_requested=0 ;;
    esac
    # lenovo_pen_disconnect_requested is a runtime guard. Do not carry a
    # natural ACL shutdown from the previous boot into the next boot; only an
    # explicit settings-page Disconnect is persistent across reboot.
    settings put global "$PEN_USER_DISCONNECT_KEY" "$user_requested" >/dev/null 2>&1
    settings put global lenovo_pen_disconnect_requested "$user_requested" >/dev/null 2>&1
    echo "[$(date '+%F %T')] disconnect latch normalized user=$user_requested"
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do
    sleep_sec 2
done
normalize_disconnect_latch
apply_pen_wake
reset_pen_state_mirror
monitor_pen_wake &

# Priv-app allowlists do not grant Android 12+ Bluetooth runtime permissions.
# Root only calls PenHidCtl's explicit service component. The APK has no
# launcher intent, so authorize that service before issuing any HID command.
grant_hidctl_bluetooth_permissions() {
    [ -f "$HIDCTL_PERMISSION_FILE" ] && return 0
    if [ -z "$(pm path com.aclaniakea.penhidctl 2>/dev/null)" ]; then
        echo "[$(date '+%F %T')] HID permission grant skipped: helper APK unavailable"
        return 0
    fi
    failed=0
    for permission in \
            android.permission.BLUETOOTH_CONNECT \
            android.permission.BLUETOOTH_SCAN; do
        if ! pm grant --user 0 com.aclaniakea.penhidctl "$permission" >/dev/null 2>&1; then
            failed=1
            echo "[$(date '+%F %T')] HID permission grant failed permission=$permission"
        fi
    done
    if [ "$failed" = 0 ]; then
        : >"$HIDCTL_PERMISSION_FILE"
        echo "[$(date '+%F %T')] HID Bluetooth runtime permissions granted"
    fi
}

hide_hidctl_launcher() {
    [ -f "$HIDCTL_LAUNCHER_FILE" ] && return 0
    if [ -z "$(pm path com.aclaniakea.penhidctl 2>/dev/null)" ]; then
        echo "[$(date '+%F %T')] HID launcher hide skipped: helper APK unavailable"
        return 0
    fi
    if pm disable --user 0 com.aclaniakea.penhidctl/.MainActivity >/dev/null 2>&1; then
        : >"$HIDCTL_LAUNCHER_FILE"
        echo "[$(date '+%F %T')] HID launcher activity disabled"
    else
        echo "[$(date '+%F %T')] HID launcher activity disable deferred"
    fi
}

retry_hidctl_setup() {
    attempt=1
    while [ "$attempt" -le 12 ] && [ ! -e "$CPS_DISABLED" ]; do
        hide_hidctl_launcher
        grant_hidctl_bluetooth_permissions
        [ -f "$HIDCTL_PERMISSION_FILE" ] && return 0
        sleep_sec 5
        attempt=$((attempt + 1))
    done
    echo "[$(date '+%F %T')] HID setup retry window expired"
}

hide_hidctl_launcher
grant_hidctl_bluetooth_permissions
retry_hidctl_setup &

# The LSPosed Hook is a separate package now. This Root service deliberately
# does not call pm install and never copies an APK into /data/app.
echo "[$(date '+%F %T')] independent LSPosed Hook package expected"

# The ported OplusBatteryManager reports wirelessPenPresent=0 even when the
# real Hall pair is 0/1 (pen docked).  That makes the bridge's Java poller
# classify every magnetic edge as undocked and the OEM Capsule is never
# requested.  Read the two real Hall nodes in root context and feed the
# already-installed ColorOS handoff/capsule receiver.  Battery and charging
# still come from the CPS/GATT-backed settings and uevent; this monitor only
# repairs the physical magnetic edge.
read_hall_state() {
    hall1=$(cat "$PEN1_HALL" 2>/dev/null | tr -d '\r')
    hall2=$(cat "$PEN2_HALL" 2>/dev/null | tr -d '\r')
    case "$hall1:$hall2" in
        0:1|1:0|0:0) echo 1 ;; # docked (either orientation)
        1:1) echo 0 ;; # detached
        *) echo -1 ;;
    esac
}

valid_level() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$1" -ge 0 ] && [ "$1" -le 100 ]
}

read_level_from_file() {
    file="$1"
    [ -r "$file" ] || return 0
    for key in LEVEL BATTERY_LEVEL BATTERY_LEVEL_PERCENT BATTERY CAPACITY PEN_BATTERY; do
        level=$(sed -n "s/^${key}=//p" "$file" 2>/dev/null | head -1 | tr -d '\r')
        if valid_level "$level"; then
            echo "$level"
            return 0
        fi
    done
}

cache_hardware_battery() {
    level="$1"
    valid_level "$level" || return 0
    settings put global ipe_pencil_battery_level "$level" >/dev/null 2>&1
    settings put global lenovo_pen_last_valid_battery "$level" >/dev/null 2>&1
    settings put global lenovo_pen_hardware_battery_valid 1 >/dev/null 2>&1
    now=$(date '+%s' 2>/dev/null)
    case "$now" in
        ''|*[!0-9]*) ;;
        *) settings put global lenovo_pen_hardware_battery_last_at "$now" >/dev/null 2>&1 ;;
    esac
}

suspect_unconnected_zero_battery() {
    [ "$1" = 0 ] || return 1
    connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
    [ "$connected" = 1 ] && return 1
    battery_valid=$(settings get global lenovo_pen_hardware_battery_valid 2>/dev/null | tr -d '\r')
    [ "$battery_valid" = 1 ] && return 1
    last_valid=$(settings get global lenovo_pen_last_valid_battery 2>/dev/null | tr -d '\r')
    valid_level "$last_valid" && [ "$last_valid" -gt 0 ]
}

read_hardware_battery() {
    level=$(settings get global ipe_pencil_battery_level 2>/dev/null | tr -d '\r')
    battery_valid=$(settings get global lenovo_pen_hardware_battery_valid 2>/dev/null | tr -d '\r')
    if valid_level "$level" && [ "$battery_valid" = 1 ]; then
        echo "$level"
        return 0
    fi
    level=$(read_level_from_file "$CPS_UEVENT")
    if valid_level "$level" && ! suspect_unconnected_zero_battery "$level"; then
        cache_hardware_battery "$level"
        echo "$level"
        return 0
    fi
    level=$(read_level_from_file "$UEVENT")
    if valid_level "$level" && ! suspect_unconnected_zero_battery "$level"; then
        cache_hardware_battery "$level"
        echo "$level"
        return 0
    fi
    # Never turn an unavailable sample into a new 0% value.  Keep the last
    # hardware sample until the GATT link reports a fresh one.
    level=$(settings get global lenovo_pen_last_valid_battery 2>/dev/null | tr -d '\r')
    if valid_level "$level"; then
        cache_hardware_battery "$level"
        echo "$level"
    else
        echo -1
    fi
}

read_charge_from_file() {
    file="$1"
    [ -r "$file" ] || return 0
    for key in CHARGING_STATE CHARGING CHARGE_STATE WIRELESS_CHARGING; do
        charge_state=$(sed -n "s/^${key}=//p" "$file" 2>/dev/null | head -1 | tr -d '\r')
        case "$charge_state" in
            1|Charging|charging|Charge|charge|WIRELESS_CHARGING|Wireless\ Charging)
                echo 1
                return 0
                ;;
            0|Full|full|Not\ charging|not_charging|Discharging|discharging|Idle|idle|None|none)
                echo 0
                return 0
                ;;
        esac
    done
}

cache_hardware_charging() {
    state="$1"
    case "$state" in
        0|1)
            settings put global lenovo_pen_hardware_charge_state "$state" >/dev/null 2>&1
            settings put global lenovo_pen_hardware_charge_valid 1 >/dev/null 2>&1
            # Keep the OEM charging keys in sync with the CPS truth so the
            # Hook handoff cannot override a fresh hardware sample with a
            # stale BLE 2A1A value.
            settings put global lenovo_pen_oem_charge_valid 1 >/dev/null 2>&1
            settings put global lenovo_pen_oem_charge_state "$state" >/dev/null 2>&1
            ;;
    esac
}

read_attached_from_file() {
    file="$1"
    [ -r "$file" ] || return 0
    sed -n 's/^ATTACHED=//p' "$file" 2>/dev/null | head -1 | tr -d '\r'
}

read_hardware_charging() {
    docked="$1"
    charge_state=$(read_charge_from_file "$CPS_UEVENT")
    attached=$(read_attached_from_file "$CPS_UEVENT")
    case "$charge_state" in
        0|1)
            if [ "$charge_state" = 0 ] || { [ "$attached" = 1 ] || [ "$docked" = 1 ]; }; then
                cache_hardware_charging "$charge_state"
                echo "$charge_state"
                return 0
            fi
            cache_hardware_charging 0
            echo 0
            return 0
            ;;
    esac
    charge_state=$(read_charge_from_file "$UEVENT")
    case "$charge_state" in
        0|1)
            cache_hardware_charging "$charge_state"
            echo "$charge_state"
            return 0
            ;;
    esac
    charge_valid=$(settings get global lenovo_pen_hardware_charge_valid 2>/dev/null | tr -d '\r')
    charge_state=$(settings get global lenovo_pen_hardware_charge_state 2>/dev/null | tr -d '\r')
    if [ "$charge_valid" = 1 ] && { [ "$charge_state" = 0 ] || [ "$charge_state" = 1 ]; }; then
        echo "$charge_state"
        return 0
    fi
    charge_valid=$(settings get global lenovo_pen_oem_charge_valid 2>/dev/null | tr -d '\r')
    charge_state=$(settings get global lenovo_pen_oem_charge_state 2>/dev/null | tr -d '\r')
    if [ "$charge_valid" = 1 ] && { [ "$charge_state" = 0 ] || [ "$charge_state" = 1 ]; }; then
        echo "$charge_state"
    else
        echo 0
    fi
}

is_pen_mac() {
    case "$1" in
        00:00:00:00:00:00) return 1 ;;
        [0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]) return 0 ;;
        *) return 1 ;;
    esac
}

normalize_pen_mac() {
    raw=$(printf '%s' "$1" | tr -d '\r' | tr 'a-f' 'A-F')
    case "$raw" in
        [0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F])
            printf '%s\n' "$raw"
            ;;
        [0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F])
            printf '%s\n' "$raw" | sed 's/../&:/g; s/:$//'
            ;;
        *)
            printf '\n'
            ;;
    esac
}

pen_name_matches() {
    name=$(printf '%s' "$1" | tr -d '\r"' | tr 'A-Z' 'a-z')
    case "$name" in
        *pen*|*stylus*|*pencil*|*lenovo*|*xiaoxin*|*yoga*|*picasso*) return 0 ;;
        *) return 1 ;;
    esac
}

same_pen_mac() {
    left=$(normalize_pen_mac "$1")
    right=$(normalize_pen_mac "$2")
    [ -n "$left" ] && [ "$left" = "$right" ]
}

# Prefer the configured address when it is still a bonded pen. If it is stale,
# select a bonded pen by its advertised name and persist the new address. This
# keeps the Bluetooth path independent of any factory MAC without attempting
# unrelated bonded devices.
find_bonded_pen_mac() {
    preferred=$(normalize_pen_mac "$1")
    for config in \
            /data/misc/bluedroid/bt_config.conf \
            /data/misc/bluetooth/bt_config.conf \
            /data/misc/bluetooth/bt_config.conf.old; do
        [ -r "$config" ] || continue
        best=""
        block_mac=""
        block_name=""
        while IFS= read -r line || [ -n "$line" ]; do
            case "$line" in
                \[*\])
                    block_key=${line#\[}
                    block_key=${block_key%\]}
                    candidate=$(normalize_pen_mac "$block_key")
                    if [ -n "$block_mac" ] && pen_name_matches "$block_name"; then
                        if same_pen_mac "$block_mac" "$preferred"; then
                            printf '%s\n' "$block_mac"
                            return 0
                        fi
                        [ -n "$best" ] || best="$block_mac"
                    fi
                    block_mac="$candidate"
                    block_name=""
                    ;;
                Name=*)
                    block_name=${line#Name=}
                    ;;
            esac
        done <"$config"
        if [ -n "$block_mac" ] && pen_name_matches "$block_name"; then
            if same_pen_mac "$block_mac" "$preferred"; then
                printf '%s\n' "$block_mac"
                return 0
            fi
            [ -n "$best" ] || best="$block_mac"
        fi
        if [ -n "$best" ]; then
            printf '%s\n' "$best"
            return 0
        fi
    done
    printf '\n'
}

resolve_pen_mac() {
    configured=$(normalize_pen_mac "$(settings get global ipe_pencil_mac_addr 2>/dev/null)")
    resolved=$(find_bonded_pen_mac "$configured")
    if ! is_pen_mac "$resolved"; then
        resolved="$configured"
    fi
    if is_pen_mac "$resolved" && ! same_pen_mac "$resolved" "$configured"; then
        settings put global ipe_pencil_mac_addr "$resolved" >/dev/null 2>&1
        echo "[$(date '+%F %T')] selected bonded pen address=$resolved previous=$configured" >&2
    fi
    printf '%s\n' "$resolved"
}

pen_mac_compact() {
    mac=$(resolve_pen_mac)
    printf '%s\n' "$mac" | tr -d ':'
}

request_pen_capsule() {
    [ "$(read_hall_state)" = 1 ] || return 0
    now=$(date '+%s' 2>/dev/null)
    previous=$(cat "$CAPSULE_DEDUP_FILE" 2>/dev/null)
    previous_time=${previous%%:*}
    previous_state=${previous#*:}
    case "$now:$previous_time" in
        *[!0-9:]*|:*) ;;
        *)
            if [ "$previous_state" = 1 ] && [ "$now" -ge "$previous_time" ] \
                    && [ "$((now - previous_time))" -lt "$CAPSULE_DEDUP_SECONDS" ]; then
                echo "[$(date '+%F %T')] duplicate magnetic capsule suppressed"
                return 0
            fi
            ;;
    esac
    echo "$now:1" >"$CAPSULE_DEDUP_FILE"
    battery=$(read_hardware_battery)
    if ! valid_level "$battery"; then
        echo "[$(date '+%F %T')] magnetic capsule delayed: CPS battery sample unavailable"
        return 0
    fi
    charging=$(read_hardware_charging 1)
    mac=$(pen_mac_compact)
    am broadcast --user 0 --receiver-foreground \
        -a com.aclaniakea.lenovopenbridge.action.SHOW_PENCIL_CAPSULE \
        -p com.oplus.ipemanager \
        --ei battery_level "$battery" \
        --ei charging_state "$charging" \
        --ei charging "$charging" \
        --es present 1 \
        --es macAddr "$mac" \
        --es source hardware_hall_root >/dev/null 2>&1
    echo "[$(date '+%F %T')] real Hall magnetic capsule requested battery=$battery charging=$charging mac=$mac"
}

publish_hall_state() {
    docked="$1"
    battery=$(read_hardware_battery)
    charging=$(read_hardware_charging "$docked")
    mac=$(pen_mac_compact)
    connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
    [ "$connected" = 1 ] || connected=0

    settings put global lenovo_pen_physical_docked "$docked" >/dev/null 2>&1
    settings put global ipe_pencil_charging_state "$charging" >/dev/null 2>&1
    if valid_level "$battery"; then
        # This is a cache update only after a real sample.  An unavailable
        # CPS/GATT sample must not overwrite the last known level with -1.
        settings put global ipe_pencil_battery_level "$battery" >/dev/null 2>&1
    fi
    refresh_active=0
    if [ "$docked" != 1 ] && [ "$connected" = 1 ]; then
        refresh_active=1
    fi
    # The refresh-rate policy is valid only while writing, but Device Space
    # treats the next two keys as the existence of a connected pen. Do not
    # hide a real Bluetooth device merely because it is magnetically docked.
    settings put global lenovo_pen_refresh_active "$refresh_active" >/dev/null 2>&1
    # OPlusRefreshRatePolicyImpl reads settings_enable_oppo_pencil as
    # isIPEPencilConnected and votes ipePencilRateId (120 Hz) while it is 1.
    # A docked pen is not being written with, so report "pen in use" only
    # when the pen is both connected and off the magnetic dock.
    settings put global settings_enable_oppo_pencil "$refresh_active" >/dev/null 2>&1
    settings put global ipe_pencil_present "$connected" >/dev/null 2>&1

    battery_args=""
    if valid_level "$battery"; then
        battery_args="--ei battery_level $battery --ei batteryLevel $battery"
    fi
    am broadcast --user 0 --receiver-foreground \
        -a com.aclaniakea.lenovopenbridge.action.COLOROS_PEN_STATE \
        -p com.oplus.ipemanager \
        $battery_args \
        --ei charging_state "$charging" \
        --ei chargingState "$charging" \
        --ei charging "$charging" \
        --ei physicalDocked "$docked" \
        --ei connected "$connected" \
        --es present "$connected" \
        --es macAddr "$mac" \
        --es name "Lenovo Tab Pen" \
        --es source hardware_hall \
        --ez hardware_battery true \
        --ez hardware_identity_known true >/dev/null 2>&1
    apply_refresh_policy "$connected" "$docked"
    echo "[$(date '+%F %T')] real Hall state docked=$docked battery=$battery charging=$charging connected=$connected mac=$mac"
}

# The old monolithic Hook called applyPenHall immediately from SCREEN_ON.
# During panel resume that raced OplusSurfaceFlinger and could leave a lit
# backlight with no frame. The independent Hook no longer owns this edge;
# replay the already-read Hall/connection state only after the panel has been
# awake for 1.2 seconds. This changes pen settings only, never brightness or
# backlight.
read_screen_wakefulness() {
    dumpsys power 2>/dev/null |
        sed -n 's/.*mWakefulness=\([^, }]*\).*/\1/p' | head -1 | tr -d '\r'
}

monitor_screen_replay() {
    last=-1
    while [ ! -e "$CPS_DISABLED" ]; do
        wakefulness=$(read_screen_wakefulness)
        case "$wakefulness" in
            Awake) state=1 ;;
            *) state=0 ;;
        esac
        if [ "$state" = 1 ] && [ "$last" != 1 ]; then
            echo "[$(date '+%F %T')] screen-on edge observed; pen state replay delayed"
            sleep_sec 1.2
            if [ "$(read_screen_wakefulness)" = Awake ]; then
                docked=$(read_hall_state)
                case "$docked" in
                    0|1)
                        publish_hall_state "$docked"
                        echo "[$(date '+%F %T')] delayed screen-on pen state replay docked=$docked"
                        ;;
                    *)
                        echo "[$(date '+%F %T')] delayed screen-on replay skipped: Hall unavailable"
                        ;;
                esac
            fi
        fi
        last="$state"
        echo "$state" >"$SCREEN_STATE_FILE"
        sleep_sec 0.25
    done
}

monitor_battery_cache() {
    while [ ! -e "$CPS_DISABLED" ]; do
        before=$(settings get global ipe_pencil_battery_level 2>/dev/null | tr -d '\r')
        level=$(read_hardware_battery)
        # The OEM process can publish an unknown sample after the Root boot
        # snapshot. Repair only that cache transition, then let the normal
        # settings receiver consume one valid battery notification.
        if valid_level "$level" && [ "$before" != "$level" ]; then
            docked=$(read_hall_state)
            case "$docked" in
                0|1)
                    publish_hall_state "$docked"
                    echo "[$(date '+%F %T')] invalid battery cache repaired level=$level"
                    ;;
            esac
        fi
        sleep_sec 5
    done
}

monitor_charging_cache() {
    last=$(settings get global lenovo_pen_hardware_charge_state 2>/dev/null | tr -d '\r')
    case "$last" in 0|1) ;; *) last=-1 ;; esac
    while [ ! -e "$CPS_DISABLED" ]; do
        docked=$(read_hall_state)
        case "$docked" in
            0|1)
                charging=$(read_hardware_charging "$docked")
                case "$charging" in
                    0|1)
                        if [ "$charging" != "$last" ]; then
                            last="$charging"
                            publish_hall_state "$docked"
                            echo "[$(date '+%F %T')] charging state repaired charging=$charging docked=$docked"
                        fi
                        ;;
                esac
                ;;
        esac
        sleep_sec 5
    done
}

# Two-way sync (system Bluetooth -> Device Space). dumpsys masks the first
# four MAC octets, so match the visible last two octets of the pen address
# against the HOGP (LE HID) profile state; HOGP state 2 is a live link.
real_bt_connected() {
    mac=$(resolve_pen_mac)
    is_pen_mac "$mac" || return 1
    tail=${mac#*:*:*:*:}
    case "$tail" in
        *[!0-9A-Fa-f:]*|'') return 1 ;;
    esac
    dump=$(dumpsys bluetooth_manager 2>/dev/null | tr 'A-Z' 'a-z')
    tail=$(printf '%s' "$tail" | tr 'A-Z' 'a-z')
    case "$dump" in
        *"$tail"*"hogp connection state=2"*) return 0 ;;
        *"hogp connection state=2"*"$tail"*) return 0 ;;
    esac
    return 1
}

# Refresh-rate policy: the pen counts as "in use" only while it is connected
# and not magnetically docked/charging. Lock 120 Hz then; otherwise release to
# the 144 Hz maximum. Writes happen only on state transitions, never per pen
# event, so the input pipeline is not disturbed.
apply_refresh_policy() {
    connected="$1"
    docked="$2"
    if [ "$connected" = 1 ] && [ "$docked" != 1 ]; then
        settings put system min_refresh_rate 120 >/dev/null 2>&1
        settings put system peak_refresh_rate 120 >/dev/null 2>&1
    else
        settings put system min_refresh_rate 0 >/dev/null 2>&1
        settings put system peak_refresh_rate 144 >/dev/null 2>&1
    fi
}

# The OEM mirrors can lag or be overridden by a stale disconnect latch. Poll
# the actual Bluetooth stack every two seconds and republish the connection
# mirrors so Device Space always follows the real link. If a live link appears
# while a stale user-disconnect latch is still set, the stack has already
# reconnected: clear the latch so the UI stops reporting "disconnected".
monitor_real_bt_state() {
    last=-1
    while [ ! -e "$CPS_DISABLED" ]; do
        if real_bt_connected; then
            connected=1
        else
            connected=0
        fi
        current=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
        [ "$current" = 1 ] || current=0
        if [ "$connected" != "$current" ] || [ "$connected" != "$last" ]; then
            settings put global lenovo_pen_link_connected "$connected" >/dev/null 2>&1
            # OPlusRefreshRateService treats ipe_pencil_connect_state==1 as the
            # IPE pencil connected (isIPEPencilConnected) and votes the OEM
            # ipePencilRateId (120 Hz) while connected. The other mirror keys
            # keep the legacy connected encoding for the pen settings UI.
            connect_state=0
            [ "$connected" = 1 ] && connect_state=2
            for key in ipe_pencil_connection_state PENCIL_CONNECT_STATE pencil_connect_state; do
                settings put global "$key" "$connect_state" >/dev/null 2>&1
            done
            settings put global ipe_pencil_connect_state "$connected" >/dev/null 2>&1
            docked=$(settings get global lenovo_pen_physical_docked 2>/dev/null | tr -d '\r')
            [ "$docked" = 1 ] || docked=0
            pen_in_use=0
            [ "$connected" = 1 ] && [ "$docked" != 1 ] && pen_in_use=1
            settings put global settings_enable_oppo_pencil "$pen_in_use" >/dev/null 2>&1
            settings put global ipe_pencil_present "$connected" >/dev/null 2>&1
            apply_refresh_policy "$connected" "$docked"
            if [ "$connected" = 1 ]; then
                user_requested=$(settings get global lenovo_pen_user_disconnect_requested 2>/dev/null | tr -d '\r')
                if [ "$user_requested" = 1 ]; then
                    settings put global lenovo_pen_user_disconnect_requested 0 >/dev/null 2>&1
                    settings put global lenovo_pen_disconnect_requested 0 >/dev/null 2>&1
                    echo "[$(date '+%F %T')] real BT link present; cleared stale disconnect latch"
                fi
            fi
            # Push the real link to the OEM UI: the Hook's handoff receiver
            # consumes the connected extra and forwards it to the panel and
            # settings callbacks, so a sheet opened before the link came up
            # stops showing a stale "connecting/disconnected" state.
            battery=$(settings get global ipe_pencil_battery_level 2>/dev/null | tr -d '\r')
            valid_level "$battery" || battery=$(settings get global lenovo_pen_last_valid_battery 2>/dev/null | tr -d '\r')
            charging=$(settings get global lenovo_pen_hardware_charge_state 2>/dev/null | tr -d '\r')
            case "$charging" in 0|1) ;; *) charging=0 ;; esac
            mac=$(pen_mac_compact)
            am broadcast --user 0 --receiver-foreground \
                -a com.aclaniakea.lenovopenbridge.action.COLOROS_PEN_STATE \
                -p com.oplus.ipemanager \
                --ei connected "$connected" \
                --ei battery_level "$battery" \
                --ei charging_state "$charging" \
                --es present "$connected" \
                --es macAddr "$mac" \
                --es source hardware_hall >/dev/null 2>&1
            echo "[$(date '+%F %T')] real BT state mirror connected=$connected (was $current)"
            last="$connected"
        fi
        sleep_sec 1
    done
}

monitor_hall_capsule() {
    candidate=-1
    samples=0
    boot_cycle=1
    last=$(cat "$HALL_STATE_FILE" 2>/dev/null | tr -d '\r')
    case "$last" in 0|1) ;; *) last=-1 ;; esac
    while [ ! -e "$CPS_DISABLED" ]; do
        state=$(read_hall_state)
        case "$state" in
            0|1)
                if [ "$state" = "$candidate" ]; then
                    samples=$((samples + 1))
                else
                    candidate="$state"
                    samples=1
                fi
                if [ "$samples" -ge 3 ]; then
                    # Do not republish solely because a receiver has not yet
                    # mirrored the setting. That feedback loop generated
                    # repeated system_server broadcasts every poll interval.
                    if [ "$boot_cycle" = 1 ] || [ "$state" != "$last" ]; then
                        previous="$last"
                        last="$state"
                        echo "$state" >"$HALL_STATE_FILE"
                        publish_hall_state "$state"
                        if [ "$state" = 1 ]; then
                            # Magnetic attach is a physical reconnect intent:
                            # clear any stale disconnect latch and restore the
                            # link if it is not already up at the BT layer.
                            settings put global lenovo_pen_user_disconnect_requested 0 >/dev/null 2>&1
                            settings put global lenovo_pen_disconnect_requested 0 >/dev/null 2>&1
                            if ! real_bt_connected; then
                                request_pen_connect
                            fi
                            if [ "$boot_cycle" = 1 ]; then
                                # IPeManager's BLE process registers the
                                # dynamic Capsule receiver late in boot. Do
                                # not spend the only boot request before it
                                # exists; the real Hall state is already
                                # published above.
                                (
                                    sleep_sec 22
                                    request_pen_capsule
                                ) &
                            else
                                request_pen_capsule
                            fi
                        fi
                        boot_cycle=0
                    fi
                fi
                ;;
        esac
        sleep_sec 0.25
    done
}

monitor_hall_capsule &
monitor_screen_replay &
monitor_battery_cache &
monitor_charging_cache &
monitor_real_bt_state &

run_hidctl() {
    action="$1"
    mac=$(resolve_pen_mac)
    if ! is_pen_mac "$mac"; then
        echo "[$(date '+%F %T')] HID $action skipped: no bonded pen MAC"
        return 0
    fi
    if [ -z "$(pm path com.aclaniakea.penhidctl 2>/dev/null)" ]; then
        echo "[$(date '+%F %T')] HID $action skipped: helper APK unavailable"
        return 0
    fi
    grant_hidctl_bluetooth_permissions
    if am start-foreground-service --user 0 -n "$HIDCTL_SERVICE" \
            --es action "$action" --es mac "$mac" >/dev/null 2>&1; then
        echo "[$(date '+%F %T')] HID $action service requested mac=$mac"
    else
        echo "[$(date '+%F %T')] HID $action request failed mac=$mac"
    fi
}

request_oem_pen_action() {
    action="$1"
    mac=$(resolve_pen_mac)
    if ! is_pen_mac "$mac"; then
        echo "[$(date '+%F %T')] OEM $action skipped: no bonded pen MAC"
        return 0
    fi
    if [ -z "$(pm path com.oplus.ipemanager 2>/dev/null)" ]; then
        echo "[$(date '+%F %T')] OEM $action skipped: IPeManager unavailable"
        return 0
    fi
    # These are the vendor service's real actions.  CONNECT_PENCIL reaches
    # s0.x()/BleManager.b(), while DISCONNECT_PENCIL reaches s0.z() and the
    # hidden BluetoothDevice.disconnect() path.  The old Root service only
    # sent a custom system_server broadcast, which could not close/open the
    # OEM GATT link.
    extra=""
    if [ "$action" = "$OEM_CONNECT_ACTION" ]; then
        extra="--ez codex_auto_connect true"
    fi
    if am startservice --user 0 -n "$OEM_CORE_SERVICE" \
            -a "$action" --es device_mac_info "$mac" $extra >/dev/null 2>&1; then

        echo "[$(date '+%F %T')] OEM $action requested mac=$mac"
    else
        echo "[$(date '+%F %T')] OEM $action request failed mac=$mac"
    fi
}

request_pen_connect() {
    requested=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')
    [ "$requested" = 1 ] && {
        echo "[$(date '+%F %T')] pen connect skipped: Settings disconnect latch is set"
        return 0
    }
    user_requested=$(settings get global lenovo_pen_user_disconnect_requested 2>/dev/null | tr -d '\r')
    [ "$user_requested" = 1 ] && {
        echo "[$(date '+%F %T')] pen connect skipped: user disconnect choice is active"
        return 0
    }

    now=$(date '+%s' 2>/dev/null)
    previous=$(cat "$PEN_CONNECT_DEDUP_FILE" 2>/dev/null)
    case "$previous" in
        ''|*[!0-9]*) ;;
        *)
            [ "$now" -ge "$previous" ] && [ "$((now - previous))" -lt 8 ] && return 0
            ;;
    esac
    echo "$now" >"$PEN_CONNECT_DEDUP_FILE"
    request_oem_pen_action "$OEM_CONNECT_ACTION"
    # Let the stock CoreService create the BLE/GATT session before asking the
    # hidden HID Host profile to attach to the same bonded device. The panel
    # is forced to the real link state by the Hook, so this wait only needs
    # to cover the OEM GATT session, not the full UI round-trip.
    sleep_sec 1
    run_hidctl connect
}

request_pen_disconnect() {
    request_oem_pen_action "$OEM_DISCONNECT_ACTION"
    run_hidctl disconnect
}

# The stock settings action updates the IPe state, but on this port HID Host
# remains connected. Enforce an explicit settings-page Disconnect at both the
# vendor CoreService and the actual HID profile. A 1 -> 0 transition is the
# only runtime path that requests a connect; an idle 0 with no live link is
# left alone because the bounded boot loop is the only automatic recovery.
monitor_hid_latch() {
    last=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')
    case "$last" in
        1|0) ;;
        *) last=-1 ;;
    esac
    repeat=0
    while [ ! -e "$CPS_DISABLED" ]; do
        requested=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')
        connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
        case "$requested" in
            1)
                if [ "$last" != 1 ]; then
                    request_pen_disconnect
                    repeat=0
                else
                    repeat=0
                fi
                ;;
            0)
                if [ "$last" = 1 ]; then
                    user_requested=$(settings get global lenovo_pen_user_disconnect_requested 2>/dev/null | tr -d '\r')
                    if [ "$user_requested" = 1 ]; then
                        echo "[$(date '+%F %T')] explicit pen connect skipped: user disconnect choice is active"
                    elif [ "$connected" = 1 ]; then
                        echo "[$(date '+%F %T')] explicit pen connect already has a real link"
                    else
                        request_pen_connect
                    fi
                    repeat=0
                else
                    repeat=0
                fi

                ;;
            *)
                repeat=0
                ;;
        esac
        last="$requested"
        sleep_sec 2
    done
}

monitor_hid_latch &

# The ported IPeManager package carries the vendor Bluetooth receivers in
# its resolver table, but their user-0 component state is disabled.  Enable
# only those two stock receivers so the real OAF/ACL events can start
# CoreService under Oppo's own UID; no synthetic event or connection state is
# written here.
for receiver in \
    com.oplus.ipemanager/.btadsorb.ble.BluetoothStatusReceiver \
    com.oplus.ipemanager/.btadsorb.receiver.BluetoothBroadcastReceiver; do
    if pm enable --user 0 "$receiver" >/dev/null 2>&1; then
        echo "[$(date '+%F %T')] enabled Oppo pen receiver=$receiver"
    else
        echo "[$(date '+%F %T')] unable to enable Oppo pen receiver=$receiver"
    fi
done

# On this port the CPS8601 is probed before hall_detect has replayed the
# initial docked state. The vendor hall notifier's real event 1 sequence is
# therefore never emitted at boot: cps_power_gpio (13) is high, but the
# controller's sw_en (10) and boost_mode (108) pins remain low. The second
# Hall node alone is high both when docked and when detached, so gate the
# real GPIO keeper on the validated Hall pair (0:1 == docked). No pen,
# Bluetooth or attention state is synthesized here; the CPS driver must still
# report the actual chip, battery and HID state.
start_cps_gpio() {
    [ -r "$CPS_PEN_HALL" ] || return 0
    [ "$(read_hall_state)" = 1 ] || return 0
    [ -x "$CPS_HELPER" ] || [ -x "$CPS_GPIOSET" ] || {
        echo "[$(date '+%F %T')] CPS GPIO helper unavailable; wake skipped"
        return 0
    }
    [ -e "$CPS_GPIODEV" ] || return 0

    if [ -r "$CPS_PIDFILE" ]; then
        old_pid=$(cat "$CPS_PIDFILE" 2>/dev/null)
        case "$old_pid" in
            ''|*[!0-9]*) old_pid= ;;
            *) kill -0 "$old_pid" 2>/dev/null && return 0 ;;
        esac
    fi
    rm -f "$CPS_PIDFILE"

    if [ -x "$CPS_HELPER" ]; then
        "$CPS_HELPER" >/dev/null 2>&1 &
        cps_pid=$!
        sleep_sec 1
        if kill -0 "$cps_pid" 2>/dev/null; then
            echo "$cps_pid" >"$CPS_PIDFILE"
            echo "[$(date '+%F %T')] CPS GPIO handle holder started pid=$cps_pid hall=1 gpio=10,108"
            return 0
        fi
        echo "[$(date '+%F %T')] CPS GPIO handle helper exited; using gpioset fallback"
    fi

    (
        cleanup_cps_gpio() {
            "$CPS_GPIOSET" "$CPS_GPIOCHIP" 10=0 108=0 >/dev/null 2>&1
            exit 0
        }
        trap cleanup_cps_gpio HUP INT TERM
        while [ ! -e "$CPS_DISABLED" ] && [ -r "$CPS_PEN_HALL" ] \
                && [ "$(read_hall_state)" = 1 ]; do
            "$CPS_GPIOSET" "$CPS_GPIOCHIP" 10=1 108=1 >/dev/null 2>&1
            sleep_sec 1
        done
        cleanup_cps_gpio
    ) &
    cps_pid=$!
    echo "$cps_pid" >"$CPS_PIDFILE"
    echo "[$(date '+%F %T')] CPS real wake sequence started pid=$cps_pid hall=1 gpio=10,108"
}

stop_cps_gpio() {
    had_pid=0
    if [ -r "$CPS_PIDFILE" ]; then
        cps_pid=$(cat "$CPS_PIDFILE" 2>/dev/null)
        case "$cps_pid" in
            ''|*[!0-9]*) ;;
            *)
                had_pid=1
                kill "$cps_pid" 2>/dev/null
                ;;
        esac
        rm -f "$CPS_PIDFILE"
    fi
    [ "$had_pid" = 1 ] && "$CPS_GPIOSET" "$CPS_GPIOCHIP" 10=0 108=0 >/dev/null 2>&1
}

monitor_cps_gpio() {
    wait_count=0
    while [ ! -r "$CPS_PEN_HALL" ] && [ "$wait_count" -lt 120 ]; do
        sleep_sec 1
        wait_count=$((wait_count + 1))
    done
    while [ ! -e "$CPS_DISABLED" ]; do
        if [ -r "$CPS_PEN_HALL" ] && [ "$(read_hall_state)" = 1 ]; then
            start_cps_gpio
        else
            stop_cps_gpio
        fi
        sleep_sec 2
    done
    stop_cps_gpio
}

wait_for_cps_power() {
    wait_count=0
    while [ ! -e "$CPS_DISABLED" ] && [ "$wait_count" -lt 20 ]; do
        if [ "$(read_hall_state)" = 0 ]; then
            echo "[$(date '+%F %T')] CPS boot power skipped: pen is not magnetically docked"
            return 1
        fi
        if [ -r "$CPS_PEN_HALL" ] && [ "$(read_hall_state)" = 1 ]; then
            start_cps_gpio
            cps_pid=$(cat "$CPS_PIDFILE" 2>/dev/null)
            case "$cps_pid" in
                ''|*[!0-9]*) ;;
                *)
                    if kill -0 "$cps_pid" 2>/dev/null; then
                        echo "[$(date '+%F %T')] CPS boot power ready pid=$cps_pid"
                        return 0
                    fi
                    ;;
            esac
        fi
        sleep_sec 1
        wait_count=$((wait_count + 1))
    done
    echo "[$(date '+%F %T')] CPS boot power wait ended without a live GPIO holder"
    return 1
}

monitor_cps_gpio &
echo "[$(date '+%F %T')] CPS hall monitor started path=$CPS_PEN_HALL"

# CPS power is a charging concern only. Bluetooth pen recovery must not wait
# for Hall/CPS because the OEM pen protocol is wireless even while undocked.
if [ "$(read_hall_state)" = 1 ] && wait_for_cps_power; then
    echo "[$(date '+%F %T')] CPS boot power ready before Bluetooth retries"
fi
touch "$PEN_BOOT_READY_FILE"

# Re-emit the driver's current INFO/MAC/TOUCH_INFORMATION snapshot after the
# LSPosed system_server observer has started.
if [ -w "$UEVENT" ]; then
    echo change >"$UEVENT"
    echo "[$(date '+%F %T')] PEN_FRAMEWORK uevent requested"
fi
if [ -w "$CPS_UEVENT" ]; then
    echo change >"$CPS_UEVENT"
    echo "[$(date '+%F %T')] CPS uevent requested"
fi

# Keep the real vendor request alive across Bluetooth's late service startup
# window.  Stop as soon as the ACL/GATT callback has reported a live link; do
# not send the old RECONNECT_PEN broadcast, which clears the settings latch and
# invokes a synthetic system_server replay before the real link exists.
# The Lenovo pen powers itself off when it is not magnetically docked, so
# actively searching for it over Bluetooth is pointless (and the early pokes
# used to restart the adapter). Only connect after the CPS power-on, which can
# only wake a docked pen; the dock-attach edge reconnects it later.
# Do not poke the Bluetooth stack while it is still coming up.
bt_wait=0
while [ "$bt_wait" -lt 60 ]; do
    [ "$(settings get global bluetooth_on 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep_sec 2
    bt_wait=$((bt_wait + 2))
done
requested=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')
user_requested=$(settings get global lenovo_pen_user_disconnect_requested 2>/dev/null | tr -d '\r')
connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
if [ "$connected" = 1 ]; then
    echo "[$(date '+%F %T')] boot pen connect confirmed by real ACL/GATT state"
elif [ "$requested" = 1 ] || [ "$user_requested" = 1 ]; then
    echo "[$(date '+%F %T')] boot pen connect skipped: Settings disconnect latch is set"
elif [ "$(read_hall_state)" = 1 ]; then
    # Docked: the CPS boot power sequence already ran; connect once and give
    # the link a bounded window to come up.
    request_pen_connect
    echo "[$(date '+%F %T')] real OEM boot connect requested (docked/CPS powered)"
    attempt=2
    while [ "$attempt" -le 4 ] && [ ! -e "$CPS_DISABLED" ]; do
        sleep_sec 12
        connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
        if [ "$connected" = 1 ]; then
            echo "[$(date '+%F %T')] boot pen connect confirmed by real ACL/GATT state"
            break
        fi
        request_pen_connect
        echo "[$(date '+%F %T')] real OEM boot connect retry attempt=$attempt"
        attempt=$((attempt + 1))
    done
else
    echo "[$(date '+%F %T')] pen not docked; boot connect skipped (CPS can only wake a docked pen)"
fi

# Keep all monitor children alive after the boot retry window. Exiting the
# shell here can orphan/kill the CPS, Hall and HID reconciliation loops on
# some KernelSU/Magisk launchers, which leaves only the already-open BLE link.
while [ ! -e "$CPS_DISABLED" ]; do
    sleep_sec 30
done
