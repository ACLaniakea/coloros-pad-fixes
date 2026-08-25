ifneq ($(KERNELRELEASE),)
ccflags-y += -DCONFIG_ZRAM_WRITEBACK -DCONFIG_ZRAM_MEMORY_TRACKING
aclswap-y := zcomp.o zram_drv.o
obj-m += aclswap.o
else
all:
	$(MAKE) -C "$(KERNEL_SRC)" O="$(KERNEL_OUT)" M="$(CURDIR)" ARCH=arm64 LLVM=1 modules
endif
