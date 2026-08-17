#!/system/bin/sh

module_version=$(sed -n 's/^version=//p' "$MODPATH/module.prop" | head -n 1)
ui_print "- 联想平板 Pro GT - ColorOS 修复模块 ${module_version:-unknown} (ACLaniakea)"
ui_print "- 仅适用于 SM8650Q / pineapple 平台"
ui_print "- 修正 AON QNN 生命周期、环境光自适应与屏幕常亮关联项"
ui_print "- 恢复小布 BWV 语音唤醒链路与 HAL 级录音增益"
ui_print "- 绑定显示色域映射与混音器配置"
ui_print "- 修复 Tango 32 位兼容、序列号空项与性能 HAL 冲突"
ui_print "- 调优：全局 swappiness=20 防关键进程换出，禁用 osense 主动换出"
ui_print "- 合并自：coloros_port_base_fix + coloros_port_tuning"
ui_print "- LSPosed Hook APK 独立安装，本模块不包含 APK"
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
