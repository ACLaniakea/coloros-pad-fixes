#!/system/bin/sh

SKIPUNZIP=0

ui_print "- 安装 SM8650Q 专用调度（Scene 标准接口）"
ui_print "- 模式：省电 / 均衡 / 性能 / 极速"
ui_print "- Scene 负责分应用切换；模块不运行额外常驻守护"

soc=$(getprop ro.soc.model)
platform=$(getprop ro.board.platform)
present=$(cat /sys/devices/system/cpu/present 2>/dev/null)
case "$soc/$platform/$present" in
    *SM8650Q*/*pineapple*/0-5) ;;
    *) abort "仅支持 SM8650Q/pineapple 六核设备，当前：$soc/$platform/$present" ;;
esac

for policy in 0 1 3 5; do
    [ -d "/sys/devices/system/cpu/cpufreq/policy$policy" ] || \
        abort "缺少 policy$policy，拒绝安装以免错误调度"
done
[ ! -d /sys/devices/system/cpu/cpufreq/policy7 ] || \
    abort "检测到非六核拓扑，拒绝安装"

set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in customize.sh post-fs-data.sh service.sh action.sh uninstall.sh scheduler/main.sh scheduler/install-interface.sh payload/powercfg.sh; do
    set_perm "$MODPATH/$script" 0 0 0755
done

MODDIR="$MODPATH" "$MODPATH/scheduler/install-interface.sh" install || \
    abort "Scene 调度接口部署失败"
ui_print "- 已注册为：SM8650Q 专用调度"
ui_print "- 下次重启后 Scene 会识别第三方调度源"
