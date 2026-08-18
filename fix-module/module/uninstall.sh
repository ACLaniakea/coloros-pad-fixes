#!/system/bin/sh

MODDIR=${0%/*}

[ -f "$MODDIR/app-suggestion.pid" ] && kill "$(cat "$MODDIR/app-suggestion.pid")" 2>/dev/null
[ -f "$MODDIR/voice-power-guard.pid" ] && kill "$(cat "$MODDIR/voice-power-guard.pid")" 2>/dev/null
resetprop persist.sys.horae.enable 0
resetprop -p persist.sys.tango_zygote32.start 1

# 调优部分卸载还原：解除 osense 配置覆盖，并恢复 ColorOS/QTI 的 memcg
# swappiness=100 与大内存设备默认 min_free_kbytes=11584。
umount /my_stock/etc/extension/sys_osense_memory_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_osense_io_decisionmaker_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_osense_memory_decisionmaker_config.xml 2>/dev/null
umount /vendor/bin/init.qcom.post_boot.sh 2>/dev/null
umount /vendor/bin/init.kernel.post_boot.sh 2>/dev/null
for memcg_file in /dev/memcg/memory.swappiness /dev/memcg/apps/memory.swappiness \
        /dev/memcg/apps/*/memory.swappiness /dev/memcg/system/memory.swappiness; do
    [ -w "$memcg_file" ] && echo 100 >"$memcg_file" 2>/dev/null
done
[ -w /proc/sys/vm/min_free_kbytes ] && echo 11584 >/proc/sys/vm/min_free_kbytes 2>/dev/null
