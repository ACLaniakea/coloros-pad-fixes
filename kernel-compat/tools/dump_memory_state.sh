#!/system/bin/sh
# Read-only memory and graphics state snapshot for device-to-device comparison.

echo '===IDENT'
getprop ro.product.model
uname -r
echo '===MEM'
grep -E 'MemTotal|MemFree|MemAvailable|Buffers|Cached|SwapCached|Active|Inactive|Unevictable|Mlocked|Shmem|Slab|SReclaimable|SUnreclaim|SwapTotal|SwapFree' /proc/meminfo
echo '===PSI'
cat /proc/pressure/memory
echo '===ZRAM'
cat /proc/swaps
cat /sys/block/zram0/mm_stat
echo '===VM'
grep -E '^(pswpin|pswpout|allocstall_normal|allocstall_movable|pgscan_direct|pgsteal_direct|pgscan_kswapd|pgsteal_kswapd|compact_stall|nr_anon_transparent_hugepages) ' /proc/vmstat
echo '===WATERMARK'
cat /proc/sys/vm/min_free_kbytes
cat /proc/sys/vm/watermark_scale_factor
cat /sys/kernel/mm/lru_gen/enabled 2>/dev/null
cat /sys/kernel/mm/transparent_hugepage/enabled
echo '===KGSL'
for f in mapped page_alloc coherent secure vmalloc; do
    printf '%s=' "$f"
    cat "/sys/class/kgsl/kgsl/$f"
done
echo '===KGSL_PROCS'
for d in /sys/class/kgsl/kgsl/proc/*; do
    [ -d "$d" ] || continue
    pid=${d##*/}
    mapped=$(cat "$d/gpumem_mapped" 2>/dev/null)
    [ -n "$mapped" ] || continue
    state=$(cat "$d/state" 2>/dev/null || echo n/a)
    reclaimed=$(cat "$d/gpumem_reclaimed" 2>/dev/null || echo n/a)
    oom=$(cat "/proc/$pid/oom_score_adj" 2>/dev/null || echo gone)
    comm=$(cat "/proc/$pid/comm" 2>/dev/null || echo gone)
    printf '%s pid=%s mapped=%s state=%s reclaimed=%s oom=%s\n' "$comm" "$pid" "$mapped" "$state" "$reclaimed" "$oom"
done
echo '===MEMCG'
for d in /dev/memcg/apps/active /dev/memcg/apps/systemserver /dev/memcg/apps/inactive; do
    echo "$d"
    grep -E '^(rss|cache|shmem|swap|inactive_anon|active_anon|unevictable) ' "$d/memory.stat"
done
echo '===PROCS'
for n in surfaceflinger system_server com.android.systemui com.oplusos.launcher com.android.launcher; do
    p=$(pidof "$n" 2>/dev/null | awk '{print $1}')
    [ -n "$p" ] || continue
    printf '%s %s ' "$n" "$p"
    grep -E '^(VmRSS|RssAnon|RssFile|RssShmem|VmSwap):' "/proc/$p/status" | tr '\n' ' '
    echo
done
