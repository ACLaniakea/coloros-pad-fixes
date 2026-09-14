# r3 内核与 KGSL 桥验证（2026-09-14）

## 内核结果

- 以 `droidspaces-r2.config` 为基线，版本串改为 `6.1.128-android14-11-sm8650q-droidspaces-r3`。
- 保留 SysV IPC KABI 槽位、32 KB module percpu、Gloom/pcppages hooks、Binder hook 与原厂 `TRIM_UNUSED_KSYMS`/BTF 配置。
- `module_layout` CRC 仍为 `0xea759d7f`；新增 `filp_open`、`kernel_write` 白名单导出不改变现有 vendor ABI CRC。
- 289 个 Lenovo/QTI vendor 模块静态检查：3615 个符号对，CRC mismatch 为 0。脚本对厂商内部 tracepoint/时钟符号仍会列出缺失项，这是该脚本的已知边界，r2 基线也同样列出。
- 通过 9008 只写 `boot_a`，写入大小 34,451,456 字节；读回分区前缀与 r3 镜像 SHA256 完全一致。未触碰 super、userdata 或其它分区。
- 首次冷启动成功，约 15 秒到 `sys.boot_completed=1`，`lsmod` 490，未见 vendor 模块 ABI 拒载、unknown symbol、Oops 或 panic。

## KGSL 桥结论（2026-09-14 晚更新：已做通并入库）

**本节此前的结论"桥禁用且不应随启动链加载"已作废。** 当时两条实现路径确实
都撞墙，但那是实现问题，不是机制问题；后续把根因追到底之后，换一条实现就通了。

### 根因

联想这版 `msm_kgsl` 用高通较老的 process reclaim 设计：显存分配在 shmem 文件里
并钉成 unevictable，靠把进程标成 background 解钉。移植包缺了写
`/sys/class/kgsl/kgsl/proc/<tgid>/state` 的厂商组件，链条第一环就断了：

```
没人写 state -> 进程恒在 PINNED 态 -> kgsl_reclaim_shrink_count_objects()
恒返回 0 -> 内核永远不调 scan_objects -> 显存永久不可回收
```

kprobe 实测：3GB 内存压力下 `count_objects` 被 `do_shrink_slab` 调用 **1328 次、
次次返回 0**，`scan_objects` 命中 **0 次**。手工写一次 background 后 count 立刻
返回 `0xc800`，scan 开始执行，12 秒内吐出 150MB。**机制本身完好。**

对照手机：`page_alloc` 719MB 对 `Unevictable` 71MB（10%）；平板是 679MB 对
677MB（100%）。手机的 `proc/<pid>/` 连 `state` 和 `gpumem_reclaimed` 节点都没有，
走的是另一代驱动，不需要这条链。

### 两条死路及其真实原因

1. **kprobe 取 `kgsl_proc_state_store` 地址间接调用**——kCFI 拒绝，panic。而且
   kobject 在 `kgsl_process_private` 里的偏移是猜的（+0x68）。这条路不该走。
2. **`filp_open`/`kernel_write` 写 sysfs 被 `-EACCES`**——这不是死路，是**缺一条
   SELinux 规则**。补上 `allow kernel vendor_sysfs_kgsl_proc file { open write
   getattr }` 之后，实测写入失败数从 59 归零。

### 触发点走过的两次弯路

- **`oom_score_adj` 不可用**：桌面的 adj 恒为 100，开应用、回桌面都不变。用
  `events/oom/oom_score_adj_update` 抓完整来回，涉及桌面的事件 **0 条**；该
  tracepoint 上的事件全部来自 lmkd，写的是 930/940 这类缓存进程。而能拿的量
  几乎全在桌面身上（adj>=700 的进程加起来只有 21MB，桌面单进程 129~1187MB）。
- **`panel_event_notification_trigger` 不送 blank/unblank**：这台机器上它只送
  FPS 变化（`notif_type=4`，负载 144/120，正是这块 144Hz 屏的刷新率）。这也
  解释了 `oplus_hybridswap_panel_bridge` 当初为什么被排除。
  改挂 msm_drm 的 `dsi_panel_power_off` / `dsi_panel_power_on`，每次转换各触发
  一次、无歧义。

### 最终形态与实测

`kernel-compat/kgsl_state_bridge/` + `oplus-bsp-module/sepolicy.rule`，
由 BSP 模块随启动链加载。冷启动后实测：

| | 熄屏前 | 熄屏后 | 亮屏后 |
|---|---|---|---|
| `Unevictable` | 1194 MB | **745 MB** | 恢复 |
| `MemAvailable` | 1771 MB | **1965 MB** | — |
| 桌面 state | foreground | background（回收 199MB） | foreground |
| background 进程数 | 0 | 14 | 0 |

`writes=37 failed=0`，SELinux 拒绝 0 条，桌面同 pid 存活，屏幕正常唤醒。

### 已知上限

`max_reclaim_limit = 51200` 页 = **200MB/进程**，桌面持有 827MB 时也只让出 199MB。
抬高它可能多放出几百兆，但一次实测赶上基线自己在动（熄屏期间 Unevictable 反而
往上跳），数据不足以下结论，暂时保留原厂值。

## 手机/平板对照修正

进一步检查发现，手机的 `msm_kgsl` 根本没有 `proc/<tgid>/state` 和
`gpumem_reclaimed` 节点；这是平板 Lenovo KGSL 的额外接口。手机额外加载的
`oplus_bsp_geas_system`、`oplus_bsp_geas_cpu`、`oplus_bsp_fg_protect` 与
`oplus_bsp_level_protect` 属于 6.6/Android 15 手机链，依赖和硬件拓扑均不同，
不能直接移植到 6.1/SM8650Q 平板。且手机运行时 `fg_protect_enable=0`、GEAS
frame drive 也为 0，因此它们不是当前 refault 差异的直接证据。

在 r3 空载状态下把 9 个 KGSL state 改成 background 未见变化——事后确认那次
测试条件不成立：刚开机的空载机上根本没有攒起显存的进程，且没有叠加内存压力。
在真实负载下重测，机制工作正常，见上。

## 回滚

`kernel-backup/boot_a-r2-current-20260914.img` 是刷写前完整 r2 `boot_a`，SHA256：

```text
8eb1d1335ba8788081f29c092bde7d56fb47a3bc1fa86825fb370edbcbcec95d
```

9008 loader：`kernel-lab/tb710fu-edl-recovery/prog_firehose_ddr-TB710FU.melf`。只写 `boot_a`，写回后用 `edl setactiveslot a`/正常 fastboot 恢复活动槽。
