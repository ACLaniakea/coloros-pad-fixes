#!/system/bin/sh
# One-shot on-device capture for the next sleep -> wake transition.

OUT=${1:-/data/local/tmp/wake-full-once.log}

memcg() {
    for group in apps/active apps/systemserver apps/inactive; do
        base="/dev/memcg/$group"
        printf 'MEMCG %s ' "$group"
        grep -E '^(rss|cache|shmem|swap|pgpgin|pgpgout|pgmajfault|unevictable) ' \
            "$base/memory.stat" 2>/dev/null | tr '\n' ' '
        echo
    done
}

procstat() {
    for name in surfaceflinger system_server com.android.systemui com.android.launcher \
        vendor.qti.camera.provider-service_64 cameraserver; do
        pid=$(pidof "$name" 2>/dev/null | awk '{print $1}')
        [ -n "$pid" ] || continue
        printf 'PROC %s pid=%s ' "$name" "$pid"
        grep -E '^(VmRSS|RssAnon|RssFile|RssShmem|VmSwap):' "/proc/$pid/status" 2>/dev/null | tr '\n' ' '
        [ "$name" = surfaceflinger ] && printf 'cpuset=%s ' "$(cat /proc/$pid/cpuset 2>/dev/null)"
        echo
    done
}

snapshot() {
    date +%s
    cat /proc/pressure/memory
    grep -E '^(pswpin|pswpout|allocstall_normal|allocstall_movable|pgscan_direct|pgsteal_direct|compact_stall) ' /proc/vmstat
    memcg
    procstat
    cat /proc/boost_pool/camera_stat 2>/dev/null
}

echo "ARMED $(date +%s)" >"$OUT"
while ! dumpsys power | grep -q 'mWakefulness=Asleep'; do sleep 2; done
echo "SLEEP $(date +%s)" >>"$OUT"
while ! dumpsys power | grep -q 'mWakefulness=Awake'; do sleep 1; done
echo "WAKE $(date +%s)" >>"$OUT"

i=0
while [ "$i" -lt 15 ]; do
    echo "SAMPLE $i" >>"$OUT"
    snapshot >>"$OUT"
    sleep 1
    i=$((i + 1))
done
echo "DONE $(date +%s)" >>"$OUT"
