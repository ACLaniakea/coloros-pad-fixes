#!/system/bin/sh
# 前摄指示灯守护：轮询 dumpsys media.camera 事件日志，仅当 camera 1 被非排除
# 客户端 CONNECT 时点亮 RGB 白灯。排除：com.aiunit.aon（注视感知周期性开关前摄）、
# 人脸解锁服务（解锁时开前摄不应亮灯）。
# 2026-09-01：原 frontled.jar 依赖 ActivityThread.systemMain() 拿 system Context，
# Android 16 上该调用会让进程被 SIGKILL（实测），弃用；初版按 "Device 1 is closed"
# 判定，AON 周期性 REMOVE/ADD 相机设备时误判常亮，改为事件日志判定。
MODDIR=${1:-${0%/*}/..}
LOG="$MODDIR/base-fix.log"
log_msg() { echo "[$(date '+%F %T')] $*" >>"$LOG"; }

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done

LEDS="/sys/class/leds/blue/brightness /sys/class/leds/green/brightness /sys/class/leds/red/brightness"
# 不亮灯的 camera 1 客户端（前摄使用方黑名单）
# android = system 进程（人脸解锁开前摄）；com.aiunit.aon = 注视感知周期性开关
EXCLUDE_PKGS="android com.aiunit.aon com.oplus.facelock com.oplus.faceunlock"
prev=
while true; do
    last=$(dumpsys media.camera 2>/dev/null | grep -E 'CONNECT device 1|DISCONNECT device 1' | head -1)
    # 注意：不能用裸 "CONNECT device 1" 做前缀——DISCONNECT 行也含该子串，
    # 会误判常亮。必须匹配 ": CONNECT device 1"（冒号空格前缀）。
    case "$last" in
        *': CONNECT device 1'*)
            cur=150
            for x in $EXCLUDE_PKGS; do
                case "$last" in *"CONNECT device 1 client for package $x"*) cur=0; break;; esac
            done
            ;;
        *)
            cur=0 ;;
    esac
    if [ "$cur" != "$prev" ]; then
        for l in $LEDS; do echo "$cur" >"$l" 2>/dev/null; done
        log_msg "frontled camera1 -> $cur"
        prev=$cur
    fi
    sleep 1
done
