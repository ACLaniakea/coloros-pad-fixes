#!/system/bin/sh

MODDIR=${0%/*}

# ============================================================================
# 卸载时还原：把 active/systemserver 的 swappiness 重新对齐 ROM 基线，
# 恢复继承关系（等价于模块最后一次 service 写入后的状态）。
# ============================================================================

[ -f "$MODDIR/daemon.pid" ] && kill "$(cat "$MODDIR/daemon.pid")" 2>/dev/null
# 还原 post-fs-data 阶段对 osense 配置的 bind mount
umount /my_stock/etc/extension/sys_osense_memory_config.xml 2>/dev/null
umount /my_stock/etc/extension/sys_osense_io_decisionmaker_config.xml 2>/dev/null
# Restore only the cgroup defaults inherited from the current ROM baseline.
if [ -w /dev/memcg/apps/active/memory.swappiness ]; then
    cat /proc/sys/vm/swappiness >/dev/memcg/apps/active/memory.swappiness 2>/dev/null
fi
if [ -w /dev/memcg/apps/systemserver/memory.swappiness ]; then
    cat /proc/sys/vm/swappiness >/dev/memcg/apps/systemserver/memory.swappiness 2>/dev/null
fi
