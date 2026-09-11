obj-m += oplus_bsp_zram_opt.o
obj-m += oplus_bsp_kswapd_opt.o

# These are module-local OPlus feature gates.  The running r1+ kernel already
# exports all corresponding Android vendor hooks; making them module-local
# avoids changing the kernel-wide .config or the existing HybridSwap driver.
ccflags-y += -DCONFIG_DYNAMIC_TUNING_SWAPPINESS
ccflags-y += -DCONFIG_OPLUS_FEATURE_KSWAPD_OPT
ccflags-y += -DCONFIG_HYBRIDSWAP_SWAPD
# The reference device enables the same existing Android reclaim hook.  This
# is scoped to zram_opt and selects anon/file balance only above its native
# free-page watermark; it does not alter the global VM policy.
ccflags-y += -DCONFIG_OPLUS_BALANCE_ANON_FILE_RECLAIM
