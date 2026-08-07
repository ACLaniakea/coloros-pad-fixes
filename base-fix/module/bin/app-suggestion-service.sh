#!/system/bin/sh

MODDIR=$1
PATCHER="$MODDIR/bin/card-protocol-patcher.jar"
DB=/data/user/0/com.oplus.pantanal.ums/databases/card_configs
WAL="$DB-wal"
CLASS=com.aclaniakea.oplusappsuggestionfix.CardProtocolPatcher

db_signature() {
    stat -c '%Y:%s' "$DB" 2>/dev/null
    stat -c '%Y:%s' "$WAL" 2>/dev/null
}

apply_fix() {
    output=$(CLASSPATH="$PATCHER" app_process /system/bin "$CLASS" 2>&1)
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
sleep 15
last_signature=
ticks=0
while :; do
    current_signature=$(db_signature)
    ticks=$((ticks + 1))
    if [ "$current_signature" != "$last_signature" ] || [ "$ticks" -ge 5 ]; then
        if apply_fix; then
            last_signature=$(db_signature)
            ticks=0
        else
            last_signature=
        fi
    fi
    sleep 60
done
