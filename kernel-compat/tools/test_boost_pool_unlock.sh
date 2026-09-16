#!/system/bin/sh
# Run on the device as root: sh test_boost_pool_unlock.sh [standby_seconds].
# It samples the exact lock-screen to wake window without changing pool size.

standby_seconds=${1:-540}

vm() {
    awk '/^(allocstall_normal|allocstall_movable|pgsteal_direct|pgscan_direct) / {
        value[$1] = $2
    } END {
        printf "%d %d %d\n", \
            value["allocstall_normal"] + value["allocstall_movable"], \
            value["pgsteal_direct"], value["pgscan_direct"]
    }' /proc/vmstat
}

pool() {
    [ -r /proc/boost_pool/camera ] || { echo "pool=unloaded"; return; }
    awk '/^Name:/ { printf "pool=%s ", $2 }' /proc/boost_pool/camera
    printf "camera_pid=%s\n" "$(cat /proc/boost_pool/camera_pid)"
}

wake_and_confirm() {
    tries=0
    while ! dumpsys power | grep -q 'mWakefulness=Awake'; do
        input keyevent KEYCODE_WAKEUP
        sleep 1
        tries=$((tries + 1))
        [ "$tries" -lt 5 ] || return 1
    done
}

wake_and_confirm || { echo 'FAIL: could not wake before standby'; exit 1; }
input keyevent KEYCODE_SLEEP
sleep 5
sleep "$standby_seconds"

echo '--- before wake ---'
set -- $(vm); a0=$1; d0=$2; s0=$3
printf 'allocstall=%s pgsteal_direct=%s pgscan_direct=%s\n' "$a0" "$d0" "$s0"
pool
dumpsys gfxinfo com.android.systemui reset >/dev/null

wake_and_confirm || { echo 'FAIL: could not wake after standby'; exit 1; }
sleep 3

echo '--- three seconds after wake ---'
set -- $(vm); a1=$1; d1=$2; s1=$3
printf 'allocstall_delta=%s pgsteal_direct_delta=%s pgscan_direct_delta=%s\n' \
    "$((a1-a0))" "$((d1-d0))" "$((s1-s0))"
pool
dumpsys gfxinfo com.android.systemui |
    grep -m5 -E 'Total frames|Janky frames:|90th percentile|99th percentile'
