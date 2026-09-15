# hybridswap 补丁现状(2026-09-14 核实)

> **2026-09-15 重编门禁（实机 panic 后确认）**：平板运行内核启用了
> `CONFIG_SYSVIPC`，但通过 `gki-6.1.128-sysvipc-kabi-slots.patch` 把其状态放入
> Android KABI reserve 槽，因而 `task_struct` 不能内联 `sysvsem/sysvshm`。恢复源码
> 的未打补丁状态会多出 24 字节，使 `force_shrink_batch()` 采用错误的
> `pending/signal=0x8e1/0x8a8`（正确值为 `0x8c9/0x890`），已实机触发对 `0x51` 的
> NULL dereference 和 kernel panic。
>
> 每次重编必须先应用该 KABI 补丁，并在复制到设备前运行：
> `python3 kernel-compat/tools/verify_hybridswap_task_abi.py <hybridmain.o-or-ko>`。
> vermagic、modversion CRC 和外部符号一致仍不足以证明安全。已用恢复备份离线构建
> 验证：应用该补丁后，候选 `hybridmain.o` 的完整 `task_struct` 布局和三处关键指令
> 与当前稳定模块一致；未通过此门禁的产物一律不得装机。

以下补丁**已编进在用的 `oplus_mm_hybridswap_zram.ko`**,在设备上核实过符号:

| 补丁 | 作用 | 核实方式 |
|---|---|---|
| `oplus-hybridswap-zram-opt-callback.patch` | `free_swap_is_low_fp` 改 extern,接回 zram_opt | `oplus_bsp_zram_opt` 依赖列里出现 hybridswap |
| `oplus-hybridswap-panel-kprobe-fallback.patch` | kprobe 版面板事件桥,替代编不出来的原生 notifier | 模块内有 `hybridswap_panel_event_pre_handler` |
| `oplus-hybridswap-panel-hal-semantics.patch` | 配套的 ops 语义调整 | 同上 |
| `oplus-hybridswap-panel-real-blank-edge.patch` | **修正上面两条面板补丁的事件源**：改挂 `dsi_panel_power_off`/`dsi_panel_power_on`，并要求桥真的观测到过转换才接管 | 熄屏 30 秒 swapd 完全停转，亮屏恢复 |
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


## 关于那两条面板补丁

`oplus-hybridswap-panel-kprobe-fallback.patch` 与
`oplus-hybridswap-panel-hal-semantics.patch` **保留原样**，它们是从原始源码到
当前状态的必经一步；但它们选的事件源在本机是错的，必须叠加
`oplus-hybridswap-panel-real-blank-edge.patch` 才能得到可用的结果。三条按顺序应用。

错在哪：`panel_event_notification_trigger` 在这台机器上只送 `notif_type=4`
（FPS 变化，负载 144/120），从不送 blank/unblank。而 `bridge_active()` 只看
kprobe 注册标志——注册确实成功了——于是既没有正确的 `display_off`，又把 HAL
那路 `swapd_pause` 一并丢弃。对照机 PKX110 的 `swapd_manual_pause` 是 2.27 亿次，
平板修复前恒为 0。

**教训：判定一个探针"可用"要看它有没有真的收到过事件，不能只看注册返回值。**

## 已删除的组件

`kernel-compat/oplus_hybridswap_panel_bridge/`（独立面板桥模块）与配套构建脚本
已从仓库删除。它挂的是同一个只送 FPS 事件的函数，从来没有起过作用，且早已被
排除在出货包之外。`build_oplus_bsp.py` 里的 `EXCLUDED_KOS` 保留为守卫，防止
旧构建产物被误放回 `ko/`。
