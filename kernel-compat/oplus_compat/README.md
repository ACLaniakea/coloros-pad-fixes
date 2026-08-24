# OPlus kernel compatibility modules

This directory contains narrowly scoped compatibility modules for running
ColorOS userspace on the Lenovo TB710FU vendor kernel. They are not binary
copies of OnePlus modules.

`oplus_shell_temp_compat` implements the `/proc/shell-temp` ABI consumed by
OPlus Horae. Horae remains the producer of the three shell-temperature values;
the module only stores them and exposes their maximum, matching the official
OPlus protocol. It deliberately does not register fake thermal zones.

`oplus_mm_compat` implements the safe, externally loadable portion of the
OPlus memory control plane on top of the existing standard GKI `zram` backend:

- `/proc/oplus_mem/swappiness_para` and `dynamic_swappiness` accept the OPlus
  parameter format, but only clamp the reclaim decision. They never raise the
  kernel/cgroup-selected swappiness. Safety ceilings are 40 for kswapd and 20
  for direct reclaim, preventing a ColorOS policy intended for full
  HybridSwap from forcing standard zram to swappiness 160/200.
- `kswapd_debug` and `kswapd_load_stat` report real slow-path, wake and runtime
  counters from GKI tracepoints. Their hooks default to completely unregistered
  and are installed only while the corresponding proc control contains `1`, so
  production reclaim does not pay an atomic-counter cost.
- `alloc_adjust_ctrl` exposes the OPlus high-order allocation optimization but
  defaults to `0`; its allocation hooks are likewise unregistered at `0`.
  Device testing observed frequent order-9 slow paths, so this control must
  remain off: masking reclaim could turn display/GPU pressure into allocation
  failure.
- `compat_status` explicitly reports `backend=standard_zram` and
  `hybridswap=0`. No fake HybridSwap or zram-writeback node is created.

The full OPlus HybridSwap tree replaces `zram_drv`, `zcomp` and the associated
reclaim workers. It cannot safely coexist with the Lenovo kernel's already
loaded standard `zram.ko`; it requires a full vendor-kernel rebuild.

The current tablet kernel maps exactly to Google GKI build `13606743`, common
commit `5c2cea985a841939e6d074cbed2019dec0245fcd`. Build against that exact source,
configuration, generated output and `Module.symvers`:

```sh
make \
  KERNEL_SRC=/path/to/common-5c2cea985a84 \
  KERNEL_OUT=/path/to/gki-13606743-modules_prepare \
  LLVM=1 ARCH=arm64
```

Do not install a module built against a merely similar 6.1 kernel. This GKI
uses `CONFIG_MODVERSIONS`; mismatched CRCs are not safe or loadable.
