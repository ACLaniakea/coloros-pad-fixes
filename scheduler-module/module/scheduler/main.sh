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
    for policy in 0 1 3 5; do
        [ -d "/sys/devices/system/cpu/cpufreq/policy$policy" ] || return 1
    done
    [ ! -d /sys/devices/system/cpu/cpufreq/policy7 ]
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
    write_node "$value3" "/sys/devices/system/cpu/cpufreq/policy3/$suffix"
    write_node "$value5" "/sys/devices/system/cpu/cpufreq/policy5/$suffix"
}

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

# 把一组同前缀的 IRQ 轮流铺到给定的 CPU 列表上。QCA 数据面每条队列的负载
# 差了一个数量级（dp_14 约 9.5 万次，dp_13 约 6 千次），因此按 /proc/interrupts
# 里的实际计数从多到少排序后再轮转，让最重的几条先落到不同核上。
spread_irq_by_prefix() {
    prefix=$1
    shift
    cpus="$*"
    [ -n "$cpus" ] || return 0
    idx=0
    for irq in $(awk -v p="$prefix" '
            index($NF, p) == 1 {
                tot = 0
                for (i = 2; i <= 7; i++) tot += $i
                gsub(":", "", $1)
                print tot, $1
            }' /proc/interrupts 2>/dev/null | sort -rn | awk '{print $2}'); do
        cpu=$(echo "$cpus" | awk -v n="$idx" '{print $((n % NF) + 1)}')
        node="/proc/irq/$irq/smp_affinity_list"
        [ -w "$node" ] || continue
        before=$(cat "$node" 2>/dev/null)
        [ "$before" = "$cpu" ] && { idx=$((idx + 1)); continue; }
        echo "$cpu" >"$node" 2>/dev/null || continue
        after=$(cat "/proc/irq/$irq/effective_affinity_list" 2>/dev/null)
        log "irq=$irq prefix=$prefix cpu=$cpu effective=${after:-unknown} previous=${before:-unknown}"
        idx=$((idx + 1))
    done
}

set_irq_cpu() {
    cpu=$1
    name=$2
    for irq in $(find_irq_by_name "$name"); do
        node="/proc/irq/$irq/smp_affinity_list"
        [ -w "$node" ] || continue
        before=$(cat "/proc/irq/$irq/effective_affinity_list" 2>/dev/null)
        [ "$before" = "$cpu" ] && continue
        echo "$cpu" >"$node" 2>/dev/null || continue
        after=$(cat "/proc/irq/$irq/effective_affinity_list" 2>/dev/null)
        log "irq=$irq name=$name cpu=$cpu effective=${after:-unknown} previous=${before:-unknown}"
    done
}

apply_irq_topology() {
    # 源 SM8850 系统的 bootargs 带 irqaffinity=0-1；在本机 1+4+1 拓扑上会把
    # 可迁移中断集中到唯一弱核 CPU0。默认把后注册的普通 IRQ 放在四颗中核，
    # 再将已注册的高频设备 IRQ 按功能静态分散。Prime CPU5 留给交互突发。
    write_node 1e /proc/irq/default_smp_affinity

    set_irq_cpu 1 glink-native-adsp
    set_irq_cpu 1 apps_rsc-drv-2
    set_irq_cpu 1 ipcc_0

    set_irq_cpu 2 hfi
    set_irq_cpu 2 ufshcd
    set_irq_cpu 2 dwc3

    set_irq_cpu 3 msm_drm
    set_irq_cpu 3 NVT-ts
    set_irq_cpu 3 spi_geni

    set_irq_cpu 4 240b7400.qcom,bwmon-llcc
    set_irq_cpu 4 24091000.qcom,bwmon-ddr
    set_irq_cpu 4 msm_serial_geni0

    # 以上按名字静态分散的是 default_smp_affinity 覆盖不到的一部分。实机
    # /proc/interrupts 显示真正的大户根本不在其中：WLAN 的 14 条中断把
    # smp_affinity_list 显式钉在 0（不是默认漂移），合计约 43.6 万次全部落在
    # 唯一弱核 CPU0；msm-vidc（硬件编解码）与两条 i2c_geni 同样有效落在 CPU0。
    # 与此同时 CPU3/CPU4 各只有约 10 万次。投屏这类场景要同时吃 Wi-Fi 数据面
    # 和视频编码，两者却挤在同一颗 379 容量的核上。
    #
    # WLAN 的 14 条中断同样全钉在 CPU0，但 QCA 驱动给它们置了 IRQ_NO_BALANCING：
    # 实测对 irq 308/319/326 写 smp_affinity 与 smp_affinity_list 一律失败
    # （rc=1/EIO），硬中断无法迁移，只能由 apply_net_rps 把随后的 softirq
    # 协议栈处理转到中核。这里不再尝试，避免每次开机做十几次注定失败的写。

    # 硬件编解码中断与其固件接口 hfi 同核，避免每帧跨核。
    set_irq_cpu 2 msm-vidc

    # i2c_geni 有多个实例（触控以外的传感器/PMIC 总线），统一挪到 CPU1。
    spread_irq_by_prefix i2c_geni 1
}

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
    # 原厂 HAL 和温控继续拥有 scaling_max_freq；这里只清除错误的高最小频率。
    write_policy scaling_min_freq 364800 499200 499200 480000
    write_policy scaling_governor walt walt walt walt

    write_node 1 /proc/sys/walt/sched_sbt_enable
    write_node 119 /proc/sys/walt/walt_rtg_cfs_boost_prio
    write_node 400 /proc/sys/walt/sched_pipeline_util_thres
    write_node 325 /proc/sys/walt/walt_low_latency_task_threshold
    write_node 'libunity.so, libfb.so' /proc/sys/walt/sched_lib_name
    write_node UnityMain /proc/sys/walt/sched_lib_task
    write_node 3000 /proc/sys/walt/sched_disable_mvp_thres
    write_node 0 /proc/sys/walt/sched_boost

    # SM8650Q 的容量拓扑是 1+4+1：CPU0=379，CPU1-4=867，CPU5=1024。
    # 中间四核虽被固件拆为 policy1/3 两个频域，调度上仍是同容量的一簇；
    # 后台若只给 0,3-4 会无故闲置其中两颗中核并在解锁时形成积压突发。
    write_node '0-4' /dev/cpuset/background/cpus
    write_node '0-4' /dev/cpuset/system-background/cpus
    write_node '0-5' /dev/cpuset/foreground/cpus
    write_node '0-5' /dev/cpuset/top-app/cpus
    # 144Hz 合成线程避开唯一的弱小核，同时可按需使用 Prime 核。
    write_node '1-5' /dev/cpuset/sf/cpus
}

set_mode() {
    mode=$1
    case "$mode" in
        powersave)
            write_node '1804800 2246400 2246400 2476800' /proc/sys/walt/sched_fmax_cap
            write_task_migration '85 85 85' '95 95 95'
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
            write_task_migration '80 85 70' '90 95 82'
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
            write_task_migration '75 80 60' '85 90 72'
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
            write_task_migration '70 75 52' '80 85 65'
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

if [ "$action" = irq-init ]; then
    apply_irq_topology
    apply_net_rps
    log "applied 1+4+1 irq topology default=$(cat /proc/irq/default_smp_affinity 2>/dev/null)"
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
