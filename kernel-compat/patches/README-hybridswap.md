# hybridswap 补丁现状(2026-09-14 核实)

以下补丁**已编进在用的 `oplus_mm_hybridswap_zram.ko`**,在设备上核实过符号:

| 补丁 | 作用 | 核实方式 |
|---|---|---|
| `oplus-hybridswap-zram-opt-callback.patch` | `free_swap_is_low_fp` 改 extern,接回 zram_opt | `oplus_bsp_zram_opt` 依赖列里出现 hybridswap |
| `oplus-hybridswap-panel-kprobe-fallback.patch` | kprobe 版面板事件桥,替代编不出来的原生 notifier | 模块内有 `hybridswap_panel_event_pre_handler` |
| `oplus-hybridswap-panel-hal-semantics.patch` | 配套的 ops 语义调整 | 同上 |
| `oplus-hybridswap-slowpath-wake-throttle.patch` | 给 `vh_alloc_pages_slowpath` 的 `wake_all_swapd()` 加 200ms 节流 | 模块内有 `last_slowpath_wake` |
| `oplus-hybridswap-fault-counter-scope.patch` | 4K ZRAM 下只统计真正的 `ZRAM_WB` fault-out，避免把普通 `pswpin` 当成 refault | `hybridswap_stat_snap` 的首个 `fault_cnt` 不再跟随纯 ZRAM 换入增长 |

**已删除**:`oplus-hybridswap-refault-snapshot-fallback.patch` 与 `oplus-hybridswap-wmark-wakeup.patch`。
前者把原厂事件驱动的 `snapshotd` 错改为 5Hz 轮询，实测 `swapd_hit_refaults` 仍近乎全票命中；
后者把 hook 从 `android_vh_alloc_pages_slowpath`
换成 `android_vh_get_page_wmark`,而后者挂在 `zone_watermark_fast()`——每次页分配都走的最热路径,
回调体又是 `if (alloc_flags) wake_all_swapd();`,等于每分配一页唤醒一次。当时已回滚,
在用的模块里查无 `android_vh_get_page_wmark`。**不要重新引入。**

## 遗留的两处待办(不影响运行,下次动这块时一并修)

1. slowpath 节流的开关挂在 `CONFIG_OPLUS_HYBRIDSWAP_PANEL_KPROBE_FALLBACK` 上,
   那是面板回退的配置,与节流语义无关;且 `unsigned long last;` 声明在 `#ifdef` 外、
   只在内部使用,配置一旦关掉就是 `-Wunused-variable`。
2. 该节流的实际价值存疑:`wake_all_swapd()` 内部对快照刷新本就有 200ms 节流,
   在调用方再卡一道会把 swapd 在真正吃紧时的响应上限压到 5 次/秒。有数据再决定去留。
