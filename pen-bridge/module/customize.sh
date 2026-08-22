#!/system/bin/sh

ui_print "- 联想手写笔桥接 Root 服务 1.1.5（ACLaniakea）"
ui_print "- 开机按已绑定手写笔地址直接调用原厂 CoreService BLE 连接"
ui_print "- 设置页断开同时执行原厂 CoreService、GATT/HID 实际断开"
ui_print "- 统一 Hall/CPS/BLE 硬件充电状态与 ColorOS 设置页热切换"
ui_print "- 已移除自定义 CPS 内核模块，避免异常重启"
ui_print "- 自定义 HID 实际断开/连接与真实 Hall 磁吸弹窗"
ui_print "- HID 控制器仅通过后台服务调用，无桌面启动器图标"
ui_print "- 启动后授予 HID 服务 Bluetooth Connect/Scan 运行时权限"
ui_print "- 磁吸仅控制 CPS/充电/弹窗，蓝牙连接不依赖 Hall"
ui_print "- 支持切换到其他已绑定蓝牙地址的手写笔"
ui_print "- 设备空间手写笔存在状态仅跟随真实蓝牙连接"
ui_print "- Root 仅在开机一次性写入 pen_wakeup 节点，避免 system_server 权限失败"
ui_print "- 连接状态只接受真实 ACL/GATT/Hall/CPS 事件，拒绝强制已连接回放"
ui_print "- 刷新率策略统一绑定：笔场景锁 120Hz，其余场景最高 144Hz"
ui_print "- LSPosed Hook APK 独立安装，不再由 Root 模块携带或同步"
ui_print "- 仅适用于 SM8650Q / pineapple 平台"

# An older revision may have embedded the Hook APK in this same module.
# Remove only those exact legacy paths during the split update. The new Root
# package intentionally has no com.aclaniakea.lenovopenbridge APK of its own.
rm -rf "$MODPATH/system/priv-app/lenovopenbridge" \
       "$MODPATH/system/system_ext/priv-app/lenovopenbridge" 2>/dev/null
rm -f "$MODPATH/system/etc/permissions/privapp-permissions-com.aclaniakea.lenovopenbridge.xml" \
      "$MODPATH/system/system_ext/etc/permissions/privapp-permissions-com.aclaniakea.lenovopenbridge.xml" 2>/dev/null

HIDCTL_APK="$MODPATH/system/priv-app/penhidctl/PenHidCtl.apk"
if [ -f "$HIDCTL_APK" ]; then
    # Keep this APK in the module's priv-app overlay. Installing it with
    # `pm install` here would turn it into /data/app and lose the privileged
    # Bluetooth Host permissions before the next boot package scan.
    ui_print "- HID 实际连接控制器已写入 priv-app，将随系统启动加载"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
[ -f "$MODPATH/bin/pen-cps-gpio" ] && set_perm "$MODPATH/bin/pen-cps-gpio" 0 0 0755

# Remove the legacy kernel bridge from an existing module update as well as
# from a fresh package. The normal CPS/Hall and BLE paths remain intact.
rm -f "$MODPATH/kernel/lenovo_pen_cps_bridge.ko" 2>/dev/null
rmdir "$MODPATH/kernel" 2>/dev/null
