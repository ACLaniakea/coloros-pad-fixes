#!/system/bin/sh

MODDIR=${0%/*}
export MODDIR
export PATH="/sbin:/system/bin:/system/xbin:/vendor/bin:$PATH"

# /data 已挂载后部署 Scene 标准入口，不等待 Android 服务。
"$MODDIR/scheduler/install-interface.sh" post-fs-data >/dev/null 2>&1

# 设备 IRQ 在此阶段已经注册；尽早撤销源八核平台把中断挤到 CPU0 的默认布局，
# 避免第一次用户解锁前就形成小核队列。该命令执行一次后立即退出。
sh "$MODDIR/scheduler/main.sh" irq-init >/dev/null 2>&1
