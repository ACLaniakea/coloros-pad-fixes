#!/system/bin/sh

# ============================================================================
# 联想平板 Pro GT - OVoice 小核省电守护
# 原理：小布唤醒在本机是 CPU 实时推理（BWV/AudioRecord），不能像原厂 DSP
#       那样零成本常驻，也不能饿死在小核。正确做法：
#         熄屏待听 -> 把 OVoice 线程限制在 little+中核 (0,3-4)，省电不丢识别；
#         亮屏/唤醒 -> 恢复全核 (0-5)，保证响应速度。
#       进程出现后 GRACE_SEC 秒内不强切，避免启动期前台服务超时崩溃。
# 实现：taskset 线程级亲和性（toybox 掩码按十六进制解析，不接受 0x 前缀），
#       由 AMS 管理的 cpuset 不会覆盖线程亲和性，比整进程迁移更稳。
# ============================================================================

MODDIR=$1
LOGFILE="$MODDIR/voice-power-guard.log"
PKG=com.oplus.ovoicemanager.wakeup
SAVE_MASK=19    # hex: cores 0,3-4 (little + 2 titanium)
FULL_MASK=3f    # hex: cores 0-5
GRACE_SEC=60

log_msg() {
    echo "[$(date '+%F %T')] $*" >>"$LOGFILE"
}

screen_is_off() {
    state=$(dumpsys power 2>/dev/null | grep -o 'mWakefulness=[A-Za-z]*' | head -n 1)
    case "$state" in
        *Asleep*|*Dozing*) return 0 ;;
    esac
    return 1
}

pin() {
    mask="$1"
    newpid=$(pidof "$PKG" 2>/dev/null | awk '{print $1}')
    [ -n "$newpid" ] || return 1
    taskset -ap "$mask" "$newpid" >/dev/null 2>&1
}

pid=
pid_since=0
last_state=

while :; do
    newpid=$(pidof "$PKG" 2>/dev/null | awk '{print $1}')
    if [ -n "$newpid" ] && [ "$newpid" != "$pid" ]; then
        pid=$newpid
        pid_since=$(date +%s)
        last_state=
        log_msg "ovoice pid=$pid tracked"
    fi

    if [ -z "$pid" ]; then
        sleep 10
        continue
    fi

    now=$(date +%s)
    if screen_is_off; then
        if [ $((now - pid_since)) -ge "$GRACE_SEC" ]; then
            if pin "$SAVE_MASK"; then
                [ "$last_state" != off ] && log_msg "screen off: ovoice pinned to $SAVE_MASK"
                last_state=off
            fi
        fi
    else
        if pin "$FULL_MASK"; then
            [ "$last_state" != on ] && log_msg "screen on: ovoice pinned to $FULL_MASK"
            last_state=on
        fi
    fi
    sleep 10
done
