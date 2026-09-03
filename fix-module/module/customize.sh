#!/system/bin/sh

module_version=$(sed -n 's/^version=//p' "$MODPATH/module.prop" | head -n 1)
ui_print "- 联想平板 Pro GT - ColorOS 修复模块 ${module_version:-unknown} (ACLaniakea)"
ui_print "- 仅适用于 SM8650Q / pineapple 平台"
ui_print "- 修正 AON QNN 生命周期、环境光自适应与屏幕常亮关联项"
ui_print "- 关闭不兼容的小布 BWV 唤醒并隐藏其设置入口（旧代码已归档）"
ui_print "- 绑定显示色域映射与混音器配置"
ui_print "- 修复 Tango 32 位兼容、序列号空项与性能 HAL 冲突"
ui_print "- 内存与交换整体交还原厂：不再写 swappiness/watermark/min_free/KGSL"
ui_print "- 一加 BSP 内核模块已跑通，HybridSwap 由原厂 init.oplus.nandswap.sh 接管"
ui_print "- OSense 覆盖由 6 份缩到 2 份，且均以原厂配置为基线做最小改动"
ui_print "- 内存扩展交还 Settings.Secure 与 Athena，模块不再回写 nandswap 镜像属性"
ui_print "- 关闭 WLAN 固件诊断日志，省去约 1.3MB/s 的闪存写入"
ui_print "- 保留 shell-temp 兼容模块（对应仍缺失的 oplus_bsp_horae_shell_temp）"
ui_print "- 关闭 HMBIRD 云控（本机确无 hmbird_sched / sched_ext）"
ui_print "- AON namespace 挂载改用进程启动事件，移除 20Hz pidof 轮询"
ui_print "- 恢复原厂 ROMUpdate Provider，修复 Scene 无障碍策略开关"
ui_print "- 用修正 SoC-696 六核配置重载 perf HAL 并保持运行，消除 perf AIDL binder 风暴"
ui_print "- 开机一次性恢复 governor 与 min/max 频点到本机硬件范围"
ui_print "- 抬高启动/渲染路径 DEBUG 日志级别，消除日志刷屏开销"
ui_print "- 去除每次开机 Hook dexopt，语音改事件驱动、应用建议改 30 分钟兜底"
ui_print "- 合并自：coloros_port_base_fix + coloros_port_tuning"
ui_print "- 内置稳定 Hook APK 副本，消除 /data/app 冷启动路径竞态"
ui_print "- 自动补齐 Hook 白名单：系统框架、Settings、AON、语音、电池等 scope.list 作用域"
ui_print "- 内置 CryptoEng 分流代理：保留软件 HAL，补齐 10003 密钥派生"
ui_print "- 恢复原厂 ROMUpdate Provider，修复 Scene 无障碍策略开关"
ui_print "- 用修正 SoC-696 六核配置重载 perf HAL 并保持运行，消除 perf AIDL binder 风暴"
ui_print "- 开机一次性恢复 governor 与 min/max 频点到本机硬件范围"
ui_print "- 抬高启动/渲染路径 DEBUG 日志级别，消除日志刷屏开销"
ui_print "- 去除每次开机 Hook dexopt，语音改事件驱动、应用建议改 30 分钟兜底"
ui_print "- 合并自：coloros_port_base_fix + coloros_port_tuning"
ui_print "- 内置稳定 Hook APK 副本，消除 /data/app 冷启动路径竞态"
ui_print "- 自动补齐 Hook 白名单：系统框架、Settings、AON、语音、电池等 scope.list 作用域"

# 预编译 Hook APK：system_server 是开机最早拉起的进程，若 dex 尚未优化，
# LSPosed 注入时偶发 I/O error，环境光/色温 bridge 不加载。安装时先编译，
# 保证下次完整重启的首次注入即成功，无需软重启 zygote。
cmd package compile -m speed -f com.aclaniakea.colorosostatsguard >/dev/null 2>&1
ui_print "- Hook APK dex 已预编译（com.aclaniakea.colorosostatsguard）"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/hook" 0 0 0755 0644
set_perm_recursive "$MODPATH/cryptoeng" 0 0 0755 0755
set_perm "$MODPATH/cryptoeng/setup.sh" 0 0 0755

# CryptoEng 已并入主模块；旧独立模块保留在磁盘上便于回退，但必须停用，
# 否则两个代理会竞争同一个 vendor binder 服务。
if [ -d /data/adb/modules/cryptoeng_hal_fix ]; then
    touch /data/adb/modules/cryptoeng_hal_fix/disable
    ui_print "- 已停用旧 CryptoEng 独立模块（功能已并入主模块）"
fi

if [ -f /data/adb/lspd/config/modules_config.db ] && \
        [ -f "$MODPATH/bin/lsposed-path-sync.jar" ] && \
        [ -f "$MODPATH/hook/BaseFix-Hook.apk" ]; then
    chcon u:object_r:system_file:s0 "$MODPATH/bin/lsposed-path-sync.jar" \
        "$MODPATH/hook/BaseFix-Hook.apk" 2>/dev/null
    if CLASSPATH="$MODPATH/bin/lsposed-path-sync.jar" app_process /system/bin \
            com.aclaniakea.tools.LsposedPathSync \
            /data/adb/lspd/config/modules_config.db \
            "$MODPATH/hook/BaseFix-Hook.apk" >/dev/null 2>&1; then
        ui_print "- LSPosed Hook 路径与白名单已固定，system_server 下次冷启动直接加载"
    else
        ui_print "! LSPosed 路径固定将在 post-fs-data 阶段重试"
    fi
fi
