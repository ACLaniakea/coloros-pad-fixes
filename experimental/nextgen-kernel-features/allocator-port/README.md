# OPlus zsmalloc 回移编译门禁

这里仅验证一加公开的 `thp_zsmalloc` 能否在当前 Android common 6.1/KMI 下编译。
它不是可刷写模块，也不会被 FixModule 自动加载。

当前平板正在使用的是标准 GKI `zsmalloc.ko` 加已验证的旧版
`oplus_mm_hybridswap_zram.ko`；参考手机则使用带
`zs_*_oplus` 私有 API 的 `oplus_bsp_zsmalloc.ko`。两者不能直接替换。

编译通过后仍需同时重编匹配的 HybridSwap、验证 6.1 ABI、再做独立 boot A/B，
不能仅凭本门禁产物在运行系统中替换 zsmalloc。

上游来源：

- <https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/oneplus/sm8750_b_16.0.0_oneplus_13/vendor/oplus/kernel/mm/thp_zsmalloc/zsmalloc.c>
- <https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/oneplus/sm8750_b_16.0.0_oneplus_13/vendor/oplus/kernel/mm/thp_zsmalloc/zsmalloc.h>
