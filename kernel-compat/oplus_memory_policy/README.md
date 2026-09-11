# OPlus external memory-policy modules

## 当前状态（4.0.3）

这两个模块**已随发布包出货**：`build_oplus_memory_policy.sh` 产出的
`oplus_bsp_zram_opt.ko` 与 `oplus_bsp_kswapd_opt.ko` 已进 `oplus-bsp-module/ko/`，
并列在 `post-fs-data.sh` 的 MODULES 里，真机 `lsmod` 可见、`0 failed`。
本目录只保留源码与 Makefile，编译产物由同级 `.gitignore` 排除。

This directory carries the two independently-loadable policy modules used by
the reference OnePlus device:

- `oplus_bsp_zram_opt.ko` supplies the real `tune_swappiness` callback and
  `/proc/oplus_mem/{swappiness_para,dynamic_swappiness}` control plane.
- `oplus_bsp_kswapd_opt.ko` supplies allocation-reclaim gating and optional
  kswapd accounting controls.

They are deliberately external modules.  They do not replace zram, zsmalloc,
or HybridSwap.  `zram_opt` uses the same restricted anon/file-balance hook as
the reference device: it remains registered for the current boot, and is
reverted by disabling the KernelSU module and rebooting.  `process_reclaim` is
not included here because the matching external module is already loaded by
the current OPlus BSP package.

The module build enables only the source-local feature gates.  It does not
modify the kernel-wide configuration.  The HybridSwap swapd policy branch is
enabled module-locally; the running HybridSwap does not import
`free_swap_is_low_fp`, so this does not replace or override its driver path.
