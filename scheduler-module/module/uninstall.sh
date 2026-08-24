#!/system/bin/sh

MODDIR=${0%/*}

remove_ours() {
    target=$1
    [ -f "$target" ] || return 0
    grep -q 'SM8650Q_SCENE_SCHEDULER_INTERFACE' "$target" 2>/dev/null && rm -f "$target"
}

remove_ours /data/powercfg.sh
remove_ours /data/powercfg-base.sh

if [ -f /data/powercfg.json ] && grep -q 'sm8650q_scene_scheduler' /data/powercfg.json 2>/dev/null; then
    rm -f /data/powercfg.json
fi

# 若安装前存在其它第三方调度接口，仅在本模块接口仍是当前接口时恢复。
if [ -f "$MODDIR/backup/powercfg.sh" ] && [ ! -e /data/powercfg.sh ]; then
    cp -p "$MODDIR/backup/powercfg.sh" /data/powercfg.sh
fi
if [ -f "$MODDIR/backup/powercfg-base.sh" ] && [ ! -e /data/powercfg-base.sh ]; then
    cp -p "$MODDIR/backup/powercfg-base.sh" /data/powercfg-base.sh
fi
if [ -f "$MODDIR/backup/powercfg.json" ] && [ ! -e /data/powercfg.json ]; then
    cp -p "$MODDIR/backup/powercfg.json" /data/powercfg.json
fi

rm -f /data/adb/sm8650q-scene-scheduler/current_mode
rm -f /data/adb/sm8650q-scene-scheduler/scheduler.log
rmdir /data/adb/sm8650q-scene-scheduler 2>/dev/null
