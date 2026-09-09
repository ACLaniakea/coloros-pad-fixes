#!/system/bin/sh

# This helper used to wake every 30 minutes and start app_process even when
# Pantanal had not changed its database.  That periodic Java launch is small
# in isolation, but it is exactly the kind of avoidable wakeup that competes
# with the first-unlock working set on an 8 GB port.  Keep the self-healing
# path, but make it edge-triggered: one correction after boot, then only when
# SQLite replaces/checkpoints the main card_configs database.
DB=/data/user/0/com.oplus.pantanal.ums/databases/card_configs
DBDIR=${DB%/*}
CLASS=com.aclaniakea.oplusappsuggestionfix.CardProtocolPatcher
APP_PROCESS=/system/bin/app_process64

init_paths() {
    MODDIR="$1"
    PATCHER="$MODDIR/bin/card-protocol-patcher.jar"
    STAMP="$MODDIR/.app-suggestion-last-check"
}

# The comment above assumed the main card_configs file is only closed for
# writing when SQLite checkpoints or replaces the durable snapshot.  That is
# not true on this device: pantanal closes it after ordinary writes, so the
# watcher fired every ~2.7s and the "edge-triggered" design degenerated into a
# 0.37 Hz app_process launcher (2281 consecutive "ALREADY_OK" runs in one log,
# which also rotated the module log away).  A full ART VM at that rate is a
# real CPU and fork-rate cost, and exactly the kind of avoidable wakeup the
# rest of this module refuses to create.
#
# The watch mask stays IN_CLOSE_WRITE: a cloud-side protocol rewrite can land
# as an in-place UPDATE, so CREATE/MOVED_TO would miss it.  Instead the
# verification itself is rate limited.  Self-healing is preserved, it just
# converges within the cooldown instead of within two seconds.
COOLDOWN_OK=300
COOLDOWN_CHANGED=30

# Stamp format: "<epoch> <ok|changed> <suppressed>".  The suppressed counter is
# carried forward so the next real run can report how many events it stood in
# for, instead of logging a line per suppressed event.
read_stamp() {
    STAMP_AT=0
    STAMP_STATE=ok
    STAMP_SKIPPED=0
    [ -f "$STAMP" ] || return 0
    read -r _at _state _skipped 2>/dev/null <"$STAMP"
    case "$_at" in ''|*[!0-9]*) return 0 ;; esac
    STAMP_AT=$_at
    case "$_state" in ok|changed) STAMP_STATE=$_state ;; esac
    case "$_skipped" in ''|*[!0-9]*) STAMP_SKIPPED=0 ;; *) STAMP_SKIPPED=$_skipped ;; esac
}

write_stamp() {
    echo "$(date +%s) $1 $2" >"$STAMP" 2>/dev/null
}

# Returns 0 when the verification should actually run.  Every suppressed event
# only costs this shell, not an ART VM.
cooldown_elapsed() {
    read_stamp
    [ "$STAMP_AT" = 0 ] && return 0
    case "$STAMP_STATE" in
        changed) _need=$COOLDOWN_CHANGED ;;
        *) _need=$COOLDOWN_OK ;;
    esac
    _now=$(date +%s)
    case "$_now" in ''|*[!0-9]*) return 0 ;; esac
    [ "$((_now - STAMP_AT))" -ge "$_need" ] && return 0
    # Bump only the suppressed counter.  The timestamp must NOT move, or a
    # steady event stream would push the cooldown out forever.
    echo "$STAMP_AT $STAMP_STATE $((STAMP_SKIPPED + 1))" >"$STAMP" 2>/dev/null
    return 1
}

apply_fix() {
    read_stamp
    output=$(CLASSPATH="$PATCHER" "$APP_PROCESS" /system/bin "$CLASS" 2>&1)
    result=$?
    _for=""
    [ "$STAMP_SKIPPED" -gt 0 ] && _for=" (stood in for $STAMP_SKIPPED suppressed events)"
    log_msg "app-suggestion patch rc=$result $output$_for"
    case "$output" in
        *ALREADY_OK*) write_stamp ok 0 ;;
        *) write_stamp changed 0 ;;
    esac
    if [ "$result" -eq 10 ]; then
        am force-stop com.heytap.speechassist >/dev/null 2>&1
        am force-stop com.oplus.pantanal.ums >/dev/null 2>&1
        sleep 8
        return 0
    fi
    [ "$result" -eq 0 ]
}

# inotifyd invokes the handler as: <event> <watched-path> <filename>.
# Derive MODDIR from the installed script, so the persistent monitor does not
# need a second shell wrapper or an environment that can become stale after a
# module update.
if [ "$#" -ge 3 ]; then
    EVENT="$1"
    CHANGED="$3"
    # WAL/SHM files change during ordinary card rendering and would make this
    # watcher noisier than the old timer.  The main database changes only when
    # SQLite checkpoints/replaces the durable snapshot, which is the useful
    # recovery edge for a cloud-side rewrite.
    case "$CHANGED" in
        card_configs) ;;
        *) exit 0 ;;
    esac
    MODDIR=${0%/*}/..
    init_paths "$MODDIR"
    . "$MODDIR/common.sh"
    # SQLite commonly emits several adjacent writes. inotifyd dispatches
    # handlers serially; a short quiet period coalesces that burst into one
    # verification instead of repeatedly starting app_process.
    cooldown_elapsed || exit 0
    sleep 2
    apply_fix
    exit 0
fi

init_paths "$1"
. "$MODDIR/common.sh"
until [ "$(getprop sys.boot_completed)" = 1 ] && [ -f "$DB" ]; do sleep 5; done
sleep 45
apply_fix

# A blocking inotifyd has no periodic timer and consumes no CPU while the
# database is unchanged.  If the directory disappears during an app update,
# exit cleanly; KernelSU's next boot invokes this helper again.
if command -v inotifyd >/dev/null 2>&1 && [ -d "$DBDIR" ]; then
    log_msg "app-suggestion patch: initial verification complete; watching SQLite updates"
    exec inotifyd "$0" "$DBDIR:w"
fi

log_msg "app-suggestion patch: inotifyd/database directory unavailable; initial verification only"
exit 0
