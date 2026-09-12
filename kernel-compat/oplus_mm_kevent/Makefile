# 一加 multimedia/feedback：内核 -> 用户态的 generic netlink 事件通道。
# 缺了它，/system_ext/bin/bsp_kevent 拿不到 netlink 家族，启动即退出，
# ColorOS 的 DCS/OLC 事件上报整条链是断的。两个 ko 有先后：kevent 先，fb 后。
obj-m += oplus_mm_kevent.o
obj-m += oplus_mm_kevent_fb.o
ccflags-y += -I$(src) -DCONFIG_OPLUS_FEATURE_MM_FEEDBACK
