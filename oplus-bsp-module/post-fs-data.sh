#!/system/bin/sh
# ============================================================================
# OPlus BSP 内核模块加载器 —— TB710FU / 自建 GKI 6.1.128
#
# 设计原则：只负责"把 ko 尽早装上"，一行启用逻辑都不写。
#
# 因为 /product/bin/init.oplus.nandswap.sh 里有这么一句：
#     if [ -f /sys/block/zram0/hybridswap_core_enable ]; then
#         hybridswap_has_insmod=1
#     fi
# 只要 oplus_mm_hybridswap_zram.ko 在 `on property:sys.boot_completed=1`
# 之前装好，原厂脚本会自己走完整套 hybridswap 分支——建 swapfile、losetup、
# nandswap_tool 开 dio、mkswap/swapon、memcg 参数、写 hybridswap_loop_device
# 与 hybridswap_enable。这正是"回归 ColorOS 原逻辑"该有的样子。
#
# oplus_synchronize 曾因"mutex_list_add 谎报入链导致延迟 panic"被排除，
# 2026-09-11 定位到真正根因（ww_mutex 传的不是链表头）并修好后重新纳入，
# 详见清单下方专门的说明段。
# ============================================================================
MODDIR=${0%/*}
LOGFILE=/data/adb/oplus_bsp.log
KODIR="$MODDIR/ko"

log_msg() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOGFILE" 2>/dev/null
}

# The r1+ experimental kernel can contain these targets in vmlinux.  Keep the
# .ko files as a rollback path, but never insmod a second copy.  Built-in
# targets use their Kbuild names, while the fallback package uses the names
# below.
module_present() {
    m="$1"
    [ -d "/sys/module/$m" ] && return 0
    case "$m" in
        oplus_cpu_sched_sched_assist) [ -d /sys/module/sched_assist ] ;;
        oplus_cpu_sched_eas_opt) [ -d /sys/module/eas_opt ] ;;
        oplus_cpu_sched_frame_boost) [ -d /sys/module/frame_boost ] ;;
        oplus_cpu_sched_sched_info) [ -d /sys/module/oplus_sched_info ] ;;
        oplus_cpu_sched_qos_sched) [ -d /sys/module/qos_sched ] ;;
        oplus_cpu_sched_task_sched) [ -d /sys/module/task_sched_info ] ;;
        oplus_cpu_sched_task_cpustats) [ -d /sys/module/task_cpustats ] ;;
        oplus_cpu_detection) [ -d /sys/module/task_overtime ] ;;
        oplus_cpu_waker_identify) [ -d /sys/module/waker_identify ] ;;
        oplus_mm_mm_osvelte) [ -d /sys/module/logger ] ;;
        oplus_mm_memload_opt_mapped_protect) [ -d /sys/module/mapped_protect ] ;;
        oplus_mm_dump_tasks_mem) [ -d /sys/module/tasks_memory ] ;;
        oplus_mm_async_reclaim_opt_pcppages_opt) [ -d /sys/module/pcppages_opt ] ;;
        oplus_mm_async_reclaim_opt_kshrink_slabd) [ -d /sys/module/kshrink_slabd ] ;;
        oplus_bsp_lz4k) [ -d /sys/module/oplus_bsp_lz4k ] ;;
        cpufreq_effiency) [ -d /sys/module/oplus_cpu_cpufreq_effiency ] ;;
        oplus_ipc) [ -d /sys/module/binder_main ] ;;
        *) return 1 ;;
    esac
}

# 日志一律追加，绝不清空。
# 上一版这里是 `: >"$LOGFILE"`，结果开机失败那一次的模块日志被下一次开机
# 冲掉了，只能回头去啃 ECC 已经报损的 ramoops。失败现场只有一份，别自己删。
# 只在超过 512 KB 时滚动一次，避免无限增长。
if [ -f "$LOGFILE" ] && [ "$(wc -c <"$LOGFILE" 2>/dev/null || echo 0)" -gt 524288 ]; then
    mv -f "$LOGFILE" "$LOGFILE.1" 2>/dev/null
fi
log_msg ""
log_msg "======== boot @ $(cat /proc/uptime 2>/dev/null | cut -d. -f1)s uptime ========"
log_msg "=== oplus_bsp post-fs-data start (kernel $(uname -r)) ==="

# ---------------------------------------------------------------------------
# 保险丝：上次开机没走到 boot_completed 就自禁用
#
# service.sh 在 sys.boot_completed=1 之后删掉 .boot_pending。所以进到这里还
# 看得见它，只能说明上一次开机中途死了。装 22 个 BSP 模块是高风险动作，
# 宁可这次不生效，也不能让用户抱着砖去 9008。
# ---------------------------------------------------------------------------
if [ -f "$MODDIR/.boot_pending" ]; then
    log_msg "FUSE: previous boot never reached boot_completed; disabling this module"
    rm -f "$MODDIR/.boot_pending"
    touch "$MODDIR/disable"
    exit 0
fi
touch "$MODDIR/.boot_pending"
sync

# ---------------------------------------------------------------------------
# 摘掉移植包补的替代壳。正常情况下这里一个都不在（本模块 id 以 a 开头，跑在
# coloros_port_fix 之前），留着是为了手动 rerun 时也能干净重来。
# ---------------------------------------------------------------------------
for shim in oplus_sched_assist oplus_mm_compat; do
    if grep -q "^$shim " /proc/modules 2>/dev/null; then
        if rmmod "$shim" 2>>"$LOGFILE"; then
            log_msg "shim removed: $shim"
        else
            log_msg "WARN: could not rmmod shim $shim"
        fi
    fi
done

# ---------------------------------------------------------------------------
# 依赖拓扑序。顺序是实测出来的，不要随意调整：
# sched_assist 导出的符号被后面一大票 cpu_sched 模块依赖，oplus_ipc 要在
# 它之后，hybridswap 依赖 mm_osvelte / sched_info，exit_mm_optimize 依赖
# hybridswap。
#
# 备忘（2026-08-28）：sched_assist 的源码其实就在内核树 drivers/oplus/cpu/sched/
#   sched_assist/ 里；如果目标内核已经把这些目标内建，下面的加载循环会通过
#   /sys/module 识别并跳过对应 .ko。外挂文件保留给旧内核回退使用。
# ---------------------------------------------------------------------------
MODULES="
oplus_cpu_sched_sched_assist
oplus_ipc
oplus_cpu_detection
oplus_cpu_sched_task_cpustats
oplus_mm_mm_osvelte
oplus_mm_dump_tasks_mem
oplus_mm_levelprotect
oplus_mm_memload_opt_mapped_protect
oplus_mm_sigkill_diagnosis
oplus_mm_process_reclaim
oplus_mm_async_reclaim_opt_pcppages_opt
oplus_mm_async_reclaim_opt_kshrink_slabd
oplus_cpu_sched_sched_info
oplus_mm_dynamic_readahead
oplus_cpu_sched_qos_sched
oplus_mm_uxmem_opt
oplus_cpu_sched_eas_opt
oplus_cpu_sched_frame_boost
oplus_cpu_sched_task_sched
oplus_cpu_waker_identify
oplus_bsp_zram_opt
oplus_bsp_kswapd_opt
oplus_mm_hybridswap_zram
oplus_mm_exit_mm_optimize
ua_cpu_ioctl
oplus_bsp_game_opt
oplus_hans
oplus_freeze_process
kp_freeze_detect
oplus_bsp_lz4k
cpufreq_effiency
oplus_resctrl
oplus_lb_bridge
"

# UX -> WALT MVP 桥：2026-09-12 起随开机加载并启用（见下方"为什么改成默认开"）。
# 放 $MODDIR/disable-walt-bridge 可整段跳过，无需改脚本。
if [ -f "$MODDIR/disable-walt-bridge" ]; then
    log_msg "walt-bridge: disable-walt-bridge 存在，跳过"
else
    MODULES="$MODULES oplus_walt_bridge"
fi

# 原机同构的内存策略层：二者只挂 Android GKI 已有的 reclaim hooks，
# 不替换已工作的 zram/HybridSwap 驱动。二者位于 MODULES 里的
# HybridSwap 之前，使 init.oplus.nandswap.sh 能写入其动态参数。

# ---------------------------------------------------------------------------
# 末尾三个是自行补编的（sweep 脚本当年漏掉了它们）：
#
# ua_cpu_ioctl —— frame_boost 的 proc/ioctl 那半边。依赖
#   oplus_cpu_sched_frame_boost（在上面），所以必须排它后面。建
#   /proc/oplus_frame_boost/{ctrl,sys_ctrl,stune_boost,info,game_ed_info}
#   与 /proc/oplus_cpu/ua_ctrl，system_server 靠这些节点标记 UX 线程。
#   源码审计：零 vendor hook，init 失败路径干净，可 rmmod。低风险。
#
# oplus_bsp_game_opt —— /proc/game_opt/*，
#   /odm/bin/hw/vendor.oplus.hardware.gameopt-service 的对端。
#   ★ 单向门：注册 8 个 hook，其中 2 个 rvh（show_max_freq、
#   try_to_wake_up_success），而 game_ctrl_exit 一个都不注销 →
#   装上就永远不能 rmmod。try_to_wake_up_success 的另一槽被
#   oplus_cpu_waker_identify 占着，两槽正好用满，别再往这个钩子上加东西。
#   2026-08-27 手动 insmod 实测通过（25 个 proc 节点、dmesg 干净）。
#
# oplus_hans —— ColorOS 后台冻结子系统的内核侧（2026-08-28 补编）。
#   源码在 modules/vendor/oplus/kernel/hans/，自带 Kbuild，模块名是
#   oplus_hans 而不是 oplus_bsp_hans。注册 genl family "oplus_hans"，
#   /system_ext/bin/hans 靠它建通道。
#
#   ★ 缺它的后果比看上去严重得多：hans 守护进程 genl_get_family_id 失败
#   → 自己退出 → system_server 的 OplusHansManager 收到 MSG_HANS_DISABLED
#   → 整套后台冻结关闭 → 后台应用一个不冻、全在跑 → swapd 把它们换出去、
#   它们立刻踩回来 → 静置态 100% refault 颠簸（pswpin 733/s，熄屏更是
#   6219/s），这是卡顿的主因。详见 project_hans_freezer_missing 记录。
#
#   风险低：五个钩子全是普通 vh（binder_preset/trans/reply/
#   alloc_new_buf_locked、do_send_sig_info），init 失败会自己回滚，
#   没有 rvh，可 rmmod。无依赖，位置随意。
#
# ---- oplus_synchronize（锁的 UX 优先级继承）：试过四次，四次都 panic，别再试 ----
#
# 2026-09-11 的四轮尝试定位并修掉了三处真实根因，源码修复都留在
# kernel-compat/oplus_synchronize/ 里（每处都有 ACLaniakea 标注的注释），
# 但每修完一处就冒出下一处，第四轮仍然 kernel_panic,null。
#
#   轮次 | 根因                                           | 触发场景
#   -----|------------------------------------------------|------------------
#    1   | mutex_list_add_ux 把链表中间节点当链表头        | 一交互就崩
#    2   | rwsem_list_add_ux 同类问题，且无条件返回 true   | 解锁必崩
#    3   | owner 可为 NULL 却直接喂给 set_inherit_ux       | 熄屏/亮屏循环
#    4   | 未定位（ramoops ECC 已损，取不到调用栈）        | 熄屏/亮屏循环
#
# 第三轮那处值得单独记：原代码是
#     if ((is_ux || is_rt) && !test_inherit_ux(owner, ...)) set_inherit_ux(owner, ...);
# 那个 ! 是陷阱——test_inherit_ux(NULL) 返回 false，取反后条件反而成立，
# 于是径直把 NULL 交给 set_inherit_ux() 解引用。mutex 与 rwsem 两处都这样。
# 已补显式 NULL 判断，并把 23 个 set_inherit_ux 调用点穷举过一遍
# （其余都自带守卫），但第四轮仍崩，说明还有别的机制里有同类假设。
#
# ★ 要再碰它的前提（缺一不可）★
#   1. 能取到真实调用栈。本机 /sys/fs/pstore/console-ramoops-0 的 ECC 已损，
#      只能靠"崩在哪个压力测试"倒推，那是盲修，第 4~N 轮都会是同样的循环。
#      需要串口，或先修好 ramoops。
#   2. 别再用"开机成功 + 短时压力测试通过"当安全信号——第二轮就是这么放行的，
#      结果用户一解锁就连环重启。
#
# 下面是前两轮的细节，保留备查。
# ------------------------------------------------------------------------
#
# 它就是对照机上那个 oplus_locking_strategy：给 mutex / rwsem / futex / rtmutex
# 加 UX 优先级继承。对照机 kallsyms 里有 747 个相关符号，本机是 0，所以一直很
# 想把它补上。源码（含下述两轮修复）留在 kernel-compat/oplus_synchronize/，
# 只作存档，**不要加回本清单**。
#
# 第一轮（更早）：装上一交互就 panic。归因写作"mutex_list_add 谎报入链"。
#
# 第二轮（2026-09-11）：找到第一轮的真正根因并修好——
#   __mutex_add_waiter() 第三个参数的语义是"插到这个节点之前"，不一定是链表头。
#   ww_mutex 路径（ww_mutex.h 的 __ww_waiter_add）传进来的是链表中间的 waiter。
#   mutex_list_add_ux() 把它当链表头绕圈遍历，途中走到 &lock->wait_list 本身
#   并 list_entry 成 mutex_waiter，于是 waiter->task 读到的其实是 struct mutex
#   的 android_oem_data1[0]。ww_mutex 被 DRM/dma-resv 大量使用，故"一交互就崩"。
#   修法：head 不是真链表头就放弃插队、据实返回是否入链、waiter->task 空指针
#   防御，另加 mutex_ux_insert 开关且默认关闭。
#
#   短期实测全绿：手动 insmod 后 20 秒滑动压力，futex_ux_set_cnt 8478 -> 11743
#   （约 165 次/秒）、unset 对称配平、零告警；重启后开机自动加载也正常，
#   34 loaded / 0 failed、boot_completed 33 秒、futex_set_blocked_ux_cnt 0 -> 70。
#
#   ★ 然后在真实使用中暴露：**只要解锁就 panic 重启**。现场：
#       sys.boot.reason = kernel_panic,null
#       NULL pointer dereference at 0x928     ← task_struct.wake_q 的偏移
#       故障指令 c8ab7d42 = casal x11, x2, [x10]，正是 wake_q_add 里的 CAS
#
#   即模块把 NULL task 传给了 wake_q_add。由于 mutex_ux_insert 当时是关着的，
#   出问题的不是 mutex 那条路，而是 futex 或 rwsem 的唤醒路径——第二轮的修复
#   只堵住了 mutex 一侧，同类的"拿着不该当 task 的东西去唤醒"在另一侧仍在。
#
# 教训：这个模块的多条路径都假设了一加内核的链表/结构语义，逐条堵成本很高，
# 而失败模式是内核 panic。要再碰它，先把 futex.c / rwsem.c 里所有喂给
# wake_q_add 的 task 来源逐个审一遍，并且只在有完整串口/ramoops 取栈能力时做。
#
# 顺带纠正一个当时的判断：BSP 模块的 .boot_pending 熔断在这里**没能兜住**——
# 它只在"开机没走到 boot_completed"时触发，而这次是开机正常、解锁才崩，
# 熔断条件根本不成立。对"起得来但用不了"这类故障，熔断是无效的。
# ---------------------------------------------------------------------------

# ---- 2026-09-11：OPlus/WALT 软件桥（本项目自编，非 OPlus 原件）----
# 实机已证明高通 sched_walt 占用并实现了 pick-next restricted hook，因此本桥
# 不再争抢最终决策点。它在普通 scheduler-tick VH 上检查 current 的 OPlus UX
# 状态，并调用 WALT 原生 set_task_boost() 赋予短期 MVP 状态；pick/preempt 仍
# 全部由 WALT 决定。普通 VH 可多订阅且可注销，可与 oplus_lb_bridge 共存。
# 运行计数见 /proc/oplus_walt_bridge；enable 参数可即时旁路，rmmod 可完整回滚。
# 当前为 staged opt-in：模块目录存在 enable-walt-bridge 文件时才随开机加载。
#
# oplus_lb_bridge 用途是把 sched_assist 的 tick
# 负载均衡入口接回调度器。必须排在 oplus_cpu_sched_sched_assist 之后
# （它链接该模块导出的 __oplus_tick_balance），故放在清单末尾。
#
# 为什么需要它：平板的 lb_stat 全部计数长期为 0，而对照手机 26 小时是
# tick_hit 2480 万、newidle_hit 1.05 亿。原因是 __oplus_tick_balance 与
# __oplus_newidle_balance 只是导出入口、模块内部无调用者，真正调用它们的是
# OPlus 打过补丁的 WALT，而本机内核带的是高通原版 WALT（手机 sched_walt 在
# sched_assist 的 holders 列表里，平板的不在；walt 符号数 4164 vs 3177）。
#
# 只接 tick 那一半：newidle 走的 android_rvh_sched_newidle_balance 是受限
# hook，只允许一个探针，已被高通 WALT 占用。
#
# ★ 实测记录（2026-09-11）：
#   第一版把 __oplus_tick_balance 直接注册成探针 → insmod 立刻 kCFI panic
#   （它在 OPlus 源码里只被直接调用，没有 kCFI 类型前缀）。加一层本模块内的
#   转接函数后正常：15 秒内 tick_hit 0 → 8277、tick_pull_runnable_ux 130，
#   内核告警 0 条。细节见 kernel-compat/oplus_lb_bridge/oplus_lb_bridge.c。
#
# 撤销：echo 0 > /proc/oplus_scheduler/sched_assist/lb_enable（免重启）、
#       rmmod oplus_lb_bridge、或从本清单删掉这一行。
# ---------------------------------------------------------------------------

# ---- 2026-08-28 第二批：从 53 个自编 ko 里筛出来的 5 个 ----
# 筛选过程见 task #49。全部单独 insmod 实测通过、且 rmmod 得掉（可回退）。
# 各自 20~32 KB，都不建顶层 /proc 节点，无依赖，顺序随意。
#
# oplus_freeze_process —— 冻结钩子。dmesg 报
#   "[freeze_process_hook] module init successfully!"。冲的是断点二：
#   HANS 已经算出冻结名单（enter FF, frzUids:[...]）但没有一个进程真被冻。
#   这一批里唯一有明确目标的，其余四个是顺带。
# kp_freeze_detect —— 冻结相关的内核异常检测，配合上面那个。
# oplus_bsp_lz4k —— lz4k 压缩算法。zram comp_algorithm 列表里已经列着 lz4k
#   但当前选的是 [lz4]；装上它才真正可选。**暂不切算法**，先备着。
# cpufreq_effiency —— "cpufreq bouncing" 抑制，5 个模块参数，全用默认。
# oplus_resctrl —— cache/带宽分区。
#
# 同批被否掉的，别再试：
#   oplus_bsp_healthinfo      撞 oplus_cpu_sched_sched_info 的 ohm_get_cur_cpuload
#   oplus_bsp_binder_strategy 撞 oplus_ipc 的 oblist_dequeue_topapp_change
#   oplus_procs_load          init_module 里 Out of memory + 内核 oops，危险
#   crypto_zstdn              本内核没编 crypto_register_scomp
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 给 hybridswap 腾位置：卸掉标准 zram
#
# oplus_mm_hybridswap_zram 不改名、不改符号，冲突只在运行期全局资源上——
# 块设备名 zram / 主设备号、zram-control class、debugfs 目录、静态
# CPUHP_ZCOMP_PREPARE。标准 zram.ko 一从内存里出去，这些立刻全部释放，
# 于是共存补丁一个都不需要。
#
# zram.ko / zsmalloc.ko 在 /system_dlkm 下且**不在任何 modules.load 里**，
# 所以卸掉之后没有任何东西会把它装回来。
# ★ 绝不能连坐卸 zsmalloc：hybridswap 自己也要用它。
#
# 此刻卸掉之后到 boot_completed 之间系统没有 swap。已确认这不影响开机。
# ---------------------------------------------------------------------------
drop_stock_zram() {
    grep -q '^zram ' /proc/modules 2>/dev/null || {
        log_msg "stock zram not loaded; nothing to drop"; return 0; }

    if grep -q '^/dev/block/zram0 ' /proc/swaps 2>/dev/null; then
        used=$(awk '$1 == "/dev/block/zram0" { print $4 }' /proc/swaps 2>/dev/null)
        log_msg "stock zram0 is swapped on (${used:-?} KB used); swapping off"
        swapoff /dev/block/zram0 2>>"$LOGFILE" || {
            log_msg "ERROR: swapoff zram0 failed; leaving stock zram in place"
            return 1
        }
    fi
    [ -d /sys/block/zram0 ] && echo 1 >/sys/block/zram0/reset 2>/dev/null

    if rmmod zram 2>>"$LOGFILE"; then
        log_msg "stock zram.ko removed (zsmalloc kept)"
        return 0
    fi
    log_msg "ERROR: rmmod zram failed; hybridswap will not be able to load"
    return 1
}

# ---------------------------------------------------------------------------
loaded=0
failed=0
for m in $MODULES; do
    ko="$KODIR/$m.ko"
    if [ ! -f "$ko" ]; then
        log_msg "MISSING: $m.ko"
        failed=$((failed + 1))
        continue
    fi
    if module_present "$m" || grep -q "^$m " /proc/modules 2>/dev/null; then
        log_msg "already present (loaded or built-in): $m"
        loaded=$((loaded + 1))
        continue
    fi

    # hybridswap 之前先让标准 zram 退场
    if [ "$m" = oplus_mm_hybridswap_zram ]; then
        drop_stock_zram || { log_msg "SKIP: $m (stock zram still resident)"; failed=$((failed + 1)); continue; }
    fi

    # 不吞 stderr：ELF 损坏、符号缺失、版本不匹配的真实原因都在这里面
    if insmod "$ko" 2>>"$LOGFILE"; then
        log_msg "loaded: $m"
        loaded=$((loaded + 1))
    else
        log_msg "FAILED: $m (rc=$?)"
        failed=$((failed + 1))
    fi
done

log_msg "=== oplus_bsp: $loaded loaded, $failed failed ==="

# ---------------------------------------------------------------------------
# 打开 UX -> WALT 桥（2026-09-12）
#
# 模块本身 enable 默认 0——"只装不开"等于没装，必须在这里显式打开。
#
# boost_type 选 1（TASK_BOOST_ON_MID）而不是模块默认的 3，理由是拓扑：
#   本机 cpu0 capacity 379（唯一弱核）、cpu1-4 867、cpu5 1024。
#   UI 线程一旦落到 cpu0 就只有中核 44% 的算力，这正是要治的病；
#   type 1 把它抬到中簇就够，不需要 type 3 的 STRICT_MAX + MVP 强制。
#   type 3 会给任务 12ms 的 MVP 执行上限，超限被 walt_cfs_deactivate_mvp_task
#   降级——多轮 A/B 里"均值改善但尾部变差"的嫌疑就在这里。
#
# ★ 诚实记录：这个桥的收益没有被测量证实 ★
#   本项目一共做过八轮 A/B（另一 AI 五轮 Perfetto FrameTimeline + 本轮三轮
#   SurfaceFlinger timestats），八轮里没有一致方向。原因是基线漂移远大于效应：
#   同为"关桥"的样本，P2P 尾部在两轮之间从 0.3% 摆到 8.7%，而且样本越靠后越差
#   （热或后台累积），交错采样抵消不掉相关性。刷新率已排除（全程稳定 150Hz、
#   主导间隔 6ms）。
#
#   所以这里启用它的依据不是"实测更快"，而是"它把一条确实断掉的原厂链路接了
#   回去"：ColorOS 的 UX 标记原本走到 sched_assist 就断了，因为最终选任务的是
#   高通 sched_walt，它不认 OPlus 的 UX 状态。桥把 UX 状态翻译成 WALT 认识的
#   task boost，链路因此闭合。这是设备所有者在知情下做的取舍。
#
# 关掉的三种方式，由轻到重：
#   1. echo 0 > /sys/module/oplus_walt_bridge/parameters/enable   （立即、免重启）
#   2. touch $MODDIR/disable-walt-bridge 后重启                    （不再加载）
#   3. rmmod oplus_walt_bridge                                     （普通 vh，可卸）
#
# 已知未验收项（沿用 TEST-2026-09-12.md）：PowerHAL 并发写同一 task boost 时
# 会互相覆盖（WALT 没有 getter 可合并）、长时功耗与待机未测。
# ---------------------------------------------------------------------------
WB=/sys/module/oplus_walt_bridge/parameters
if [ -d "$WB" ]; then
    echo 1 >"$WB/boost_type" 2>/dev/null
    echo 1 >"$WB/enable" 2>/dev/null
    log_msg "walt-bridge: enable=$(cat "$WB/enable" 2>/dev/null) type=$(cat "$WB/boost_type" 2>/dev/null) period=$(cat "$WB/boost_period_ms" 2>/dev/null)"
elif [ ! -f "$MODDIR/disable-walt-bridge" ]; then
    log_msg "WARN: walt-bridge 模块未加载，无法启用"
fi

# ---------------------------------------------------------------------------
# 就地抓取模块自报的 hook 注册结果（2026-09-12）
#
# sched_assist / frame_boost 等在 init 里对每个注册失败都会 pr_err：
#     [sched_assist][...]failed to register_trace_android_rvh_xxx, ret=-16
# -16 = EBUSY，意味着那个受限 hook 已被高通 WALT 或联想模块占走，
# 对应的 UX 机制在本机是死的。
#
# 这些消息只在 post-fs-data 这一刻产生，而本机 dmesg 环形缓冲约 56 秒就滚一轮
# （servicemanager 每秒两次找不到 subsys HAL 在刷屏），等 adb 起来再看必然已丢。
# 所以在加载循环结束后立刻落盘一份，成本是几十行日志。
# ---------------------------------------------------------------------------
{
    echo "--- hook 注册结果快照 ---"
    dmesg 2>/dev/null | grep -aiE 'sched_assist|frame_boost|eas_opt|qos_sched|uxmem|failed to register|ret=-16' | tail -60
    echo "--- 快照结束 ---"
} >>"$LOGFILE" 2>/dev/null

if [ -f /sys/block/zram0/hybridswap_core_enable ]; then
    log_msg "hybridswap sysfs present; init.oplus.nandswap.sh will take over at boot_completed"
else
    log_msg "WARN: /sys/block/zram0/hybridswap_core_enable absent; stock script will fall back to plain zram"
fi
