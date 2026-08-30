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

for policy in 0 1 5; do
    [ -d "/sys/devices/system/cpu/cpufreq/policy$policy" ] || \
        abort "缺少 policy$policy，拒绝安装以免错误调度"
done
# policy 编号是 cpufreq 的 leader CPU，不是性能簇序号。部分兼容内核把
# CPU1-4 合成一个 policy1，此时没有 policy3 仍是合法的 1+4+1 拓扑。
# 运行期会自动使用出现的 policy3；安装阶段绝不能因它尚未注册或被合并而拒装。
# 只看"policy3 在不在"还不够：那只能区分分离/合并两种写法，说明不了 CPU1-4
# 是不是真的都被管到。直接读 related_cpus 数一遍，缺核就明确警告——不中止，
# 因为缺的那几颗本来也没有可写节点，中止只会连能配的部分一起丢。
_mid=$(for _p in 1 3; do
    cat "/sys/devices/system/cpu/cpufreq/policy$_p/related_cpus" 2>/dev/null
done | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -u | tr '\n' ' ')
if [ -d /sys/devices/system/cpu/cpufreq/policy3 ]; then
    ui_print "- 中核频域：分离（policy1 + policy3），覆盖 CPU [$_mid]"
else
    ui_print "- 中核频域：合并（policy1），覆盖 CPU [$_mid]"
fi
for _c in 1 2 3 4; do
    case " $_mid " in
        *" $_c "*) ;;
        *) ui_print "! 警告：CPU$_c 不在任何中核 cpufreq policy 里，它的档位参数不会生效" ;;
    esac
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
