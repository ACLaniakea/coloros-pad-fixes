#!/system/bin/sh

export PATH="/sbin:/system/bin:/system/xbin:/vendor/bin:$PATH"
STATE_DIR=/data/adb/sm8650q-scene-scheduler
STATE_FILE="$STATE_DIR/current_mode"
LOGFILE="$STATE_DIR/scheduler.log"
LOCK="$STATE_DIR/apply.lock"

supported_device() {
    case "$(getprop ro.soc.model)/$(getprop ro.board.platform)/$(cat /sys/devices/system/cpu/present 2>/dev/null)" in
        *SM8650Q*/*pineapple*/0-5) ;;
        *) return 1 ;;
    esac
    # policy3 is optional: some kernels merge the four middle CPUs into
    # policy1.  policy0/1/5 are the required 1+4+1 leaders.
    for policy in 0 1 5; do
        [ -d "/sys/devices/system/cpu/cpufreq/policy$policy" ] || return 1
    done
    [ ! -d /sys/devices/system/cpu/cpufreq/policy7 ]
}

has_split_middle_policy() {
    [ -d /sys/devices/system/cpu/cpufreq/policy3 ]
}

log() {
    mkdir -p "$STATE_DIR"
    size=0
    [ ! -f "$LOGFILE" ] || size=$(wc -c <"$LOGFILE" 2>/dev/null)
    [ "${size:-0}" -lt 131072 ] || mv -f "$LOGFILE" "$LOGFILE.old"
    echo "[$(date '+%F %T')] $*" >>"$LOGFILE"
}

write_node() {
    value=$1
    node=$2
    [ -w "$node" ] || return 0
    current=$(cat "$node" 2>/dev/null)
    [ "$current" = "$value" ] || echo "$value" >"$node" 2>/dev/null
}

write_policy() {
    suffix=$1
    value0=$2
    value1=$3
    value3=$4
    value5=$5
    write_node "$value0" "/sys/devices/system/cpu/cpufreq/policy0/$suffix"
    write_node "$value1" "/sys/devices/system/cpu/cpufreq/policy1/$suffix"
    # policy1/3 use identical values in every profile.  Skipping policy3 on
    # merged-middle kernels is therefore lossless and avoids a false reject.
    has_split_middle_policy && \
        write_node "$value3" "/sys/devices/system/cpu/cpufreq/policy3/$suffix"
    write_node "$value5" "/sys/devices/system/cpu/cpufreq/policy5/$suffix"
}

# 向量的三位分别对应 CPU0->中核、中核->中核、中核->X4 三个迁移边界。
#
# 第一位在源系统里是 90（均衡档）。SM8850 有四颗小核可以铺开负载，等到某个
# 任务吃满一颗小核的 90% 再上迁是合理的；本机只有 CPU0 一颗弱核（容量 379，
# 中核 867、X4 1024），90% 意味着它必然先饱和才会开始搬运。实机确认过这个
# 后果：空闲测试里 CPU0 忙 62% 而 X4 只有 7%。
#
# 交叉采样验证（com.android.settings 冷启动 TotalTime，6 轮轮换顺序取中位）：
#   基线 [90 95 82]        中位 1136 ms
#   只降第一位 [60 95 82]  中位  939 ms
#   三位全降 [60 60 60]    中位  942 ms
# 只降第一位与全降在统计上不可区分，说明收益完全来自弱核这一个边界。因此
# 保留后两位已调好的值：中核之间同容量，压低只会徒增抖动；通往 X4 的门槛
# 另有功耗取舍。均衡档取 60 为实测值，其余档位按同方向等距外推、未单独实测。
write_task_migration() {
    down=$1
    up=$2
    # WALT 拒绝 down >= up 的中间状态。先临时抬高 up，确保不同 Scene 模式
    # 双向切换时两组向量都能原子式落到目标值。
    write_node '100 100 100' /proc/sys/walt/sched_upmigrate
    write_node "$down" /proc/sys/walt/sched_downmigrate
    write_node "$up" /proc/sys/walt/sched_upmigrate
}

find_irq_by_name() {
    name=$1
    awk -v name="$name" '$NF == name { gsub(":", "", $1); print $1 }' /proc/interrupts 2>/dev/null
}

