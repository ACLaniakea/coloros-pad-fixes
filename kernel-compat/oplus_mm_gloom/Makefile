# 一加 mm/gloom：虚拟地址空间(VA)特性上报，导出 /proc/oplus_mem/gloom_va_feature
# 与 gloom_va_feature_debug。消费方是 /system/lib64/libandroid_runtime.so，
# 每个应用进程都会读，移植包里内核这一端一直是缺的。
# 不注册任何 vendor hook，也不使用 per-CPU 变量，因此不占 PERCPU_MODULE_RESERVE。
obj-m += oplus_mm_gloom.o
oplus_mm_gloom-y := gloom_common.o mmap_util.o reserve_area.o va_feature_hash.o va_feature_node.o
ccflags-y += -I$(src) -Wno-error -DCONFIG_VIRTUAL_RESERVE_MEMORY -DCONFIG_OPLUS_FEATURE_HEALTHINFO=1
ccflags-y += -I$(src)/../include
