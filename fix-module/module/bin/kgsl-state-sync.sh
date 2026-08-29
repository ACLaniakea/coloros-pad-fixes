#!/system/bin/sh
#
# KGSL 显存前后台状态同步
# ============================================================================
# 为什么存在这个常驻（本模块唯一的例外，2026-08-29 用户明确批准）
#
# 高通 KGSL 自带显存回收：进程被标记为 background 时，内核把它的 GPU 内存
# 换出到 zram。本机内核侧完全正常（/proc/kallsyms 有 43 个 kgsl_reclaim*
# 符号、msm_kgsl 已加载、max_reclaim_limit 与 page_reclaim_per_call 均非零），
# 但**整个移植包里没有任何用户态写入者**：
#
#   * 全 /vendor + /system 扫描，含 "kgsl/kgsl/proc" 字串的只有
#     vendor.qti.hardware.memtrack-service（只读做内存统计）和
#     /system_ext/bin/autochmod.sh（开机改权限）。
#   * libqti-perfd.so 里的 kgsl 字串全是 kgsl-3d0/idle_timer、force_clk_on
#     这类调频节点，与 reclaim 无关。
#   * vendor 分区是联想的（vendor.lenovo.hardware.performance-service），
#     一加那侧负责写状态的组件不在移植包里。
#
# 结果：所有进程的 state 恒为 foreground（实测含 adj=965 的已缓存微信），
# gpumem_reclaimed 全为 0 —— 机制从未回收过一个页。8GB 机器上白白压着
# 600MB~1.4GB 不可回收的显存（Shmem ≈ Unevictable ≈ kgsl page_alloc）。
#
# 实测手动标记 4 个后台进程，15 秒内回收 146MB，Shmem 655→554MB。
#
# 为什么必须常驻而不能一次性写：
#   内核**不会**自动把 state 改回 foreground。实测微信被标 background 后
#   切回前台，state 仍是 background —— 前台应用的显存会被 shrinker 持续
#   拿走，那是直接的卡顿源。所以必须双向同步。而 Android 侧没有可用的
#   事件源（无 inotify、无 uevent、logcat 监听太重），
#   /sys/module/msm_kgsl/parameters/ 也没有能绕开状态节点的总开关。
#   穷尽三条路之后，轮询是唯一实现方式。
#
# 开销：每轮只读十几个 sysfs/proc 小文件，且**只在状态需要改变时才写**。
# ============================================================================

MODDIR=$1
. "$MODDIR/common.sh"

KGSL_PROC=/sys/class/kgsl/kgsl/proc

# 轮询间隔。取 6 秒是在两个方向之间折中：
#   * 太长 → 用户打开一个已缓存应用后，它在最长 N 秒内仍被标 background，
#     正好在冷启动这个最吃显存的时刻可能被 shrinker 抢走页，适得其反。
#   * 太短 → shell 唤醒本身的开销开始可见。
INTERVAL=6

# adj 阈值：>= 该值才标 background。
#
# AOSP 的 adj 分档：FOREGROUND=0 VISIBLE=100 PERCEPTIBLE=200 BACKUP=300
#   HEAVY_WEIGHT=400 SERVICE=500 HOME=600 PREVIOUS=700 SERVICE_B=800
#   CACHED_APP_MIN=900
#
# 取 800 是刻意保守：把 HOME(600) 和 PREVIOUS(700) 排除在外。
#   * HOME 就是桌面，本机实测占 484MB 映射显存，是全机最大户，但"回到桌面"
#     是使用频率最高的转场，回收它等于把收益直接换成可感知的卡顿。
#   * PREVIOUS 是刚离开的那个应用，也是最可能马上切回去的。
# 剩下的 SERVICE_B(800) 和 CACHED(900+) 才是系统眼里"随时可杀"的那批，
# 回收它们的显存代价最小。
# 若后续实测收益不足，这个值可以下调到 500（SERVICE，已不可见），
# 但下调前必须跑 A/B —— 回收的去向是 zram 换出，而换出量本身是本机
# 已确认的卡顿因素之一。
ADJ_THRESHOLD=800

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done

if [ ! -d "$KGSL_PROC" ]; then
    log_msg "kgsl-sync: $KGSL_PROC 不存在，退出（非 Adreno 或 msm_kgsl 未加载）"
    exit 0
fi

log_msg "kgsl-sync: 启动 间隔=${INTERVAL}s 阈值=adj>=$ADJ_THRESHOLD max_reclaim_limit=$(cat /sys/class/kgsl/kgsl/max_reclaim_limit 2>/dev/null)页"

# 累计计数，只在有变化时写日志，避免把 fix-module.log 刷满。
_total_bg=0
_total_fg=0
_last_report=0

while :; do
    sleep "$INTERVAL"

    _bg=0
    _fg=0
    for _d in "$KGSL_PROC"/*; do
        [ -d "$_d" ] || continue
        _pid=${_d##*/}

        # 全程用 read 内建而不是 $(cat ...)：每轮要读十几个进程 × 2 个文件，
        # 用 cat 是三十多次 fork，实测占单核 0.55%；换成 read 后无 fork。
        # 先用 [ -r ] 挡一道，进程可能在两次轮询之间退出（kgsl 会自己清理目录）。
        [ -r "/proc/$_pid/oom_score_adj" ] || continue
        _adj=
        read _adj <"/proc/$_pid/oom_score_adj" 2>/dev/null
        case "$_adj" in '' | *[!0-9-]*) continue ;; esac

        if [ "$_adj" -ge "$ADJ_THRESHOLD" ] 2>/dev/null; then
            _want=background
        else
            _want=foreground
        fi

        # 只在确实需要改变时才写。绝大多数轮次这里一次写都不会发生。
        # 注意 state 节点没有结尾换行，read 会返回非零但变量已赋值，所以不能用返回码判断。
        [ -r "$_d/state" ] || continue
        _cur=
        read _cur <"$_d/state" 2>/dev/null
        [ "$_cur" = "$_want" ] && continue

        printf '%s' "$_want" >"$_d/state" 2>/dev/null || continue
        if [ "$_want" = background ]; then
            _bg=$((_bg + 1))
        else
            _fg=$((_fg + 1))
        fi
    done

    [ "$_bg" = 0 ] && [ "$_fg" = 0 ] && continue
    _total_bg=$((_total_bg + _bg))
    _total_fg=$((_total_fg + _fg))

    # 有变化也不是每次都记日志：最多每 5 分钟汇报一次累计值。
    _now=
    read _now <"/proc/uptime" 2>/dev/null
    _now=${_now%%.*}
    case "$_now" in '' | *[!0-9]*) continue ;; esac
    [ "$((_now - _last_report))" -lt 300 ] && continue
    _last_report=$_now

    _rec=0
    for _f in "$KGSL_PROC"/*/gpumem_reclaimed; do
        [ -r "$_f" ] || continue
        _v=
        read _v <"$_f" 2>/dev/null
        case "$_v" in '' | *[!0-9]*) continue ;; esac
        _rec=$((_rec + _v))
    done
    log_msg "kgsl-sync: 累计 标background=$_total_bg 还原foreground=$_total_fg 当前已回收=$((_rec / 1048576))MB Shmem=$(awk '/^Shmem:/{print int($2/1024)}' /proc/meminfo 2>/dev/null)MB"
done