# WLAN 的中断名按队列枚举（pci0_wlan_ce_0..8、pci0_wlan_grp_dp_0..15），
# 且随驱动加载时机变化，只能按前缀匹配。输出 "irq 名字" 两列。
find_irq_by_prefix() {
    prefix=$1
    awk -v p="$prefix" 'index($NF, p) == 1 { gsub(":", "", $1); print $1, $NF }' \
        /proc/interrupts 2>/dev/null
}

# 说明：IRQ 铺核（原 spread_irq_by_prefix / set_irq_cpu）已整体移交 fix 模块的
# apply_sched_baseline —— 原因见那边的注释：调度模块依赖 Scene 主动回调，
# 三天没调用就让 IRQ 亲和全静默失效，基础修复不能挂在这种触发条件上。
# 这里刻意不再保留这两个函数，避免"源码里有、实际不跑"的死代码再次误导排查。
# find_irq_by_name 保留，status() 还在用它做只读展示。

# CPU1-4 掩码；Prime CPU5 不参与网络软中断，留给交互突发。
RPS_CPUS=1e
RPS_SOCK_FLOW_ENTRIES=32768
RPS_FLOW_CNT=4096

apply_net_rps() {
    # 原厂 init.qcom.post_boot.sh 只给 rmnet0/rmnet_ipa0 配了 RPS，wlan 从未配置。
    # 在源机八核上这没问题：QCA 驱动自己的 NAPI 亲和会把 CE/DP 中断铺到大核簇。
    # 搬到本机 1+4+1 六核后，那些中断被 IRQ_NO_BALANCING 钉死在唯一弱核 CPU0
    # （实测 WLAN 合计约 43.6 万次全部落在 CPU0），又没有 RPS 兜底，于是
    # Wi-Fi 收包的协议栈处理与硬件编解码挤在同一颗 379 容量的核上。
    # RPS 只搬 softirq，不触碰硬中断，属于标准内核机制，可随时清零回滚。
    [ -d /sys/class/net ] || return 0

    # rps_flow_cnt 只有在全局 rps_sock_flow_entries 非零时才允许写入，顺序不能反。
    if [ -w /proc/sys/net/core/rps_sock_flow_entries ]; then
        cur=$(cat /proc/sys/net/core/rps_sock_flow_entries 2>/dev/null)
        case "$cur" in ''|*[!0-9]*) cur=0 ;; esac
        if [ "$cur" -lt "$RPS_SOCK_FLOW_ENTRIES" ]; then
            write_node "$RPS_SOCK_FLOW_ENTRIES" /proc/sys/net/core/rps_sock_flow_entries
        fi
    fi

    applied=0
    for dev in wlan0 p2p0; do
        [ -d "/sys/class/net/$dev" ] || continue
        for q in /sys/class/net/"$dev"/queues/rx-*; do
            [ -w "$q/rps_cpus" ] || continue
            [ "$(cat "$q/rps_cpus" 2>/dev/null)" = "$RPS_CPUS" ] && continue
            write_node "$RPS_CPUS" "$q/rps_cpus"
            [ -w "$q/rps_flow_cnt" ] && write_node "$RPS_FLOW_CNT" "$q/rps_flow_cnt"
            applied=$((applied + 1))
        done
    done
    [ "$applied" -gt 0 ] && log "rps applied to $applied wlan/p2p rx queues cpus=$RPS_CPUS"
    return 0
}

set_common() {
    # 基础修复（IRQ 拓扑、wlan RPS、cpuset 拓扑、scaling_min_freq、walt 常量、
    # 迁移门槛基线、input boost 基线）已全部搬到 ColorOS-Port-Base-Fix 的
    # service.sh:apply_sched_baseline，开机一次性写入。
    #
    # 搬家原因：这些与 Scene 模式无关的东西原先只有 Scene 调用本脚本时才落地。
    # 2026-08-25 之后 Scene 停止调用，scheduler.log 三天无新记录，于是 IRQ 全回
    # CPU0、sched_upmigrate 回到 95、input_boost 关闭、cpuset 回到 0,3-4——
    # 基础修复静默失效而没有任何征兆。判据是「Scene 卸载了这条是否还必须成立」。
    #
    # 本模块自此只负责随模式变化的量。governor 仍在这里兜一次底：Scene 的其它
    # 功能可能把某个 policy 钉成 powersave，切模式时顺手解开。
    write_policy scaling_governor walt walt walt walt
}

