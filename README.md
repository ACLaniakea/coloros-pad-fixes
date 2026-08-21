# ColorOS Pad Port Fixes

> 联想小新 Pro GT（TB710FU / SM8650Q / pineapple）ColorOS 16 (OPD2513) 移植系统的修复与调优集合
> KernelSU 模块 + LSPosed Hook，作者 **ACLaniakea**

![Platform](https://img.shields.io/badge/platform-SM8650Q%20%2F%20pineapple-blue)
![Android](https://img.shields.io/badge/Android-16%20(ColorOS%2016)-green)
![Version](https://img.shields.io/badge/version-2.0.1-orange)
![License](https://img.shields.io/badge/license-MIT-blue)

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
| **修复模块**（基础修复+调优合并） | KernelSU 模块 | `coloros_port_fix` | AON QNN 生命周期、环境光自适应、小布 BWV、杜比；用修正 SoC-696 六核配置重载 perf HAL 并保持运行（消除 composer 每帧 AIDL 重连风暴）；开机一次性恢复 governor（powersave→walt/schedutil）与 min/max 频点；兼容 8/12GB RAM 与 ROM 既有的 0～1×RAM ZRAM，不创建/扩容；实测后关闭 OSense 主动换出，保留内核按需 ZRAM；开机 swappiness=5，稳定后普通=10、冷后台=20、活跃与 system_server=5，64MB 水位；AON 挂载为零轮询事件驱动；恢复 ROMUpdate Provider；压制 92 个 ColorOS 启动/渲染 tag 的 DEBUG/INFO 日志 |
| **base-fix 基础修复** | LSPosed Hook | `com.aclaniakea.colorosostatsguard` | AON YUV 归一化、环境光色温桥接、BWV 唤醒链路、电池健康、CPU/GPU 信息、OStats 日志防护、移植 Thermal HAL 的 skin 状态恢复等 |
| **pen-bridge 手写笔桥接** | KernelSU 模块 | `lenovo_pen_bridge` | 原厂 CoreService BLE 连接/断开、CPS 上电、真实 ACL/GATT/Hall 状态同步、PenHidCtl HID 控制（flock 单例 + 开机监控时序） |
| **pen-bridge 手写笔桥接** | LSPosed Hook | `com.aclaniakea.lenovopenbridge` | 手写笔状态/设置/设备空间桥接，真实 GATT 断开 |
| **PenHidCtl** | priv-app | `com.aclaniakea.penhidctl` | HID 连接控制（纯服务、无桌面图标） |

详细修复清单见 [修复汇总.md](修复汇总.md)。

> `base-fix/module/` 与 `port-tuning/` 目录保留为合并前的独立源码（只读参考），当前活跃模块为 `fix-module/`（`coloros_port_fix`）。

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

# 2. 卸载旧模块（含早期独立的 base-fix / port-tuning / 笔桥接）
adb shell su -c 'ksud module uninstall coloros_port_base_fix'
adb shell su -c 'ksud module uninstall coloros_port_tuning'
adb shell su -c 'ksud module uninstall lenovo_pen_bridge'

# 3. 安装新模块（KernelSU）
adb shell su -c 'ksud module install /sdcard/FixModule-v2.0.1.zip'
adb shell su -c 'ksud module install /sdcard/PenBridge-Module-v1.1.4.zip'

# 4. 安装 Hook APK（LSPosed）
adb install --no-incremental BaseFix-Hook-v1.1.0.apk
adb install --no-incremental -r PenBridge-Hook-v1.1.4.apk

# 5. 重启
adb reboot
```

> `PenHidCtl.apk` 已作为 priv-app 随 `PenBridge-Module` 挂载，无需单独安装。`FixModule` 内置一份签名 BaseFix Hook 副本并在 zygote 前固定 LSPosed 路径，用于避免 ColorOS 冷启动时随机 `/data/app` 路径尚不可见；外部 APK 仍需非增量安装，以注册 Dolby Bridge 服务和 LSPosed 模块包。

## 推荐作用域

安装后请在 **LSPosed 管理器 → 模块 → 作用域** 检查作用域。`FixModule` 会自动保留 BaseFix 的系统框架（`android` / 数据库中的 `system`）作用域，其余应用作用域仍请手动勾选：

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
python3 fix-module/tools/build_fix.py                       # 修复模块（含调优）
python3 pen-bridge/hook/tools/build_hook_v1.py <输入APK>     # pen-bridge Hook APK
python3 pen-bridge/penhidctl/tools/build_penhid.py          # PenHidCtl APK
python3 pen-bridge/module/tools/build_root.py pen-bridge/module <输出zip>
```

> **OEM 二进制不随仓库分发**：`payload/aon-libs/`、`payload/voice/`、`odm/lib64/`、`zygisk/`、`libpeninput.so`、`pen-cps-gpio` 等为原厂固件/运行时，已通过 `.gitignore` 排除，可从设备已安装模块目录或原厂备份还原；`PenHidCtl.apk` 与 `bin/card-protocol-patcher.jar` 均由仓库内源码构建生成（后者由 `base-fix/module/tools/smali/` 经 `tools/build_patcher.py` 自动重编译，需 smali.jar）。
> 修复模块内 `payload/bin/init.qcom.post_boot.sh` 与 `payload/bin/init.kernel.post_boot.sh`
> 为高通开机脚本的一次性补丁（开机 swappiness 100→5，见 `fix-module/tools/patch_postboot.sh`），
> 由原厂 `/vendor/bin/` 脚本经该工具生成，随模块 bind 覆盖，属文本补丁而非预编译二进制。

## 目录结构

```text
.
├── README.md
├── 修复汇总.md
├── tools/
│   └── build_all.sh                  # 一键全量构建
├── base-fix/
│   ├── module/                       # 合并前独立模块源码（已并入 fix-module，保留参考）
│   └── hook/                         # LSPosed Hook 源码 + 资源 + 构建脚本
├── port-tuning/
│   └── module/                       # 合并前独立调优模块脚本（保留参考）
├── fix-module/
│   ├── module/                       # 合并后活跃修复模块（post-fs-data/service/customize…）
│   └── tools/                        # build_fix.py / patch_postboot.sh
└── pen-bridge/
    ├── module/                       # 手写笔 Root 模块 + PenHidCtl priv-app
    ├── hook/                         # 手写笔 LSPosed Hook 源码 + smali 补丁
    └── penhidctl/                    # PenHidCtl 源码
```

## 已知问题

- 小布 DSP（SoundTrigger/UIM）唤醒无开源替代方案，采用 BWV CPU 路径（识别率/延迟受 CPU 占用影响），待机耗电较高；
- 小布说话开头偶发卡顿暂未稳定复现，待修复。
- 手写笔连接状态显示与开机振动：v1.1.4 修复控制中心卡片的 Hall/OEM 充电缓存竞态，物理离座强制清除充电态，并缩短原厂 DeviceProvider 的有效边沿去重窗口。
- 查找设备功能由于缺少RPMB内的服务器公钥，无法注册本设备，但可以查看其他设备

## 致谢与免责

- 本项目仅供个人学习与调试，不构成对任何系统/固件的官方支持；
- 不包含设备序列号、签名私钥或刷入包；已编译 APK/JAR 均为构建产物（源码在仓库内，构建脚本见上文）。
- 安装前请保留系统备份，刷入风险自负。

## 开源协议

本项目采用 [MIT License](LICENSE)。仅供个人学习与设备移植研究使用；请自行评估风险，作者不对使用后果负责。
