#!/system/bin/sh

MODDIR=${0%/*}
export MODDIR
export PATH="/sbin:/system/bin:/system/xbin:/vendor/bin:$PATH"

"$MODDIR/scheduler/install-interface.sh" action
sh "$MODDIR/scheduler/main.sh" status
