# 一加 mm/proactive_compact：只读的碎片度上报，导出 /proc/oplus_mem/fragmentation_index。
# 不用任何 vendor hook，也不自己发起规整——规整由用户态 oplus_compact_memory 服务
# 读到 is_fragmented=1 之后经 /proc/sys/vm/compact_memory 触发。
obj-m += oplus_mm_proactive_compact.o
oplus_mm_proactive_compact-y := proactive_compact.o
