#!/system/bin/sh

MODDIR=${0%/*}
echo "机型: $(getprop ro.product.model)"
echo "Horae 服务: $(getprop init.svc.horae)"
echo "Tango 32 位 Zygote: $(getprop init.svc.zygote_tango)"
echo "GameOpt 服务: $(getprop init.svc.gameopt_hal_service-1-0)"
echo "基础修复 Hooks: $(dumpsys package com.aclaniakea.colorosostatsguard 2>/dev/null | sed -n 's/.*versionName=//p' | head -1)"
echo "VM 状态:"
echo "  swappiness=$(cat /proc/sys/vm/swappiness 2>/dev/null)"
echo "  min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null)"
echo "  active memcg swappiness=$(cat /dev/memcg/apps/active/memory.swappiness 2>/dev/null)"
echo "  systemserver memcg swappiness=$(cat /dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null)"
echo "  zram 已用: $(awk '{printf "%.1fGB", $1/1073741824}' /sys/block/zram0/mm_stat 2>/dev/null)"
tail -40 "$MODDIR/fix-module.log" 2>/dev/null
