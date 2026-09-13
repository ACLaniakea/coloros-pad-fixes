# 变更记录

每个版本的一句话摘要。详情见 [docs/release-notes/](docs/release-notes/)。

本项目的版本号在所有模块与 APK 间保持统一，一个 Release 内的产物必须配套使用。

## [4.0.4](docs/release-notes/4.0.4.md)

恢复被移植弄断的 AOT、osvelte 与 HybridSwap 链路，并将 OPlus performance HAL
桥接到 Lenovo 面板的真实事件。HAL 保持运行；内核侧修正其亮屏后延迟暂停 swapd 的
时序失配，并节流 Lenovo slowpath 的重复唤醒。补回 `snapshotd` 的 200ms refault 基线
刷新；此前已否决的参考机 watermark hook 不包含在发布版本。

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
