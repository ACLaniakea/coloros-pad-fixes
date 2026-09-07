# LSPosed 依赖审计与 KernelSU 收敛方案

更新时间：2026-09-07

## 结论

本项目可以减少 KernelSU 模块数量，但不能仅靠把 `.ko` 编进内核就删除全部 LSPosed Hook。

原因是两者处在不同层：

- 内核内建目标负责调度、Binder、内存回收、HybridSwap、后台冻结和内核节点；
- LSPosed Hook 负责移植 ColorOS 后的 Java framework、Settings、AON、手写笔、设备标识、杜比 UI 和加密业务回调。

当前稳定基线 `r1+` 仍由 `oplus-bsp-module` 加载已验证的 OPlus 外挂 `.ko`；此前“全部内建”的候选在启动链上失败，已经回退，不能把它写成当前能力。`CONFIG_KSU` 保持关闭，继续使用当前可回退的 KernelSU 外置链，避免重复注入导致 root 管理器状态丢失。

## Hook 分类

### 不能由当前内核替代，暂时保留

| Hook | 作用 | 原因 |
| --- | --- | --- |
| `AmbientColorSensorBridge` | 环境光/色温曲线、RGB 动画和拖动状态 | 这是 system_server 的 ColorOS Java 状态机，不是单个内核节点 |
| `AonCameraOpBridge`、`AonYuvLayoutBridge`、`AonSmartFaceGazeCompat` | AON 与普通相机互斥、YUV 布局和人脸关注状态 | AON 的 QNN/DSP 运行时已由 KernelSU 恢复，但相机占用和回调仍发生在 framework/app 层 |
| `LenovoPenBridgeGuard`、PenBridge Hook | 手写笔连接、充电、电量、控制中心刷新和振动桥接 | 目标类属于 ColorOS/设备空间应用，内核只能提供输入事件，不能修复这些 Java 状态同步 |
| `DolbyBridgeService`、`SoundEffectsMenuFix` | 杜比场景、声场扩展和空间音频互斥 UI | 真实效果链之外的设置页面逻辑，需保持与原厂服务调用一致 |
| `OShareContactCryptoCompat`、`OmkmsSoftwareFallback` | 互传联系人/密码本的业务层兼容 | 只有真实 provisioning/HAL 链路完整通过后，才有条件逐项退役 |
| `StdIdGuidCompat`、`OplusSystemIdentityBridge` | 设备 GUID、型号和 ColorOS 身份兼容 | `resetprop` 只能覆盖全局属性，不能替代应用内部 SDK 的返回值和缓存逻辑 |

### 与内核无关，但属于低频 UI/兼容保护，暂时保留

`CpuHealthInfoBridge`、`RealtimeGpuBridge`、`BatteryHealthBridge`、`OcrScannerCameraBridge`、`LenovoColorModeBridge`、`CellularIdentityUiGuard`、`OrealityDisableBridge`、`HmbirdCompatGuard`、`UafQosCompat`、`SoundTriggerTrackerGuard` 和 `WiredAccessoryUEventGuard` 都是应用或 system_server 方法级兼容。它们不是常驻 daemon，只有对应作用域进程启动时才注入；删除它们不会解决内核压力，反而会让相应页面恢复错误值或重新暴露不适配入口。

### 可以在后续验证后退役

优先候选是 `OmkmsSoftwareFallback` 与 `OShareContactCryptoCompat`，前提是原厂 CryptoEng provisioning、联系人广播和密码本云同步在两台设备之间连续成功，并且重启后仍能成功。之后再逐个验证 `AonYuvLayoutBridge` 等只剩兼容兜底的 Hook，不能一次性删除。

## 已完成的冗余收敛

- 小布唤醒已由模块策略禁用：移除了 `ColorOSVoiceWakeupBridge` 的 LSPosed 入口，以及 `com.heytap.speechassist`、`com.oplus.ovoicemanager.wakeup`、`com.oplus.gesture` 三个仅服务于该实验的推荐作用域。代码保留在源码中作为历史参考，但不会再注入任何进程。
- 前摄指示灯已改为 `CameraServiceProxy` 的事件桥接；旧版每秒 `dumpsys media.camera` 的 shell 守护不再打包。
- 全量构建不再产出已废弃的 tuning 模块，避免后续 release 误带旧调优策略。

## 运行时开销核对

实机快照中，项目自身的常驻进程只有 AON namespace loader、应用建议的 SQLite `inotifyd` 监听、KGSL 状态同步、手电亮度 `inotifyd`、手写笔 CPS GPIO 以及用户主动安装的 Scene daemon。

| 项目 | 结论 |
| --- | --- |
| AON namespace loader | 保留。它必须跟随 AON 的动态 PID/私有 mount namespace，在 `nativeCreate` 前挂入已验证的 AIBoost/QNN 运行时；改成一次性启动会再次出现重启后随机失效。 |
| KGSL 状态同步 | 保留。每 6 秒仅读取小量节点，实机快照约 0.1% CPU；它负责把 cached/service-B 进程标为 background，并在回到前台时恢复，当前内核没有等价用户态写入者。 |
| 应用建议与手电 | 保留。两者均为阻塞式 `inotifyd`，没有周期性轮询。 |
| 手写笔 CPS | 保留。它是磁吸/供电边沿的硬件状态桥接，不能由一次性开机脚本替代。 |
| Scene daemon | 不属于 Fix 的冗余路径；它由用户安装的 Scene 模块提供。当前可见约 1% CPU，应由 Scene 场景策略单独评估，而非并入或删除 Fix。 |

源码中仍有 `ColorOSVoiceWakeupBridge` 和未注册的身份桥接类作为历史/实验参考。它们不在 `xposed_init`，不会被 LSPosed 实例化；清除其源码只会缩小 APK，不能再降低运行时 CPU，因此不以删除历史代码冒功能回归风险。

## 模块收敛方案

1. `oplus-bsp-module` 已兼容未来的内建内核：加载脚本先检查 `/sys/module`，已内建或已加载的目标直接跳过 `insmod`。在当前 r1+ 上，它仍承担已验证的外挂 `.ko` 加载职责。
2. 只有未来内建候选完成完整 9008 启动验证后，才可以停止日常外挂加载路径；在此之前不得移除 BSP 模块。
3. `fix-module` 保留 KernelSU 的 AON、温度、环境光资源和策略修复；它不能直接吸收 Java Hook 的功能。
4. BaseFix、PenBridge、ZUI 相机 Hook 是否合并为一个运行时包，要以作用域隔离和故障回退为条件；合并 APK 可以减少安装项，但不会消除运行时注入依赖。
5. 如果目标是彻底不安装 LSPosed，下一阶段只能把每个 Hook 改成静态 framework/app 补丁或单一 Zygisk 注入器，不能用 shell 脚本伪装替代。该迁移必须按功能逐项回归，不能和首次刷内核同时进行。

## 当前内核候选的安全边界

- 基线：`6.1.128-android14-11-sm8650q-droidspaces-r1+`；
- 当前活动 `vendor_boot_a` 的 ramdisk、DTB 和 bootconfig 与 `vendor_boot-hyperSched-stub.img` 一致，不是原厂实体 HyperSched；
- 候选保留外置 KernelSU，未启用内建 KernelSU；
- 候选已重打为 100 MiB 的 boot 镜像，但在完成 9008 启动验证前不得标记为 release；
- 回退只写回已读取并校验过的当前 r1+ `boot_a`，不使用全量 rawprogram 救砖流程。
