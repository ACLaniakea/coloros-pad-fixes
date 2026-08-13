#!/system/bin/sh

MODDIR=${1:-${0%/*}/..}
LOGFILE="$MODDIR/base-fix.log"

log_msg() {
    echo "[$(date '+%F %T')] $*" >>"$LOGFILE"
}

read_rear_temp() {
    for zone in /sys/class/thermal/thermal_zone*; do
        [ "$(cat "$zone/type" 2>/dev/null)" = "rear-tof-therm" ] || continue
        cat "$zone/temp" 2>/dev/null
        return 0
    done
    return 1
}

while :; do
    temp=$(read_rear_temp)
    case "$temp" in
        ''|*[!0-9]*) sleep 10; continue ;;
    esac

    if [ "$temp" -lt 65000 ]; then
        for policy in /sys/devices/system/cpu/cpufreq/policy*; do
            max_file="$policy/scaling_max_freq"
            hw_max_file="$policy/cpuinfo_max_freq"
            [ -r "$hw_max_file" ] && [ -r "$max_file" ] || continue
            hw_max=$(cat "$hw_max_file" 2>/dev/null)
            current_max=$(cat "$max_file" 2>/dev/null)
            case "$hw_max:$current_max" in
                ''|*[!0-9:]*|*:*:) continue ;;
            esac
            if [ "$current_max" -lt "$hw_max" ]; then
                chmod 0644 "$max_file" 2>/dev/null
                echo "$hw_max" >"$max_file" 2>/dev/null
                log_msg "adaptive boost restored $(basename "$policy") max=$hw_max temp=$temp"
            fi
        done
    fi
    sleep 10
done
