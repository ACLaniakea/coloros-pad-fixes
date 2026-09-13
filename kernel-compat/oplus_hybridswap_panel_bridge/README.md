# oplus_hybridswap_panel_bridge —— 已废弃,仅留档

早期尝试:用独立内核模块探测面板事件,把熄屏状态喂给 hybridswap 的 `display_off`。

**已被取代**:`oplus-hybridswap-panel-kprobe-fallback.patch` 把 kprobe 版的面板事件桥
直接编进了 `oplus_mm_hybridswap_zram.ko`(实测模块里有
`hybridswap_panel_event_pre_handler`),两者探测同一事件,同时装会重复注册。

`build_oplus_bsp.py` 的 `EXCLUDED_KOS` 已把它挡在发布包外,`ko/` 目录下的产物也已移除。
源码保留在此仅供追溯,**不要加回模块清单**。
