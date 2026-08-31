#!/system/bin/sh
# ============================================================================
# 尽早把关键 UI 进程的 memcg 保护写下去（桌面 / system_server / SurfaceFlinger）
#
# 为什么单独拉一个脚本，而不是留在 service.sh 里：
#   service.sh 开头要等 sys.boot_completed，本身又是一条一千多行的串行链，
#   等它跑到保护那一段已经是开机 60~90 秒之后 —— 而**开机头一分钟恰恰是内存
#   压力最大的时候**，等我们写下去，这几个进程的页早就被换进 zram 了。
#   用户报的"开机后第一次解锁卡顿"就落在这个窗口里。
#
# 这里改成从 post-fs-data 就在后台等：memcg 一出现立刻写，写完就退出。
# 有界（最多约 120 秒）、写完即止，不是常驻守卫。
#
# 只需要写 uid 组一次：子 memcg 在创建时继承父组的 swappiness（实测父=0 时
# 新建子组读回 0），所以进程后来重启落到新的 pid_ 子组也仍然是 0。
# 但**当前已经存在的 pid 子组继承不到**（它们建在我们写父组之前），要单独补。
#
# swappiness 与 app_score 两个都要写，缺一不可：
#   app_score 只被 OPlus 自己的 swapd 使用；
#   内核的 kswapd 与直接回收不看它，只看 swappiness。
#   实测只压 app_score，十分钟后桌面换出量又从 91MB 涨回 154MB。
# ============================================================================

LOG="${1:-/data/adb/modules/coloros_port_fix}/fix-module.log"
log() { echo "[$(date '+%F %T')] ui-memcg: $*" >>"$LOG" 2>/dev/null; }

set_group() {
    [ -d "$1" ] || return 1
    echo 0 >"$1/memory.swappiness" 2>/dev/null
    echo 0 >"$1/memory.app_score" 2>/dev/null
    [ "$(cat "$1/memory.swappiness" 2>/dev/null)" = "0" ]
}

# 把某个进程的 uid 组与它当前的 pid 子组一起写掉
protect_pid() {
    _g=$(awk -F: '/:memory:/{print $3}' "/proc/$1/cgroup" 2>/dev/null)
    case "$_g" in /apps/*) ;; *) return 1 ;; esac
    _u=${_g#/apps/}; _u=${_u%%/*}
    set_group "/dev/memcg/apps/$_u" || return 1
    set_group "/dev/memcg$_g"
    log "pid=$1 $_g sw=$(cat "/dev/memcg$_g/memory.swappiness" 2>/dev/null) swap=$(awk '/^swap /{print int($2/1048576)}' "/dev/memcg$_g/memory.stat" 2>/dev/null)MB"
    return 0
}

sys_done=0
launcher_done=0
i=0
while [ "$i" -lt 240 ]; do
    if [ "$sys_done" = 0 ]; then
        _sp=$(pidof system_server)
        if [ -n "$_sp" ] && protect_pid "$_sp"; then
            sys_done=1
            _fp=$(pidof surfaceflinger)
            [ -n "$_fp" ] && protect_pid "$_fp"
        fi
    fi
    if [ "$launcher_done" = 0 ]; then
        _lp=$(pidof com.android.launcher)
        [ -n "$_lp" ] && protect_pid "$_lp" && launcher_done=1
    fi
    [ "$sys_done" = 1 ] && [ "$launcher_done" = 1 ] && break
    sleep 0.5
    i=$((i + 1))
done
log "arming finished after ${i} rounds (system=$sys_done launcher=$launcher_done)"
