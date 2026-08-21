#!/system/bin/sh

module_version=$(sed -n 's/^version=//p' "$MODPATH/module.prop" | head -n 1)
ui_print "- 联想平板 Pro GT - ColorOS 修复模块 ${module_version:-unknown} (ACLaniakea)"
ui_print "- 仅适用于 SM8650Q / pineapple 平台"
ui_print "- 修正 AON QNN 生命周期、环境光自适应与屏幕常亮关联项"
ui_print "- 恢复小布 BWV 语音唤醒链路与 HAL 级录音增益"
ui_print "- 绑定显示色域映射与混音器配置"
ui_print "- 修复 Tango 32 位兼容、序列号空项与性能 HAL 冲突"
ui_print "- 调优：开机=5，稳定后普通=10/冷后台=20/活跃与 system_server=5"
ui_print "- 关闭实测会引发交互换页风暴的 OSense 主动换出，保留按需 ZRAM"
ui_print "- AON namespace 挂载改用进程启动事件，移除 20Hz pidof 轮询"
ui_print "- 恢复原厂 ROMUpdate Provider，修复 Scene 无障碍策略开关"
ui_print "- 用修正 SoC-696 六核配置重载 perf HAL 并保持运行，消除 perf AIDL binder 风暴"
ui_print "- 开机一次性恢复 governor 与 min/max 频点到本机硬件范围"
ui_print "- 抬高启动/渲染路径 DEBUG 日志级别，消除日志刷屏开销"
ui_print "- 合并自：coloros_port_base_fix + coloros_port_tuning"
ui_print "- 内置稳定 Hook APK 副本，消除 /data/app 冷启动路径竞态"
ui_print "- Hook APK 推荐作用域：android、com.aiunit.aon、com.heytap.speechassist、com.oplus.ovoicemanager.wakeup 等（scope.list）"
ui_print "- 作用域请用户在 LSPosed 管理器手动勾选"

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

if [ -f /data/adb/lspd/config/modules_config.db ] && \
        [ -f "$MODPATH/bin/lsposed-path-sync.jar" ] && \
        [ -f "$MODPATH/hook/BaseFix-Hook.apk" ]; then
    chcon u:object_r:system_file:s0 "$MODPATH/bin/lsposed-path-sync.jar" \
        "$MODPATH/hook/BaseFix-Hook.apk" 2>/dev/null
    if CLASSPATH="$MODPATH/bin/lsposed-path-sync.jar" app_process /system/bin \
            com.aclaniakea.tools.LsposedPathSync \
            /data/adb/lspd/config/modules_config.db \
            "$MODPATH/hook/BaseFix-Hook.apk" >/dev/null 2>&1; then
        ui_print "- LSPosed Hook 路径已固定，system_server 下次冷启动直接加载"
    else
        ui_print "! LSPosed 路径固定将在 post-fs-data 阶段重试"
    fi
fi
