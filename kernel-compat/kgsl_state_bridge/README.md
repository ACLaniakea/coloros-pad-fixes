# oplus_kgsl_state_bridge (v4.1.2)

补回移植包里缺失的 KGSL 显存回收触发者。由 `oplus-bsp-module` 随启动链加载，
配套 r3 内核。

> [!NOTE]
> 历史：4.1.0 做过熄屏全量回收版并移除（解锁时桌面 p95 帧时 24ms -> 101~121ms）；
> 4.1.1 改成 shrinker 版重新接回，但 tracepoint 仍会在 adj>=100 时无条件标记，
> 熄屏十几秒就把桌面 114MB 全部回收，待机后动画卡顿复现。4.1.2 按对照机
> PKX110 的行为重做了策略，见下。排查记录见
> `docs/investigations/4.1.1-卡顿与内存回收排查.md` 第 17 节。

## 它解决什么

联想这版 `msm_kgsl` 用高通较老的 process reclaim 设计：显存分配在 shmem 文件
里并钉成 unevictable，靠把进程标成 `background` 来解钉。移植包缺了写
`/sys/class/kgsl/kgsl/proc/<tgid>/state` 的厂商组件，链条第一环就断了：

```
没人写 state -> 进程恒在 PINNED 态 -> kgsl_reclaim_shrink_count_objects()
恒返回 0 -> 内核永远不调 scan_objects -> 显存永久不可回收
```

实测（3GB 内存压力）：`count_objects` 被 `do_shrink_slab` 调用 1328 次、
次次返回 0，`scan_objects` 命中 0 次。手工写一次 `background` 后 count 立刻
返回 `0xc800`，scan 开始执行。**机制本身完好。**

## 策略（对照机经验）

对照机的 GPU 显存根本不钉（`Unevictable` 恒 69MB），压力下由内核按页回收冷页；
前台 UI 栈不回收，解锁时桌面一帧都不重画；原厂 HAL 在亮屏后暂停 swapd 约 6 秒，
专门保护亮屏后的第一个交互窗口。平板只能整进程标记，所以规则是：

| 对象 | 何时标 background | 何时还原 foreground |
| --- | --- | --- |
| adj < 0（SF / system_server / SystemUI） | 永不 | — |
| 缓存进程 adj >= `cached_adj`(900) | 内存压力扫描 | adj 变化并落到 900 以下 |
| UI 带 `ui_adj`(100) <= adj < 900 | 内存压力扫描，且：亮屏、不在亮屏保护窗口、连续处于该带 >= 60s、`gpumem_mapped` >= 192MB | 熄屏立即还原；adj 真正变化时还原 |

- 压力信号来自注册的 shrinker：`count_objects` 只节流并排队，**回调里不做任何文件 I/O**。
- 192MB 门槛只会命中堆起来的桌面这类大户；壁纸（~53MB）、`com.oplus.blur`、
  侧边栏、输入法永远不碰。
- 屏幕状态挂 msm_drm 的 `dsi_panel_power_off` / `dsi_panel_power_on`
  （`panel_event_notification_trigger` 在本机只送 FPS 变化）。kprobe 注册失败时 UI 带自动停用。
- 同值 adj 重写不算变化，避免"回收 -> 钉回"来回。

## 实现上的两条红线

1. **不调用 `msm_kgsl` 的私有函数。** kprobe 取 `kgsl_proc_state_store` 地址再
   间接调用会被 kCFI 拒绝，实测直接 panic。
2. **只走公开接口**：`filp_open` + `kernel_write` 写 `state`；读 `gpumem_mapped`
   时 `kernel_read` 不在 r3 白名单里，改走该 sysfs 文件自己的 `read_iter`
   （类型匹配的函数指针调用，kCFI 放行）。

## 必须配套的 SELinux 规则

见 `fix-module/module/sepolicy.rule`：

```
allow kernel vendor_sysfs_kgsl_proc file { open read write getattr }
allow kernel vendor_sysfs_kgsl_proc dir search
allow kernel vendor_sysfs_kgsl dir search
allow kernel sysfs dir search
```

缺 `write` 时 `stat_failed` 增长（-13）；缺 `read` 时 `stat_read_failed` 增长，
UI 带按"不回收"处理。

## 运行时参数

`/sys/module/oplus_kgsl_state_bridge/parameters/`

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `bridge_enabled` | Y | 总开关，关掉后不再有新的 background 标记 |
| `cached_adj` | 900 | 缓存进程下限 |
| `ui_band_enabled` | Y | 是否考虑 UI 带 |
| `ui_adj` | 100 | UI 带下限 |
| `ui_min_mapped_mb` | 192 | UI 带显存门槛 |
| `ui_min_age_sec` | 60 | UI 带连续遮挡时长 |
| `wake_grace_sec` | 30 | 亮屏保护窗口 |
| `shrinker_throttle_sec` | 30 | 两次压力扫描的最小间隔 |
| `display_on` | — | 只读，当前面板状态 |
| `stat_*` | — | 只读计数：`events` `writes_bg` `writes_fg` `missing` `failed` `read_failed` `slots_full` `shrinker_calls` `shrinker_sweeps` `marked_cached` `marked_ui` `screen_off_restores` |

`stat_missing` 计的是没有 KGSL 上下文的进程，属正常——大多数进程不碰 GPU。

卸载（`rmmod`）会把**所有**进程还原 foreground。

## 构建

```sh
S=/home/ACLaniakea/oplus-src-recovered-20260830/oplus-src
make -C $S/aosp/src O=$S/out-r3 M=$PWD ARCH=arm64 LLVM=1 LLVM_IAS=1 modules
cp oplus_kgsl_state_bridge.ko ../../oplus-bsp-module/ko/
```

r3 内核保持 GKI 符号裁剪开启，白名单里只额外加了 `filp_open` 与
`kernel_write`（`filp_close` 本就存在）。
