#!/system/bin/sh

MODDIR=${0%/*}
export MODDIR
export PATH="/sbin:/system/bin:/system/xbin:/vendor/bin:$PATH"

# /data 已挂载后部署 Scene 标准入口，不等待 Android 服务。
"$MODDIR/scheduler/install-interface.sh" post-fs-data >/dev/null 2>&1
