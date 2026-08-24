#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
MODULE="${PROJECT_ROOT}/kernel-compat/oplus_compat/oplus_mm_compat.ko"
REMOTE=/data/local/tmp/oplus_mm_compat.ko
ACTION=${1:-status}

root_shell() {
    # Passing a multiline script as an adb argument loses quoting on Android's
    # remote shell. Feed it to an explicit root shell over stdin instead.
    printf '%s\n' "$1" | adb shell su -c sh
}

status() {
    root_shell '
        echo "kernel=$(uname -r)"
        echo "module=$(grep "^oplus_mm_compat " /proc/modules || true)"
        echo "vm.swappiness=$(cat /proc/sys/vm/swappiness)"
        echo "page-cluster=$(cat /proc/sys/vm/page-cluster)"
        echo "zram=$(awk "/zram/{print}" /proc/swaps)"
        for node in compat_status swappiness_para dynamic_swappiness alloc_adjust_ctrl kswapd_debug kswapd_load_stat; do
            echo "--- $node"
            cat "/proc/oplus_mem/$node" 2>/dev/null || true
        done
        grep -E "^(pswpin|pswpout|pgscan_kswapd|pgscan_direct|pgsteal_kswapd|pgsteal_direct|allocstall) " /proc/vmstat
    '
}

case "$ACTION" in
    load)
        test -f "$MODULE"
        adb push "$MODULE" "$REMOTE" >/dev/null
        root_shell '
            grep -q "^oplus_mm_compat " /proc/modules || insmod /data/local/tmp/oplus_mm_compat.ko
            test -r /proc/oplus_mem/compat_status
            echo "vm_swappiness=160" >/proc/oplus_mem/swappiness_para
            echo "direct_swappiness=200" >/proc/oplus_mem/swappiness_para
            echo "160 1024 160 512" >/proc/oplus_mem/dynamic_swappiness
            test "$(sed -n "s/^vm_swappiness: //p" /proc/oplus_mem/swappiness_para)" = 50
            test "$(sed -n "s/^direct_swappiness: //p" /proc/oplus_mem/swappiness_para)" = 20
            test "$(cat /proc/oplus_mem/dynamic_swappiness)" = "50 1024 50 512"
            echo "vm_swappiness=50" >/proc/oplus_mem/swappiness_para
            echo "direct_swappiness=10" >/proc/oplus_mem/swappiness_para
            echo "50 1024 30 512" >/proc/oplus_mem/dynamic_swappiness
            test "$(cat /proc/oplus_mem/alloc_adjust_ctrl)" = 0
        '
        status
        ;;
    unload)
        root_shell '
            if grep -q "^oplus_mm_compat " /proc/modules; then
                rmmod oplus_mm_compat
            fi
        '
        status
        ;;
    status)
        status
        ;;
    sample)
        root_shell '
            i=0
            while [ "$i" -le 3 ]; do
                echo "SAMPLE=$i"
                cat /proc/uptime
                grep -E "^(MemAvailable|SwapFree|CmaTotal|CmaFree):" /proc/meminfo
                grep -E "^(pswpin|pswpout|pgscan_kswapd|pgscan_direct|pgsteal_kswapd|pgsteal_direct|allocstall|compact_stall|compact_fail|compact_success) " /proc/vmstat
                cat /proc/pressure/memory
                cat /sys/block/zram0/mm_stat
                sed -n "1,5p" /proc/oplus_mem/kswapd_load_stat
                [ "$i" -eq 3 ] || sleep 10
                i=$((i + 1))
            done
        '
        ;;
    switch)
        root_shell '
            snapshot() {
                cat /proc/uptime
                grep -E "^(MemAvailable|SwapFree|CmaFree):" /proc/meminfo
                grep -E "^(pswpin|pswpout|pgscan_kswapd|pgscan_direct|pgsteal_kswapd|pgsteal_direct|compact_stall|compact_fail|compact_success) " /proc/vmstat
                cat /proc/pressure/memory
                cat /sys/block/zram0/mm_stat
            }
            echo BEFORE
            snapshot
            round=0
            while [ "$round" -lt 1 ]; do
                for package in com.anthropic.claude com.twitter.android com.android.settings com.google.android.apps.bard com.android.contacts flar2.devcheck com.google.android.googlequicksearchbox com.omarea.vtools com.heytap.mydevices com.tencent.mobileqq com.tencent.mm; do
                    monkey -p "$package" 1 >/dev/null 2>&1
                    sleep 1
                done
                round=$((round + 1))
            done
            input keyevent KEYCODE_HOME
            sleep 3
            echo AFTER
            snapshot
            echo KSWAPD
            cat /proc/oplus_mem/kswapd_load_stat
        '
        ;;
    *)
        echo "usage: $0 [load|status|sample|switch|unload]" >&2
        exit 2
        ;;
esac