set_mode() {
    mode=$1
    case "$mode" in
        powersave)
            write_node '1804800 2246400 2246400 2476800' /proc/sys/walt/sched_fmax_cap
            write_task_migration '75 85 85' '85 95 95'
            write_policy walt/up_rate_limit_us 1000 1000 1000 1000
            write_policy walt/down_rate_limit_us 5000 5000 5000 5000
            write_policy walt/hispeed_freq 1017600 1286400 1286400 1248000
            write_policy walt/hispeed_load 95 95 95 95
            write_node 95 /proc/sys/walt/sched_group_upmigrate
            write_node 85 /proc/sys/walt/sched_group_downmigrate
            write_node 0 /proc/sys/walt/input_boost/sched_boost_on_input
            write_node 100 /proc/sys/walt/input_boost/input_boost_ms
            write_node '1017600 0 0 0 0 0 0 0' /proc/sys/walt/input_boost/input_boost_freq
            write_node 231000000 /sys/class/kgsl/kgsl-3d0/devfreq/min_freq
            ;;
        balance)
            write_node '2265600 2707200 2707200 2995200' /proc/sys/walt/sched_fmax_cap
            # cluster1/2 是同容量的四颗中核；只降低通往 cluster3/X4 的门槛。
            write_task_migration '50 85 70' '60 95 82'
            write_policy walt/up_rate_limit_us 0 0 0 0
            write_policy walt/down_rate_limit_us 3000 3000 3000 3000
            write_policy walt/hispeed_freq 1248000 1497600 1497600 1478400
            write_policy walt/hispeed_load 90 90 90 90
            write_node 90 /proc/sys/walt/sched_group_upmigrate
            write_node 80 /proc/sys/walt/sched_group_downmigrate
            write_node 1 /proc/sys/walt/input_boost/sched_boost_on_input
            write_node 120 /proc/sys/walt/input_boost/input_boost_ms
            write_node '1248000 1497600 1497600 1497600 1497600 1478400 0 0' /proc/sys/walt/input_boost/input_boost_freq
            write_node 231000000 /sys/class/kgsl/kgsl-3d0/devfreq/min_freq
            ;;
        performance)
            write_node '2265600 2956800 2956800 3302400' /proc/sys/walt/sched_fmax_cap
            write_task_migration '45 80 60' '55 90 72'
            write_policy walt/up_rate_limit_us 0 0 0 0
            write_policy walt/down_rate_limit_us 1000 1000 1000 1000
            write_policy walt/hispeed_freq 1459200 1708800 1708800 1593600
            write_policy walt/hispeed_load 85 85 85 85
            write_node 85 /proc/sys/walt/sched_group_upmigrate
            write_node 75 /proc/sys/walt/sched_group_downmigrate
            write_node 1 /proc/sys/walt/input_boost/sched_boost_on_input
            write_node 180 /proc/sys/walt/input_boost/input_boost_ms
            write_node '1459200 1708800 1708800 1708800 1708800 1593600 0 0' /proc/sys/walt/input_boost/input_boost_freq
            write_node 310000000 /sys/class/kgsl/kgsl-3d0/devfreq/min_freq
            ;;
        fast)
            write_node '2265600 2956800 2956800 3302400' /proc/sys/walt/sched_fmax_cap
            write_task_migration '40 75 52' '50 85 65'
            write_policy walt/up_rate_limit_us 0 0 0 0
            write_policy walt/down_rate_limit_us 0 0 0 0
            write_policy walt/hispeed_freq 1689600 1920000 1920000 1824000
            write_policy walt/hispeed_load 80 80 80 80
            write_node 80 /proc/sys/walt/sched_group_upmigrate
            write_node 70 /proc/sys/walt/sched_group_downmigrate
            write_node 1 /proc/sys/walt/input_boost/sched_boost_on_input
            write_node 250 /proc/sys/walt/input_boost/input_boost_ms
            write_node '1689600 1920000 1920000 1920000 1920000 1824000 0 0' /proc/sys/walt/input_boost/input_boost_freq
            write_node 310000000 /sys/class/kgsl/kgsl-3d0/devfreq/min_freq
            ;;
    esac
}

