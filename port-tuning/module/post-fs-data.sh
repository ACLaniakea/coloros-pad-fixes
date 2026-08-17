#!/system/bin/sh

MODDIR=${0%/*}

# ============================================================================
# 联想平板 Pro GT - ColorOS 调优 · post-fs-data 阶段
# 在 system_server 启动前，用本模块的 osense 配置 bind mount 覆盖 /my_stock
# 原文件（KernelSU 不挂载 my_stock，直接 bind mount 最可靠）：
#   1) sys_osense_memory_config.xml：swap 策略 switch=false，禁用 osense 对
#      后台应用的主动 zram 换出（长待机换出-换入抖动的主因，实测持续
#      120MB/s 换出、76MB/s 换入）；
#   2) sys_osense_io_decisionmaker_config.xml：移除 IO PSI reentrant/iostop
#      高频清理规则，避免与 zram 换出形成循环。
# 仅适用于 SM8650Q / pineapple 平台。
# ============================================================================

export PATH="/sbin:/system/bin:/system/xbin:/vendor/bin:$PATH"

# 等待 /my_stock 分区可用
wait_count=0
while [ ! -f /my_stock/etc/extension/sys_osense_memory_config.xml ] &&
      [ "$wait_count" -lt 30 ]; do
    sleep 1
    wait_count=$((wait_count + 1))
done

bind_over() {
    src="$MODDIR/payload/osense/$1"
    dst="/my_stock/etc/extension/$1"
    if [ -f "$src" ] && [ -f "$dst" ]; then
        mount --bind "$src" "$dst" 2>/dev/null && \
            echo "[post-fs-data] bind mounted $dst" >>"$MODDIR/tuning.log"
    fi
}

bind_over sys_osense_memory_config.xml
bind_over sys_osense_io_decisionmaker_config.xml
