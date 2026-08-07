#!/system/bin/sh

MODDIR=${0%/*}
echo "机型: $(getprop ro.product.model)"
echo "Horae 服务: $(getprop init.svc.horae)"
echo "Tango 32 位 Zygote: $(getprop init.svc.zygote_tango)"
echo "GameOpt 服务: $(getprop init.svc.gameopt_hal_service-1-0)"
echo "基础修复 Hooks: $(dumpsys package com.aclaniakea.colorosostatsguard 2>/dev/null | sed -n 's/.*versionName=//p' | head -1)"
tail -40 "$MODDIR/base-fix.log" 2>/dev/null