status() {
    echo "SM8650Q 专用调度"
    echo "mode=$(cat "$STATE_FILE" 2>/dev/null)"
    echo "device=$(getprop ro.soc.model)/$(getprop ro.board.platform) cpu=$(cat /sys/devices/system/cpu/present 2>/dev/null)"
    echo "policies=$(ls -d /sys/devices/system/cpu/cpufreq/policy* 2>/dev/null | sed 's#^.*/policy##' | tr '\n' ' ')"
    has_split_middle_policy && echo "middle_policy=split(policy1+policy3)" || echo "middle_policy=merged(policy1)"
    echo "fmax=$(cat /proc/sys/walt/sched_fmax_cap 2>/dev/null)"
    echo "boost=$(cat /proc/sys/walt/input_boost/sched_boost_on_input 2>/dev/null) input_ms=$(cat /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null)"
    echo "migration=$(cat /proc/sys/walt/sched_group_upmigrate 2>/dev/null)/$(cat /proc/sys/walt/sched_group_downmigrate 2>/dev/null)"
    echo "task_migration=$(cat /proc/sys/walt/sched_upmigrate 2>/dev/null)/$(cat /proc/sys/walt/sched_downmigrate 2>/dev/null)"
    echo "irq_default=$(cat /proc/irq/default_smp_affinity 2>/dev/null)"
    for name in hfi glink-native-adsp ipcc_0 apps_rsc-drv-2 msm_drm ufshcd; do
        for irq in $(find_irq_by_name "$name"); do
            echo "irq=$irq/$name cpu=$(cat /proc/irq/$irq/effective_affinity_list 2>/dev/null)"
        done
    done
    echo "perf=$(getprop init.svc.vendor.perfservice) perf2=$(getprop init.svc.perf2-hal-1-0) oplus=$(getprop init.svc.oplus.performance.hal.service-1-0)"
    tail -20 "$LOGFILE" 2>/dev/null
}

action=${1:-status}
if [ "$action" = status ]; then
    status
    exit 0
fi

if ! supported_device; then
    log "reject unsupported device action=$action"
    exit 2
fi

case "$action" in
    init) mode=balance ;;
    irq-init) mode= ;;
    powersave|balance|performance|fast) mode=$action ;;
    pedestal) mode=performance ;;
    # Scene 在熄屏时调用 standby，但亮屏/解锁动画早于下一次前台模式回调。
    # 若这里落到 powersave，唤醒阶段会继承低频上限且没有 input boost，造成
    # 第一段解锁动画明显卡顿。待机本身由内核 suspend/原厂 Power HAL 省电，
    # 因此保留 balance 基线，保证亮屏链路立即可用。
    standby) mode=balance ;;
    auto) mode=balance ;;
    *) log "reject unknown action=$action"; exit 3 ;;
esac

mkdir -p "$STATE_DIR"
attempt=0
while ! mkdir "$LOCK" 2>/dev/null; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 20 ] || exit 4
    sleep 0.05
done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

# IRQ 拓扑已移交 fix 模块的 apply_sched_baseline；这里只保留 wlan RPS 的补写，
# 因为 wlan0/p2p0 会在关开 Wi-Fi、投屏建组时被重建并清零 rps_cpus。
if [ "$action" = irq-init ]; then
    apply_net_rps
    exit 0
fi
set_common
set_mode "$mode"
echo "$mode" >"$STATE_FILE"

# wlan0/p2p0 被重建（关开 Wi-Fi、投屏建组）时 rps_cpus 会清零。Scene 每次切换
# 模式都会调到这里，顺手补一次即可覆盖，无需常驻监听；已是目标值时直接跳过，
# 正常情况下一次 sysfs 读、零次写。
apply_net_rps

if [ "$action" = init ]; then
    for svc in vendor.perfservice perf2-hal-1-0 oplus.performance.hal.service-1-0 performance; do
        [ "$(getprop init.svc.$svc)" = running ] || start "$svc" 2>/dev/null
    done
fi
log "applied action=$action mode=$mode fmax=$(cat /proc/sys/walt/sched_fmax_cap 2>/dev/null)"
