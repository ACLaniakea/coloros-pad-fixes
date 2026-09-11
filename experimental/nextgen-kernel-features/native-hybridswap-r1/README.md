# r1+ Native HybridSwap allocator port

This directory records the source-level allocator change for the running
`6.1.128-android14-11-sm8650q-droidspaces-r1+` baseline.

The current OPlus HybridSwap module was built from the older
`oplus_performance_5.10/mm/hybridswap_zram` tree. Its zsmalloc fast and slow
paths pass `__GFP_CMA`; the newer OnePlus implementation does not. The patch
removes that flag only from the four compressed-swap `zs_malloc()` calls. It
does not alter ordinary page allocation, display/CMA clients, zram size,
swappiness, or HybridSwap thresholds.

`include/linux/healthinfo/fg.h` is only the missing build declaration for the
existing `is_fg()` export. It does not create a fake runtime node or service.

Status: source patch and r1+ compilation gate only. The resulting module is
not installed or added to the boot module list yet. Replacement requires a
separate boot-time A/B test because the current zram device is already active.
