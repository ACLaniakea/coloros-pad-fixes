#!/system/bin/sh
# SM8650Q_SCENE_SCHEDULER_INTERFACE

ACTIVE=/data/adb/modules/sm8650q_scene_scheduler/scheduler/main.sh
UPDATE=/data/adb/modules_update/sm8650q_scene_scheduler/scheduler/main.sh

if [ -x "$ACTIVE" ]; then
    exec sh "$ACTIVE" "$@"
elif [ -x "$UPDATE" ]; then
    exec sh "$UPDATE" "$@"
fi

echo "SM8650Q scheduler module is not active" >&2
exit 1
