#!/system/bin/sh
# One-shot A/B: protect wake-critical memcgs only while the display is off.
# The original swappiness values are restored shortly after the next wake.

OUT=${1:-/data/local/tmp/wake-memcg-protect-once.log}
ACTIVE=/dev/memcg/apps/active/memory.swappiness
SYSTEM=/dev/memcg/apps/systemserver/memory.swappiness

read_value() {
    cat "$1" 2>/dev/null
}

ACTIVE_OLD=$(read_value "$ACTIVE")
SYSTEM_OLD=$(read_value "$SYSTEM")

restore() {
    [ -n "$ACTIVE_OLD" ] && echo "$ACTIVE_OLD" >"$ACTIVE" 2>/dev/null
    [ -n "$SYSTEM_OLD" ] && echo "$SYSTEM_OLD" >"$SYSTEM" 2>/dev/null
    echo "RESTORED $(date +%s) active=$(read_value "$ACTIVE") system=$(read_value "$SYSTEM")" >>"$OUT"
}

trap restore EXIT INT TERM

echo "ARMED $(date +%s) active=$ACTIVE_OLD system=$SYSTEM_OLD" >"$OUT"
echo 0 >"$ACTIVE"
echo 0 >"$SYSTEM"
echo "PROTECTED $(date +%s) active=$(read_value "$ACTIVE") system=$(read_value "$SYSTEM")" >>"$OUT"

while ! dumpsys power | grep -q 'mWakefulness=Asleep'; do sleep 2; done
echo "SLEEP $(date +%s)" >>"$OUT"
while ! dumpsys power | grep -q 'mWakefulness=Awake'; do sleep 1; done

echo "WAKE $(date +%s)" >>"$OUT"
grep -E '^(pswpin|pswpout|allocstall_normal|allocstall_movable|pgscan_direct|pgsteal_direct|compact_stall) ' /proc/vmstat >>"$OUT"
sleep 15
