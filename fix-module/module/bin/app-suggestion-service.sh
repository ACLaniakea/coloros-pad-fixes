#!/system/bin/sh

# The protocol correction is a boot-time compatibility repair.  It must not
# remain resident: Pantanal legitimately rewrites its card database for cloud
# content, and observing those writes makes the launcher refresh suggestions.
DB=/data/user/0/com.oplus.pantanal.ums/databases/card_configs
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

init_paths "$1"
. "$MODDIR/common.sh"
until [ "$(getprop sys.boot_completed)" = 1 ] && [ -f "$DB" ]; do sleep 5; done
sleep 45
apply_fix
# Do not watch card_configs or restart Pantanal after boot.  A cloud/database
# update can replace the repaired rows, but keeping Dock suggestions stable is
# more important than continuously reapplying this nonessential compatibility
# workaround; the next boot performs one fresh verification.
log_msg "app-suggestion patch: one-shot verification complete"
exit 0
