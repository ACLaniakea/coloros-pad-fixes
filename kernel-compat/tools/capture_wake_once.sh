#!/system/bin/sh
# One-shot on-device wake capture. It exits after the first sleep -> wake cycle.

OUT=${1:-/data/local/tmp/wake-once.log}

snapshot() {
    date +%s
    cat /proc/pressure/memory
    grep -E '^(pswpin|pswpout|allocstall_normal|allocstall_movable|pgscan_direct|pgsteal_direct|compact_stall) ' /proc/vmstat
    cat /proc/swaps
    cat /proc/boost_pool/camera_stat 2>/dev/null
    for node in /sys/class/kgsl/kgsl/page_alloc \
        /sys/class/kgsl/kgsl/page_reclaim_per_call; do
        printf '%s=' "${node##*/}"
        cat "$node"
    done
}

echo "ARMED $(date +%s)" >"$OUT"
while ! dumpsys power | grep -q 'mWakefulness=Asleep'; do
    sleep 2
done
echo "SLEEP $(date +%s)" >>"$OUT"

while ! dumpsys power | grep -q 'mWakefulness=Awake'; do
    sleep 1
done
echo "WAKE $(date +%s)" >>"$OUT"

i=0
while [ "$i" -lt 30 ]; do
    echo "SAMPLE $i" >>"$OUT"
    snapshot >>"$OUT"
    sleep 1
    i=$((i + 1))
done

echo "DONE $(date +%s)" >>"$OUT"
