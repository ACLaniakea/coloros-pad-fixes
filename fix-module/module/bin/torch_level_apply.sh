#!/system/bin/sh
# ============================================================================
# 手电亮度：信箱处理脚本（由 service.sh 里的 inotifyd 拉起）
#
# 调用约定（inotifyd）：$1=事件字母 $2=被监视的目录 $3=变动的文件名
# 信箱内容：一行，"<电流值>" 或 "<电流值> on"（后者表示这次是"正在点灯"）
#
# ---- 为什么要绕这一圈 ----
# SystemUI 跑在 platform_app 域，直接写 /sys/class/leds/led:torch_*/brightness
# 一定是 EACCES —— 节点 chmod 0666、chcon 成 vendor_sysfs_graphics、再把
# file/dir/lnk_file 三类权限全放行之后依然如此，而且 dmesg 里一条 avc 都不落
# （AOSP 对 appdomain 碰 sysfs 的这类拒绝有 dontaudit，看日志永远像"策略没问题"）。
# 所以改成：Hook 把目标电流写进它自己的数据目录，root 这边听见了再落笔。
#
# 值的含义是**驱动电流档**（0..100），不是百分比亮度。灯亮着时驱动把持续
# 电流上限压到 78，写更大也会被自己削掉，所以 Hook 那边最高就给到 78。
#
# ---- 一条硬约束：全程不许 fork ----
# inotifyd 是**串行**跑 handler 的，一次拖动甩十几个事件，handler 里每多一个
# 外部命令（cat / head / tr）就多一次 fork+exec，几十毫秒地累，最后表现成
# "亮度不跟手"。所以这里只用内建：`read x < file` 取值、`echo > file` 落笔，
# 一个子进程都不起。密集复查那个循环同理 —— 带 fork 的话 20ms 的间隔根本
# 兑现不了，实际粒度会掉到七八十毫秒。
# ============================================================================

[ "$3" = "aclaniakea_torch_level" ] || exit 0

read -r line < "$2/$3" 2>/dev/null || exit 0
v=${line%% *}
flag=${line#* }
case "$v" in
    ''|*[!0-9]*) exit 0 ;;
esac
[ "$v" -ge 1 ] && [ "$v" -le 100 ] || exit 0

A=/sys/class/leds/led:torch_0/brightness
B=/sys/class/leds/led:torch_3/brightness

apply() {
    # 已经是目标值就别再写：驱动每收到一次写都重新下发一次电流，重复写本身
    # 就是可见的一次闪。
    read -r _a < "$A" 2>/dev/null
    [ "$_a" = "$v" ] || echo "$v" > "$A" 2>/dev/null
    read -r _b < "$B" 2>/dev/null
    [ "$_b" = "$v" ] || echo "$v" > "$B" 2>/dev/null
}

apply

# HAL 那一下的兜底：camx 在点灯／改档时一定会把电流写成它自己的 torchCurrent(57)，
# 时机不定。这里用 20ms 粒度盯着，**一发现节点被改掉就压回去并立刻退出** ——
# 不等满时长是有意的：inotifyd 串行跑 handler，一次拖动十几个事件，每个都占满
# 0.3 秒的话队列就积成"亮度不跟手"。正常情况下 HAL 几十毫秒内就写完，这个循环
# 也就跑两三轮。
if [ "$flag" = "on" ]; then
    i=0
    while [ "$i" -lt 15 ]; do
        sleep 0.02
        read -r _a < "$A" 2>/dev/null
        if [ "$_a" != "$v" ]; then
            apply
            break
        fi
        i=$((i + 1))
    done
fi
