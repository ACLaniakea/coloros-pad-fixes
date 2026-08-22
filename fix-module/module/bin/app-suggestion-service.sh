#!/system/bin/sh

MODDIR=$1
PATCHER="$MODDIR/bin/card-protocol-patcher.jar"
DB=/data/user/0/com.oplus.pantanal.ums/databases/card_configs
WAL="$DB-wal"
CLASS=com.aclaniakea.oplusappsuggestionfix.CardProtocolPatcher
APP_PROCESS=/system/bin/app_process64

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

. "$MODDIR/common.sh"
until [ "$(getprop sys.boot_completed)" = 1 ] && [ -f "$DB" ]; do sleep 5; done
# The database WAL changes during ordinary Pantanal activity. Treating that as
# a repair signal made this script launch app_process every minute forever,
# feeding PackageManager/system_server work long after the protocol was fixed.
# Repair once after boot, then retain a very-low-frequency cloud-rewrite
# safety check so the original self-healing behavior is not lost.
sleep 45
while :; do
    apply_fix
    sleep 1800
done
