ifneq ($(KERNELRELEASE),)
obj-m += oplus_sched_assist.o
else
KERNEL_SRC ?=
KERNEL_OUT ?=
all:
	$(MAKE) -C "$(KERNEL_SRC)" O="$(KERNEL_OUT)" M="$(CURDIR)" ARCH=arm64 LLVM=1 modules
clean:
	$(MAKE) -C "$(KERNEL_SRC)" O="$(KERNEL_OUT)" M="$(CURDIR)" ARCH=arm64 LLVM=1 clean
endif
