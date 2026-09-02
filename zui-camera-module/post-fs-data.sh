#!/system/bin/sh
# Pin only the standalone ZUI Camera LSPosed module before app specialization.
MODDIR=${0%/*}
DB=/data/adb/lspd/config/modules_config.db
APK="$MODDIR/hook/ZUI-Camera-Compat.apk"
SYNC="$MODDIR/bin/lsposed-path-sync.jar"

if [ -f "$DB" ] && [ -f "$APK" ] && [ -f "$SYNC" ]; then
  chown 0:0 "$APK" "$SYNC"
  chmod 0644 "$APK" "$SYNC"
  chcon u:object_r:system_file:s0 "$APK" "$SYNC" 2>/dev/null
  CLASSPATH="$SYNC" app_process /system/bin com.aclaniakea.tools.LsposedPathSync \
    "$DB" "$APK" com.aclaniakea.zuicameracompat >/dev/null 2>&1
fi
