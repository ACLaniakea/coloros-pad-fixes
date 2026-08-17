#!/system/bin/sh

MODDIR=${0%/*}

if [ -f "$MODDIR/cpu-limit-guard.pid" ]; then
    kill "$(cat "$MODDIR/cpu-limit-guard.pid")" 2>/dev/null
fi
[ -f "$MODDIR/app-suggestion.pid" ] && kill "$(cat "$MODDIR/app-suggestion.pid")" 2>/dev/null
[ -f "$MODDIR/voice-power-guard.pid" ] && kill "$(cat "$MODDIR/voice-power-guard.pid")" 2>/dev/null
resetprop persist.sys.horae.enable 0
resetprop -p persist.sys.tango_zygote32.start 1

# 调优部分卸载还原：解除 osense 配置覆盖，把 active/systemserver 的
# swappiness 重新对齐 ROM 基线（等价于模块最后一次 service 写入后的状态）。
umount /my_stock/etc/extension/sys_osense_memory_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_osense_io_decisionmaker_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_osense_memory_decisionmaker_config.xml 2>/dev/null
umount /vendor/bin/init.qcom.post_boot.sh 2>/dev/null
umount /vendor/bin/init.kernel.post_boot.sh 2>/dev/null
if [ -w /dev/memcg/apps/active/memory.swappiness ]; then
    cat /proc/sys/vm/swappiness >/dev/memcg/apps/active/memory.swappiness 2>/dev/null
fi
if [ -w /dev/memcg/apps/systemserver/memory.swappiness ]; then
    cat /proc/sys/vm/swappiness >/dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null
fi
