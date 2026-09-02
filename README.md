<p align="center">
  <img src="assets/readme-hero.png" alt="ColorOS Pad Fixes — 联想小新 Pad Pro GT · SM8650Q" width="100%">
</p>

# ColorOS Pad Fixes

面向联想小新 Pad Pro GT（TB710FU / SM8650Q / pineapple）的 ColorOS 16（OPD2513）移植系统适配项目。

[![Platform](https://img.shields.io/badge/platform-SM8650Q%20%2F%20pineapple-blue)](#兼容性)
[![Android](https://img.shields.io/badge/Android-16%20%2F%20ColorOS%2016-green)](#兼容性)
[![Release](https://img.shields.io/github/v/release/ACLaniakea/coloros-pad-fixes?display_name=tag)](../../releases/latest)
[![License](https://img.shields.io/badge/code-GPL--3.0-blue)](#许可证与第三方材料)

项目以尽量少的 systemless 覆盖补齐移植系统的硬件适配、框架兼容和外设桥接；不替换原厂分区，也不对其他设备启用。

**下载：** [GitHub Releases](../../releases/latest)

**最新版本：** 3.2.0

**适用范围：** 仅 TB710FU + OPD2513。刷机有风险，请先备份。

## 功能概览

- 系统适配：显示与环境光、144 Hz、性能/温控拓扑、音频链路、传感器与前摄指示灯。
- 相机与 AON：相机 provider 稳定性、前摄占用仲裁、AON 注视感知运行时兼容。
- 手写笔：连接、电量、充电、设备空间、HID 与振动桥接。
- 扩展能力：Scene 调度、OPlus BSP 内核模块、CryptoEng 查找设备/互传兼容、ZUI 原厂相机移植。

## 发布内容

| 文件 | 用途 | 是否需要 |
|---|---|---|
| `FixModule-*.zip` | 主修复模块 | 必装 |
| `BaseFix-Hook-*.apk` | 主修复的 LSPosed Hook | 必装 |
| `boot-ACLaniakea-*.img`、`vendor_boot-hyperSched-stub.img` | 与 BSP/调度模块配套的内核镜像 | 使用 3.x 完整方案时必装 |
| `OplusBSP-Modules-*.zip` | OPlus BSP 内核模块 | 使用完整方案时安装 |
| `PenBridge-Module-*.zip`、`PenBridge-Hook-*.apk`、`PenHidCtl-*.apk` | 手写笔桥接套件 | 按需 |
| `SM8650Q-Scene-Scheduler-*.zip` | Scene 调度配置 | 按需 |
| `CryptoengHAL-Module-*.zip` | 查找设备与互传兼容 | 按需 |
| `LenovoPadProGT-ZUI-Camera-Port-*.zip`、`ZUI-Camera-Compat-*.apk` | ZUI 原厂相机移植 | 按需 |

不要安装仓库中的 `base-fix/module` 或 `port-tuning`：它们仅用于保留历史源码，已由 `FixModule` 取代。

## 安装

### 前置条件

- 联想小新 Pad Pro GT（TB710FU），已安装 OPD2513 ColorOS 16 移植系统；
- 已解锁 bootloader，并已配置 KernelSU 与 LSPosed（Zygisk）；
- 使用完整 3.x 方案时，`boot` 与 `vendor_boot` 必须来自同一 Release 且成对刷入。

### 推荐顺序

1. 备份当前 `boot`、`vendor_boot` 与重要数据。
2. 在 fastboot 中刷入 Release 提供的配套 `boot` 和 `vendor_boot`，然后重启系统。
3. 在 KernelSU 中安装 `FixModule`；按需安装 BSP、手写笔、Scene、CryptoEng 与 ZUI 相机模块。
4. 安装相应 APK，在 LSPosed 中启用并按 APK 的推荐作用域勾选。
5. 完整重启一次。

示例：

```bash
adb reboot bootloader
fastboot flash boot boot-ACLaniakea-6.1.128-13.img
fastboot flash vendor_boot vendor_boot-hyperSched-stub.img
fastboot reboot

adb install -r BaseFix-Hook-v3.2.0.apk
adb shell su -c 'ksud module install /sdcard/FixModule-v3.2.0.zip'
```

镜像、模块与 APK 的具体版本应来自**同一 Release**。不要混用旧版内核、模块和 Hook。

### 回退

无法开机时，优先进入 KernelSU 安全模式停用模块；仍无法恢复时回刷自己备份的 `boot` 与 `vendor_boot`。详见[救援手册](kernel-compat/刷机事故与救援手册.md)。

## 兼容性

| 项目 | 状态 |
|---|---|
| TB710FU / SM8650Q / pineapple | 支持 |
| ColorOS 16 移植包 OPD2513 | 支持 |
| 其他联想机型、其他 SoC、原生 ZUI | 不支持 |
| ZUI 相机移植 | 仅 TB710FU 原厂相机资源；按需安装 |

模块会检查设备平台；不匹配时会跳过执行。仍请不要将本项目用于其他设备或 ROM。

## 常见问题

**需要全部安装吗？**

主修复模块与 BaseFix Hook 是基础组件；手写笔、Scene、CryptoEng、ZUI 相机均按需安装。

**能否不刷内核？**

不能使用完整 3.x 方案。BSP 与调度模块依赖配套内核接口；请使用同一 Release 的成对镜像。

**ZUI 相机是否替换底层相机 HAL？**

不会。它仅部署 ZUI 相机及其应用级兼容层，不替换 CamX、camera provider 或全局设备身份。

**哪里看版本改动？**

请阅读 [Releases](../../releases) 与 [3.2.0 Release Notes](docs/3.2.0-release-notes.md)。

## 构建

构建脚本会将产物输出到 `releases/`。SDK、签名文件、厂商镜像与临时分析文件不随源码仓库分发。

```bash
export ANDROID_SDK=/path/to/android-sdk
export XPOSED_STUBS=/path/to/xposed-stubs
export ACL_KS=/path/to/signing-key.jks

bash tools/build_all.sh
```

各模块的构建入口位于对应目录的 `tools/` 下；内核兼容层及其 ABI 要求见 [kernel-compat](kernel-compat/)。

## 文档

- [3.2.0 Release Notes](docs/3.2.0-release-notes.md)
- [3.1.0 Release Notes](docs/3.1.0-release-notes.md)
- [修复汇总与技术记录](修复汇总.md)
- [内核兼容性与后续移植说明](内核兼容性与后续移植说明.md)
- [主线 Linux 与 Arch Linux 可行性说明](主线Linux内核与ArchLinux启动可行性说明.md)

## 贡献与反馈

欢迎提交 Issue，反馈时请附上机型、ROM/版本、已安装模块、复现步骤和必要日志。请勿上传序列号、账号令牌、密钥或完整厂商固件。

## 免责声明

本项目仅供个人学习、设备适配与研究。刷机和 root 操作可能导致数据丢失、无法启动或失去保修；请自行评估风险并做好备份。

## 许可证与第三方材料

本项目作者编写的代码默认采用 [GPL-3.0](LICENSE)。`kernel-compat/` 中标注的内核模块源码采用 [GPL-2.0](kernel-compat/LICENSE)。

仓库或 Release 中可能包含用于兼容研究的第三方/原厂材料，例如联想 ZUI 相机 APK、配置、资源、签名关联内容、厂商二进制接口，以及从原厂或第三方实现观察到的 CryptoEng 协议字段和样本。这些材料不因随本项目分发而获得 GPL 再授权，相关权利仍归原权利人所有；本项目不授予复制、再分发、修改、商业使用或绕过设备、账号及服务安全机制的许可。使用者应自行确认拥有合法来源并遵守适用许可与法律。

CryptoEng 的分流、HKDF、AIDL 调用和互操作代码属于本项目代码并适用 GPL-3.0；厂商密钥材料、二进制、服务数据及其引用不在该授权范围内。

## 致谢

感谢所有测试者、上游开发者和开源社区的支持。

<p align="center">
  <img src="docs/donate-wechat.png" alt="微信赞助码" width="220">
</p>
