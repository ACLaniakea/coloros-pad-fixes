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
}

apply_fix() {
    output=$(CLASSPATH="$PATCHER" "$APP_PROCESS" /system/bin "$CLASS" 2>&1)
    result=$?
    log_msg "app-suggestion patch rc=$result $output"
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
