# ColorOS Pad Port Fixes

> 联想小新 Pro GT（TB710FU / SM8650Q / pineapple）ColorOS 16 (OPD2513) 移植系统的修复与调优集合
> KernelSU 模块 + LSPosed Hook，统一版本 **1.0.1**，作者 **ACLaniakea**

![Platform](https://img.shields.io/badge/platform-SM8650Q%20%2F%20pineapple-blue)
![Android](https://img.shields.io/badge/Android-16%20(ColorOS%2016)-green)
![Version](https://img.shields.io/badge/version-1.0.1-orange)
![License](https://img.shields.io/badge/license-个人使用-lightgrey)

---

## 目录

- [项目组成](#项目组成)
- [兼容性](#兼容性)
- [安装](#安装)
- [推荐作用域](#推荐作用域)
- [构建](#构建)
- [目录结构](#目录结构)
- [已知问题](#已知问题)
- [致谢与免责](#致谢与免责)

---

## 项目组成

| 项目 | 类型 | 包名 / 模块 ID | 作用 |
| --- | --- | --- | --- |
| **base-fix 基础修复** | KernelSU 模块 | `coloros_port_base_fix` | AON QNN 生命周期、环境光自适应、小布 BWV 语音唤醒、录音增益、144Hz、显示色域、Tango/序列号/性能 HAL 兼容 |
| **base-fix 基础修复** | LSPosed Hook | `com.aclaniakea.colorosostatsguard` | AON YUV 归一化、环境光色温桥接、BWV 唤醒链路、电池健康、CPU/GPU 信息、OStats 日志防护、查找设备 CryptoEng 软模拟等 |
| **port-tuning 调优** | KernelSU 模块 | `coloros_port_tuning` | 恢复 ROM 基线 swappiness 继承，消除唤醒/解锁 direct-reclaim 卡顿 |
| **pen-bridge 手写笔桥接** | KernelSU 模块 | `lenovo_pen_bridge` | 原厂 CoreService BLE 连接/断开、CPS 上电、真实 ACL/GATT/Hall 状态同步、PenHidCtl HID 控制 |
| **pen-bridge 手写笔桥接** | LSPosed Hook | `com.aclaniakea.lenovopenbridge` | 手写笔状态/设置/设备空间桥接，真实 GATT 断开 |
| **PenHidCtl** | priv-app | `com.aclaniakea.penhidctl` | HID 连接控制（纯服务、无桌面图标） |

详细修复清单见 [修复汇总.md](修复汇总.md)。

## 兼容性

- 联想小新 Pro GT（TB710FU）
- 高通 SM8650Q（pineapple）
- ColorOS 16 移植版（Android 16/OPD2513）
- Root 环境：KernelSU（推荐）+ LSPosed（Zygisk）
- 前置模块：Rezygisk Lsposed

> 模块的 `post-fs-data.sh` / `service.sh` 会先校验 `ro.soc.model`/`ro.board.platform`，非目标设备自动跳过，可安全共存。

## 安装

全新安装（旧版 `com.codex.*` 与旧模块需先卸载）：

```bash
# 1. 卸载旧包
adb uninstall com.codex.colorosostatsguard
adb uninstall com.codex.lenovopenbridge
adb uninstall com.codex.penhidctl

# 2. 卸载旧模块
adb shell su -c 'ksud module uninstall coloros_port_base_fix'
adb shell su -c 'ksud module uninstall coloros_port_tuning'
adb shell su -c 'ksud module uninstall lenovo_pen_bridge'

# 3. 安装新模块（KernelSU）
adb shell su -c 'ksud module install /sdcard/BaseFix-Module-v1.0.1.zip'
adb shell su -c 'ksud module install /sdcard/PortTuning-Module-v1.0.0.zip'
adb shell su -c 'ksud module install /sdcard/PenBridge-Module-v1.0.0.zip'

# 4. 安装 Hook APK（LSPosed）
adb install BaseFix-Hook-v1.0.1.apk
adb install PenBridge-Hook-v1.0.0.apk

# 5. 重启
adb reboot
```

> `PenHidCtl.apk` 已作为 priv-app 随 `PenBridge-Module` 挂载，无需单独安装；`BaseFix` / `PenBridge` 两个 Hook APK 由 LSPosed 管理器加载。

## 推荐作用域

安装后请在 **LSPosed 管理器 → 模块 → 作用域** 手动勾选（模块不自动写入作用域）：

**base-fix（`com.aclaniakea.colorosostatsguard`）**，见 [scope.list](base-fix/hook/resources/META-INF/xposed/scope.list)：

```
android
com.android.settings
com.coloros.phonemanager
com.coloros.ocrscanner
com.inkdye.lenovopentocoloros
com.aiunit.aon
com.heytap.speechassist
com.oplus.ovoicemanager.wakeup
com.oplus.battery
com.oplus.gesture
com.coloros.findmyphone
```

**pen-bridge（`com.aclaniakea.lenovopenbridge`）**，见 [scope.list](pen-bridge/hook/source/resources/META-INF/xposed/scope.list)：

```
android
com.coloros.note
com.oplus.exsystemservice
com.oplus.healthservice
com.heytap.mydevices
com.oplus.ipemanager
com.oplus.wirelesssettings
com.oplus.screenshot
```

## 构建

本机工具链（不随仓库分发）：

- Android SDK build-tools（aapt2 / d8 / zipalign / apksigner）
- Xposed API stubs
- smali / baksmali（pen-bridge hook 由已验证 dex 重打包）
- 签名密钥（`/tmp/aclaniakea.jks`，别名 `aclaniakea`）

```bash
# 一键全量构建（产物输出到 releases/）
bash tools/build_all.sh
```

分项目构建：

```bash
python3 base-fix/hook/tools/build_integrated_hook.py        # base-fix Hook APK
python3 base-fix/module/tools/build_release.py              # base-fix 模块
python3 pen-bridge/hook/tools/build_hook_v1.py <输入APK>     # pen-bridge Hook APK
python3 pen-bridge/penhidctl/tools/build_penhid.py          # PenHidCtl APK
python3 pen-bridge/module/tools/build_root.py pen-bridge/module <输出zip>
python3 port-tuning/tools/build_tuning.py                   # 调优模块
```

> **OEM 二进制不随仓库分发**：`payload/aon-libs/`、`payload/voice/`、`odm/lib64/`、`zygisk/`、`libpeninput.so`、`pen-cps-gpio` 等为原厂固件/运行时，已通过 `.gitignore` 排除，可从设备已安装模块目录或原厂备份还原；`PenHidCtl.apk` 由源码构建生成。

## 目录结构

```text
.
├── README.md
├── 修复汇总.md
├── tools/
│   └── build_all.sh                  # 一键全量构建
├── base-fix/
│   ├── module/                       # KernelSU 模块（post-fs-data/service/customize…）
│   └── hook/                         # LSPosed Hook 源码 + 资源 + 构建脚本
├── port-tuning/
│   └── module/                       # 调优模块脚本
└── pen-bridge/
    ├── module/                       # 手写笔 Root 模块 + PenHidCtl priv-app
    ├── hook/                         # 手写笔 LSPosed Hook 源码 + smali 补丁
    └── penhidctl/                    # PenHidCtl 源码
```

## 已知问题

- 小布 DSP（SoundTrigger/UIM）唤醒无开源替代方案，采用 BWV CPU 路径（识别率/延迟受 CPU 占用影响）；
- 小布说话开头偶发卡顿暂未稳定复现。
- 手写笔还有一些连接问题尚待修复。
- 查找设备为 CryptoEng 软模拟链路（RPMB/注册状态为软件模拟，与真机不同）。

## 致谢与免责

- 本项目仅供个人学习与调试，不构成对任何系统/固件的官方支持；
- 不包含设备序列号、签名私钥、已编译 APK/JAR 或刷入包；
- 安装前请保留系统备份，刷入风险自负。
