#!/system/bin/sh

# Keep the TB710FU global refresh policy in this independently updateable
# Root package. It is a display-policy bind only at this early stage; the
# service writes the vendor pen-wakeup proc nodes once after boot/CPS is ready.
MODDIR=${0%/*}

# LSPosed parses enabled modules before PackageManager restores random
# /data/app paths on this port. Pin the Pen Hook to the signed copy embedded
# in this KernelSU module before zygote/system_server requests its module list.
LSP_DB=/data/adb/lspd/config/modules_config.db
LSP_APK="$MODDIR/hook/PenBridge-Hook.apk"
LSP_SYNC="$MODDIR/bin/lsposed-path-sync.jar"
if [ -f "$LSP_DB" ] && [ -f "$LSP_APK" ] && [ -f "$LSP_SYNC" ]; then
    chown 0:0 "$LSP_APK" "$LSP_SYNC"
    chmod 0644 "$LSP_APK" "$LSP_SYNC"
    chcon u:object_r:system_file:s0 "$LSP_APK" "$LSP_SYNC" 2>/dev/null
    sync_attempt=0
    while [ "$sync_attempt" -lt 5 ]; do
        if CLASSPATH="$LSP_SYNC" app_process /system/bin \
                com.aclaniakea.tools.LsposedPathSync "$LSP_DB" "$LSP_APK" \
                com.aclaniakea.lenovopenbridge \
                system com.coloros.note com.oplus.exsystemservice \
                com.oplus.healthservice com.heytap.mydevices com.oplus.ipemanager \
                com.oplus.wirelesssettings com.oplus.screenshot >/dev/null 2>&1; then
            break
        fi
        sync_attempt=$((sync_attempt + 1))
        sleep 1
    done
fi

REFRESH_TARGET=/my_product/etc/refresh_rate_config.xml
REFRESH_PAYLOAD="$MODDIR/payload/refresh_rate_config.tb710fu.xml"
if [ -f "$REFRESH_TARGET" ] && [ -f "$REFRESH_PAYLOAD" ]; then
    chown 0:0 "$REFRESH_PAYLOAD"
    chmod 0644 "$REFRESH_PAYLOAD"
    chcon u:object_r:system_file:s0 "$REFRESH_PAYLOAD" 2>/dev/null
    mount --bind "$REFRESH_PAYLOAD" "$REFRESH_TARGET" 2>/dev/null
fi
exit 0
