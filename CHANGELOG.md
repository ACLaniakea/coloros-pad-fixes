# 变更记录

每个版本的一句话摘要。详情见 [docs/release-notes/](docs/release-notes/)。

本项目的版本号在所有模块与 APK 间保持统一，一个 Release 内的产物必须配套使用。

## [4.1.0](docs/release-notes/4.1.0.md)

修好 HybridSwap 那条从未闭合过的熄屏闸门：面板 kprobe 挂错了事件源，本机只收到
FPS 变化（notif_type=4，负载 144/120），于是 display_off 永远为 0——待机时该停的
主动回收从未停过；而 bridge_active 又只看"注册成功"就把 HAL 那路 swapd_pause
一并丢弃，对照机 swapd_manual_pause 2.27 亿次、平板恒为 0。改挂
dsi_panel_power_off/on 并要求真的观测到过转换才接管。
补上 /dev/mqueue 挂载——4.0.4 的能力检测用了 toybox 不认的 \b，匹配恒空，挂载
从未执行过。修正 BSP 打包脚本的写死白名单，它会静默漏掉新增文件。
配套内核为 r2，须与阻塞版 vendor_boot 成对刷入。
本版还调查过 KGSL 显存回收链并做出内核桥（熄屏实测释放 450~510MB），但最终移除：
扫过 ROM 里所有引用该接口的组件，全是只读统计，没有任何一个写 state，参照机同样
如此；而且收益在待机时无人消费，却要在下一次解锁时偿还（桌面 95 分位帧时
24ms -> 101~121ms），加之实测内核击杀记录为 0，连防 OOM 的理由也不成立。
同时更正 4.0.4 的一条结论：那个面板 kprobe 从来没送过 blank/unblank。

## [4.0.4](docs/release-notes/4.0.4.md)

恢复被移植弄断的 AOT、osvelte 与 HybridSwap 链路，并将 OPlus performance HAL
桥接到 Lenovo 面板的真实事件。HAL 保持运行；内核侧修正其亮屏后延迟暂停 swapd 的
时序失配，并节流 Lenovo slowpath 的重复唤醒。曾尝试的 `snapshotd` 定时 refault 基线
刷新已在 r2 撤回：它把原厂事件驱动机制错误改为 5Hz 轮询。最终确认 4K ZRAM
兼容路径把每次普通 `pswpin` 都误计进 HybridSwap 私有 `fault_cnt`，已改为只统计
真正的 `ZRAM_WB` fault-out；原厂阈值与事件驱动快照保持不变。
同时将移植脚本写死的 `lz4` 改为对照机原厂实际使用的标准 `zstd`；等量约 5GB
ZRAM 数据下物理占用减少约 500MB，锁屏窗口同步 direct reclaim 降低约 39%。
启动期再将旧 performance HAL 的精确输出 `2300/2000/2300` 一次性桥接到对照机
运行档 `2500/2200/2500`；有限等待后退出，不常驻，也不覆盖未知或自定义值。
重新启用已完成 A/B 的 zsmalloc CMA 边界修复：仅从 `zs_malloc` 请求清除
`__GFP_CMA`，避免 ZRAM 与显示争抢 CMA；并移除 KGSL 的 6 秒常驻 shell 轮询。
此前已否决的参考机 watermark hook 不包含在发布版本。

## [4.0.3](docs/release-notes/4.0.3.md)

调度链三项：把 sched_assist 的负载均衡入口接回调度 tick、把 UX 标记翻译成 WALT
认识的任务 boost、补回帧组对 SurfaceFlinger 的提频与迁移加成；并移除挤占 per-CPU
预留的 `oplus_resctrl`。

## [4.0.2](docs/release-notes/4.0.2.md)

相机身份层去掉写死的 UID，新增 8GB 内存档（该档已在 4.0.4 撤销、交还原厂）。

## [4.0.1](docs/release-notes/4.0.1.md)

统一各模块与 APK 的版本号，重写模块与应用简介，补齐发布说明。

## [4.0.0](docs/release-notes/4.0.0.md)

自建内核基线：GKI 6.1.128-android14 + 289 个 Lenovo vendor 模块通过 ABI 门禁。

## [3.2.1](docs/release-notes/3.2.1.md)

CryptoEng HAL 并入 FixModule。

## [3.2.0](docs/release-notes/3.2.0.md)

AON 相机与 DSP 链路修复，模块统一。

## [3.1.0](docs/release-notes/3.1.0.md)

查找设备、互传联系人、前摄指示灯修复。
