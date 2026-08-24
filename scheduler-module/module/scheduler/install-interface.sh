#!/system/bin/sh

MODDIR=${MODDIR:-${0%/*}/..}
DATA_DIR=/data
BACKUP="$MODDIR/backup"

[ -f "$MODDIR/payload/powercfg.sh" ] || exit 1
[ -f "$MODDIR/payload/powercfg.json" ] || exit 1

mkdir -p "$BACKUP"

backup_foreign_once() {
    name=$1
    target="$DATA_DIR/$name"
    [ -f "$target" ] || return 0
    grep -q 'SM8650Q_SCENE_SCHEDULER_INTERFACE' "$target" 2>/dev/null && return 0
    [ -f "$BACKUP/$name" ] || cp -p "$target" "$BACKUP/$name"
}

backup_foreign_once powercfg.sh
backup_foreign_once powercfg-base.sh
if [ -f /data/powercfg.json ] && ! grep -q 'sm8650q_scene_scheduler' /data/powercfg.json 2>/dev/null; then
    [ -f "$BACKUP/powercfg.json" ] || cp -p /data/powercfg.json "$BACKUP/powercfg.json"
fi

cp -f "$MODDIR/payload/powercfg.sh" /data/powercfg.sh || exit 1
cp -f "$MODDIR/payload/powercfg.sh" /data/powercfg-base.sh || exit 1
cp -f "$MODDIR/payload/powercfg.json" /data/powercfg.json || exit 1
chown root:root /data/powercfg.sh /data/powercfg-base.sh /data/powercfg.json 2>/dev/null
chmod 0755 /data/powercfg.sh /data/powercfg-base.sh
chmod 0644 /data/powercfg.json
restorecon /data/powercfg.sh /data/powercfg-base.sh /data/powercfg.json 2>/dev/null
