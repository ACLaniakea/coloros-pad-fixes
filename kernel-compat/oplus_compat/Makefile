ifneq ($(KERNELRELEASE),)
obj-m += oplus_shell_temp_compat.o
obj-m += oplus_mm_compat.o
else
KERNEL_SRC ?=
KERNEL_OUT ?=
LLVM ?= 1
ARCH ?= arm64

.PHONY: all clean

all:
	@test -n "$(KERNEL_SRC)" || (echo "KERNEL_SRC is required" >&2; exit 2)
	@test -n "$(KERNEL_OUT)" || (echo "KERNEL_OUT is required" >&2; exit 2)
	$(MAKE) -C "$(KERNEL_SRC)" O="$(KERNEL_OUT)" M="$(CURDIR)" \
		ARCH="$(ARCH)" LLVM="$(LLVM)" modules

clean:
	@test -n "$(KERNEL_SRC)" || (echo "KERNEL_SRC is required" >&2; exit 2)
	@test -n "$(KERNEL_OUT)" || (echo "KERNEL_OUT is required" >&2; exit 2)
	$(MAKE) -C "$(KERNEL_SRC)" O="$(KERNEL_OUT)" M="$(CURDIR)" \
		ARCH="$(ARCH)" LLVM="$(LLVM)" clean
endif
