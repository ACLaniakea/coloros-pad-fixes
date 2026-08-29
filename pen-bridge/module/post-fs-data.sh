#!/system/bin/sh

# The service writes the vendor pen-wakeup proc nodes once after boot/CPS is
# ready; this early stage only pins the LSPosed hook path.
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

# 刷新率配置（refresh_rate_config.xml）的 bind 已迁回 fix 模块。
# 它是整机显示基线，与笔无关：笔在用时的 120Hz 由原厂
# OplusRefreshRatePolicyImpl 依 settings_enable_oppo_pencil 自行投票，
# 本模块只负责把那个键写对。放在这里曾导致 ratemagic 被误删 144，
# 面板被长期钉在 60Hz——详见 fix-module/module/post-fs-data.sh 里的说明。
exit 0
