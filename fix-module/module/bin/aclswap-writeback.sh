#!/system/bin/sh
# aclswap 写回驱动：周期性把冷页从压缩池推到闪存。
#
# 单独成文件并由 service.sh 用 setsid 启动，而不是内联的 `( ... ) &`：
# KernelSU 的 service.sh 退出时会回收整个进程组，内联子进程活不过它。
MODDIR="${1:-/data/adb/modules/coloros_port_fix}"
. "$MODDIR/common.sh" 2>/dev/null || exit 0

ACLSWAP_SYS=/sys/block/aclswap0
ACLSWAP_DEV=/dev/block/aclswap0
ACLSWAP_IDLE_AGE=300
ACLSWAP_CYCLE=60
ACLSWAP_WB_PAGES_PER_CYCLE=65536
ACLSWAP_TRIGGER_PCT=60

reported=0

aclswap_active() {
    [ -d "$ACLSWAP_SYS" ] || return 1
    grep -q "^$ACLSWAP_DEV " /proc/swaps 2>/dev/null
}

while aclswap_active; do
    toybox sleep "$ACLSWAP_CYCLE"
    limit=$(awk '{print $4}' "$ACLSWAP_SYS/mm_stat" 2>/dev/null)
    used=$(awk '{print $3}' "$ACLSWAP_SYS/mm_stat" 2>/dev/null)
    case "$limit" in ''|0|*[!0-9]*) continue ;; esac
    case "$used" in ''|*[!0-9]*) continue ;; esac
    threshold=$(awk -v l="$limit" -v p="$ACLSWAP_TRIGGER_PCT" 'BEGIN{ printf "%.0f", l * p / 100 }')
    [ "$used" -gt "$threshold" ] || continue

    echo 1 >"$ACLSWAP_SYS/writeback_limit_enable" 2>/dev/null
    echo "$ACLSWAP_WB_PAGES_PER_CYCLE" >"$ACLSWAP_SYS/writeback_limit" 2>/dev/null
    echo "$ACLSWAP_IDLE_AGE" >"$ACLSWAP_SYS/idle" 2>/dev/null
    # 写满本轮额度时返回非零，那是正常的收尾而不是错误。
    echo idle >"$ACLSWAP_SYS/writeback" 2>/dev/null

    after=$(awk '{print $3}' "$ACLSWAP_SYS/mm_stat" 2>/dev/null)
    case "$after" in ''|*[!0-9]*) after=$used ;; esac
    if [ "$after" -lt "$used" ] && [ "$reported" = 0 ]; then
        log_msg "aclswap: writeback active, first pass moved $(( (used - after) / 1048576 ))MB out of the pool"
        reported=1
    fi
done
log_msg "aclswap: swap no longer active; writeback driver exiting"
