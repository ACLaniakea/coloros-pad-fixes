#!/system/bin/sh

MODDIR=${0%/*}

[ -f "$MODDIR/app-suggestion.pid" ] && kill "$(cat "$MODDIR/app-suggestion.pid")" 2>/dev/null
[ -f "$MODDIR/voice-power-guard.pid" ] && kill "$(cat "$MODDIR/voice-power-guard.pid")" 2>/dev/null
[ -f "$MODDIR/kgsl-state-sync.pid" ] && kill "$(cat "$MODDIR/kgsl-state-sync.pid")" 2>/dev/null
# 停掉同步器后必须把状态全部还原：内核不会自己把 state 写回 foreground，
# 留下 background 标记会让这些进程的显存持续被 shrinker 拿走。
for _d in /sys/class/kgsl/kgsl/proc/*; do
    [ -w "$_d/state" ] && printf 'foreground' >"$_d/state" 2>/dev/null
done
resetprop persist.sys.horae.enable 0
resetprop -p persist.sys.tango_zygote32.start 1

# ============================================================================
# 解除本模块建立的全部 bind mount。
#
# 上一版这份清单只有 5 条，漏了十几个——卸载后那些 bind 还挂着，虽然重启会
# 自然消失，但在「卸载后不重启直接排查」时会误导人，以为模块还在起作用。
# 清单按 post-fs-data.sh / service.sh 里 `mount --bind` 的出现顺序整理，
# 改动那两个脚本的挂载点时**必须同步改这里**。
#
# 不再恢复任何 VM/memcg 参数：模块已经不写它们了，那些值全部由
# /product/bin/init.oplus.nandswap.sh 与 OSense 掌管，卸载时乱写反而是破坏。
# ============================================================================

# 1) vendor 配置（6 核 4 簇拓扑 + 热策略）
for conf in "$MODDIR"/payload/thermal/thermal-engine_*.conf; do
    [ -f "$conf" ] && umount "/vendor/etc/$(basename "$conf")" 2>/dev/null
done
umount /vendor/etc/perf/targetconfig.xml 2>/dev/null

# 2) OSense 配置（现在只 bind 这两份；另四份已交还原厂）
umount /my_stock/etc/extension/sys_memory_nirvana_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_osense_memory_decisionmaker_config.xml 2>/dev/null
# 历史遗留：老版本还 bind 过下面四份，卸载旧版时一并解开
umount /my_stock/etc/extension/sys_osense_memory_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_osense_feature_common_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_mm_swap_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_osense_io_decisionmaker_config.xml 2>/dev/null

# 3) OPlus 特性表（派生版，可能被叠了不止一层）
while grep -q ' /my_stock/etc/extension/com.oplus.oplus-feature.xml ' /proc/mounts 2>/dev/null; do
    umount /my_stock/etc/extension/com.oplus.oplus-feature.xml 2>/dev/null || break
done

# 4) 音频：杜比路由策略、TB710FU 采集增益 mixer
umount /system_ext/etc/Multimedia_Daemon_List.xml 2>/dev/null
umount /vendor/etc/audio/sku_pineapple/mixer_paths_pineapple_mtp.xml 2>/dev/null

# 5) AON 原生运行时与 32 位 libdl
umount /my_product/app/AONService/lib/arm64 2>/dev/null
umount /apex/com.android.runtime/lib/bionic/libdl.so 2>/dev/null

# 6) 传感器能力表、小布唤醒窗口
umount /my_product/etc/permissions/oplus.product.display_features.xml 2>/dev/null
umount /my_product/etc/OVMS_settings.xml 2>/dev/null

# 7) 历史遗留：post_boot 补丁的 bind 已撤销，卸载旧版时仍需解开
umount /vendor/bin/init.qcom.post_boot.sh 2>/dev/null
umount /vendor/bin/init.kernel.post_boot.sh 2>/dev/null
