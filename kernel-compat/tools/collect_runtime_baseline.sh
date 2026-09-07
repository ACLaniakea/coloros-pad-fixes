#!/usr/bin/env bash
# Collect a read-only SM8650Q / ColorOS runtime snapshot for A/B performance
# testing. It intentionally never changes properties, sysfs, modules, or
# Android services.
set -euo pipefail

target_dir=${1:-"runtime-baseline-$(date +%Y%m%d-%H%M%S)"}
mkdir -p "$target_dir"

adb_bin=${ADB:-adb}
serial_args=()
if [[ -n "${ADB_SERIAL:-}" ]]; then
    serial_args=(-s "$ADB_SERIAL")
fi

"$adb_bin" "${serial_args[@]}" wait-for-device

"$adb_bin" "${serial_args[@]}" shell 'su -c '\''
echo "== kernel =="
uname -a
echo
echo "== memory =="
cat /proc/meminfo
echo
echo "== pressure =="
cat /proc/pressure/memory 2>/dev/null
cat /proc/pressure/cpu 2>/dev/null
echo
echo "== vmstat (swap/reclaim) =="
grep -E "^(pswp|pgscan|pgsteal|workingset|compact|nr_anon|nr_file|nr_slab)" /proc/vmstat
echo
echo "== swaps =="
cat /proc/swaps
echo
echo "== zram / hybridswap =="
for node in \
  /sys/block/zram0/comp_algorithm \
  /sys/block/zram0/disksize \
  /sys/block/zram0/mm_stat \
  /sys/block/zram0/hybridswap_enable \
  /sys/block/zram0/hybridswap_meminfo \
  /sys/block/zram0/hybridswap_core_enable; do
    [ -r "$node" ] || continue
    echo "-- $node"
    cat "$node"
done
echo
echo "== MGLRU =="
for node in /sys/kernel/mm/lru_gen/enabled /sys/kernel/mm/lru_gen/min_ttl_ms; do
    [ -r "$node" ] && { echo "-- $node"; cat "$node"; }
done
echo
echo "== relevant modules =="
grep -E "^(oplus_|sched_walt|lenovohyperSched|zram|zsmalloc)" /proc/modules
echo
echo "== cpu top =="
top -b -n 1 -m 35 2>/dev/null || top -n 1 -m 35
'\''' >"$target_dir/runtime.txt"

printf '%s\n' "wrote $target_dir/runtime.txt"
