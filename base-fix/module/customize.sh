#!/system/bin/sh

module_version=$(sed -n 's/^version=//p' "$MODPATH/module.prop" | head -n 1)
ui_print "- 联想平板 Pro GT - ColorOS 基础修复 ${module_version:-unknown} (ACLaniakea)"
ui_print "- 仅适用于 SM8650Q / pineapple 平台"
ui_print "- 修正 AON QNN 生命周期、环境光自适应与屏幕常亮关联项"
ui_print "- 恢复小布 BWV 语音唤醒链路与 HAL 级录音增益"
ui_print "- 绑定显示色域映射与混音器配置"
ui_print "- 修复 Tango 32 位兼容、序列号空项与性能 HAL 冲突"
ui_print "- LSPosed Hook APK 独立安装，本模块不包含 APK"
ui_print "- Hook APK 推荐作用域：android、com.aiunit.aon、com.heytap.speechassist、com.oplus.ovoicemanager.wakeup 等（scope.list）"
ui_print "- 作用域请用户在 LSPosed 管理器手动勾选"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
