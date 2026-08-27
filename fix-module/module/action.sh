#!/system/bin/sh

MODDIR=${0%/*}
echo "机型: $(getprop ro.product.model)"
echo "Horae 服务: $(getprop init.svc.horae)"
echo "Tango 32 位 Zygote: $(getprop init.svc.zygote_tango)"
echo "GameOpt 服务: $(getprop init.svc.gameopt_hal_service-1-0)"
echo "基础修复 Hooks: $(dumpsys package com.aclaniakea.colorosostatsguard 2>/dev/null | sed -n 's/.*versionName=//p' | head -1)"
echo "内存/交换状态（均由原厂 init.oplus.nandswap.sh 与 OSense 掌管）:"
echo "  swappiness=$(cat /proc/sys/vm/swappiness 2>/dev/null)"
echo "  watermark=$(cat /proc/sys/vm/watermark_scale_factor 2>/dev/null)"
echo "  min_free_kbytes=$(cat /proc/sys/vm/min_free_kbytes 2>/dev/null)"
ram_kb=$(awk '/^MemTotal:/{print $2; exit}' /proc/meminfo 2>/dev/null)
swap_kb=$(awk '/^\/dev\/.*zram/{sum += $3} END{print sum+0}' /proc/swaps 2>/dev/null)
echo "  detected RAM=${ram_kb:-0}kB active_zram_swap=${swap_kb:-0}kB (ROM-owned; module does not resize)"
echo "  memcg root swappiness=$(cat /dev/memcg/memory.swappiness 2>/dev/null)"
echo "  apps root memcg swappiness=$(cat /dev/memcg/apps/memory.swappiness 2>/dev/null)"
echo "  zram 已用: $(awk '{printf "%.1fGB", $1/1073741824}' /sys/block/zram0/mm_stat 2>/dev/null)"
echo "HybridSwap:"
if [ -f /sys/block/zram0/hybridswap_core_enable ]; then
    echo "  core_enable=$(cat /sys/block/zram0/hybridswap_core_enable 2>/dev/null)"
    echo "  loop_device=$(cat /sys/block/zram0/hybridswap_loop_device 2>/dev/null)"
    echo "  swapd_pid=$(cat /dev/memcg/memory.swapd_pid 2>/dev/null)"
    echo "  zram2ufs 额度(level1)=$(awk '/level 1 ub_zram2ufs_ratio/{print $NF}' /dev/memcg/memory.swapd_memcgs_param 2>/dev/null) mem2zram(level1)=$(awk '/level 1 ub_mem2zram_ratio/{print $NF}' /dev/memcg/memory.swapd_memcgs_param 2>/dev/null)"
    # 节点名是 hybridswap_stat_snap，不是 hybridswap_stat；写错的话这里恒为空。
    grep -E '^(reclaimin_cnt|reclaimin_bytes|batchout_cnt)' \
        /sys/block/zram0/hybridswap_stat_snap 2>/dev/null | sed 's/^/  /'
    grep -E '^fail_record_num' /sys/block/zram0/hybridswap_report 2>/dev/null | sed 's/^/  /'
    # ESU_C = eswap 已用（回写真的发生了才会非 0）；ZSU_O = zram 里的原始数据量。
    grep -E '^(EST|ESU_C|ZSU_O)' /sys/block/zram0/hybridswap_meminfo 2>/dev/null | sed 's/^/  /'
else
    echo "  未加载（缺 oplus_mm_hybridswap_zram）"
fi
echo "外壳温兼容模块（对应缺失的 oplus_bsp_horae_shell_temp）:"
grep -E '^oplus_shell_temp_compat ' /proc/modules 2>/dev/null || echo "  未加载"
echo "PSI:"
cat /proc/pressure/cpu /proc/pressure/memory /proc/pressure/io 2>/dev/null
tail -40 "$MODDIR/fix-module.log" 2>/dev/null
