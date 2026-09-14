> [!IMPORTANT]
> **本模块已不随发布包出货，仅作为已排除路径的记录保留。**
>
> 移除理由：逐个扫过 ROM 里所有引用 `kgsl/kgsl/proc` 的组件
> （`libAlgoProcess.so`、`memtrack-service`、`autochmod.sh`、若干 sepolicy），
> **全是只读统计，没有任何一个写 `state`**；参照机 PKX110 命中同一批组件、
> 同样只读，且其 `proc/<pid>/` 下连 `state` 节点都没有。原厂不驱动这套机制。
>
> 另有实测：桥带来的待机回收要在下一次解锁时偿还——桌面 95 分位帧时从 24ms
> 涨到 101~121ms；而平板内核击杀记录为 0、PSI `some avg10=0.30`，并不缺内存。
>
> 下面的技术内容仍然有效，作为这条链路的分析记录。

# oplus_kgsl_state_bridge

补回移植包里缺失的 KGSL 显存回收触发者。

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

## 两个触发点

| 触发点 | 覆盖对象 | 说明 |
| --- | --- | --- |
| `dsi_panel_power_off` / `dsi_panel_power_on`（kprobe） | 全部进程 | 主路径。熄屏全部标 background，亮屏按各自 adj 还原 |
| `oom_score_adj_update`（tracepoint） | `adj >= adj_threshold` | 辅助路径，默认阈值 800 |

**为什么主路径不是 adj**：桌面的 `oom_score_adj` 恒为 100，开应用、回桌面都
不变，该 tracepoint 上涉及桌面的事件为 0 条（事件全部来自 lmkd，写的是
930/940 这类缓存进程）。而能回收的量几乎全在桌面身上——adj>=700 的进程加
起来只有 21MB，桌面单进程 129~1187MB。熄屏是唯一既覆盖桌面、风险又为零的边。

**为什么不用 `panel_event_notification_trigger`**：在这台机器上它只送 FPS
变化（`notif_type=4`，负载 144/120，正是这块 144Hz 屏的刷新率），从不送
blank/unblank。

## 实现上的两条红线

1. **不调用 `msm_kgsl` 的私有函数。** 早期版本用 kprobe 取
   `kgsl_proc_state_store` 地址再间接调用，并猜 kobject 在
   `kgsl_process_private` 里的偏移（+0x68）。kCFI 拒绝这种间接调用，实测
   直接 panic。
2. **只走公开接口**：`filp_open` + `kernel_write`，等价于 shell 里
   `echo background > .../state`。所有文件 I/O 都在工作队列里，
   tracepoint 与 kprobe 回调只置标志并排队。

## 必须配套的 SELinux 规则

见 `oplus-bsp-module/sepolicy.rule`。写入主体域是 `kernel`，缺规则时
`filp_open` 返回 `-EACCES`，实测 59 次写入全军覆没。

## 运行时参数

`/sys/module/oplus_kgsl_state_bridge/parameters/`

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `adj_threshold` | 800 | 达到该 adj 才标 background |
| `screen_off_sweep` | Y | 熄屏全量回收；关掉后亮屏仍会还原 |
| `bridge_enabled` | Y | 总开关 |
| `stat_events` / `stat_writes` / `stat_missing` / `stat_failed` / `stat_slots_full` | — | 只读计数。`stat_failed` 非零通常就是缺 SELinux 规则 |

`stat_missing` 计的是没有 KGSL 上下文的进程，属正常——大多数进程不碰 GPU。

## 构建

```sh
make -C <kernel-src> O=<out-r3> M=$PWD ARCH=arm64 LLVM=1 LLVM_IAS=1 modules
```

r3 内核保持 GKI 符号裁剪开启，白名单里只额外加了 `filp_open` 与
`kernel_write`（`filp_close` 本就存在）。
