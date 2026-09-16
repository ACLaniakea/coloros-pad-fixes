#!/system/bin/sh
# One-shot wake attribution capture. This exits after the first wake window.

OUT=${1:-/data/local/tmp/wake-memcg-once.log}

memcg_snapshot() {
    for group in apps/active apps/systemserver apps/inactive; do
        base="/dev/memcg/$group"
        printf 'MEMCG %s usage=' "$group"
        cat "$base/memory.usage_in_bytes" 2>/dev/null
        grep -E '^(rss|cache|shmem|swap|pgpgin|pgpgout|pgfault|pgmajfault|workingset_refault_anon|workingset_refault_file) ' \
            "$base/memory.stat" 2>/dev/null | tr '\n' ' '
        echo
    done
}

proc_snapshot() {
    for name in surfaceflinger system_server com.oplusos.launcher com.android.systemui \
        vendor.qti.camera.provider-service_64 cameraserver; do
        pid=$(pidof "$name" 2>/dev/null | awk '{print $1}')
        [ -n "$pid" ] || continue
        printf 'PROC %s pid=%s ' "$name" "$pid"
        grep -E '^(VmRSS|RssAnon|RssFile|RssShmem|VmSwap):' "/proc/$pid/status" 2>/dev/null | tr '\n' ' '
        echo
    done
}

snapshot() {
    date +%s
    cat /proc/pressure/memory
    grep -E '^(pswpin|pswpout|allocstall_normal|allocstall_movable|pgscan_direct|pgsteal_direct|compact_stall) ' /proc/vmstat
    memcg_snapshot
    proc_snapshot
    cat /proc/boost_pool/camera_stat 2>/dev/null
}

echo "ARMED $(date +%s)" >"$OUT"
while ! dumpsys power | grep -q 'mWakefulness=Asleep'; do sleep 2; done
echo "SLEEP $(date +%s)" >>"$OUT"
while ! dumpsys power | grep -q 'mWakefulness=Awake'; do sleep 1; done
echo "WAKE $(date +%s)" >>"$OUT"

i=0
while [ "$i" -lt 12 ]; do
    echo "SAMPLE $i" >>"$OUT"
    snapshot >>"$OUT"
    sleep 1
    i=$((i + 1))
done
echo "DONE $(date +%s)" >>"$OUT"
