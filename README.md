# ColorOS Pad Port Fixes

> 联想小新 Pro GT（TB710FU / SM8650Q / pineapple）ColorOS 16 (OPD2513) 移植系统的修复与调优集合
> KernelSU 模块 + LSPosed Hook，作者 **ACLaniakea**

![Platform](https://img.shields.io/badge/platform-SM8650Q%20%2F%20pineapple-blue)
![Android](https://img.shields.io/badge/Android-16%20(ColorOS%2016)-green)
![Version](https://img.shields.io/badge/version-2.0.13-orange)
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
| **修复模块**（基础修复+调优合并） | KernelSU 模块 | `coloros_port_fix` | AON QNN 生命周期、环境光自适应、小布 BWV、杜比；用修正 SoC-696 六核配置重载 perf HAL 并保持运行（消除 composer 每帧 AIDL 重连风暴）；首次解锁 memcg 按 active/system_server/launcher/native-system=0、普通/冷后台=50 分层，但只迁移进程归属、不搬运已计费页面；标准 ZRAM 上提供低开销 OPlus 内存 ABI 限幅并阻止 zsmalloc 消耗显示 CMA；关闭缺少内核接口的 HybridSwap/Nirvana/HMBIRD 云控，保留预加载、LMKD、MGLRU；8GB缓存进程上限48、12GB保持原厂值；KGSL 单次回收约4MB；兼容 ROM 既有0～1×RAM ZRAM且不创建/扩容；保留原厂动态 boost、温控与人脸识别性能 |
| **SM8650Q 专用调度（Scene）** | KernelSU 模块 / Scene 外部调度 | `sm8650q_scene_scheduler` | 按实机 `1+4+1` 容量拓扑和 `policy0/1/3/5` 四频域提供省电、均衡、性能、极速模式；Scene 负责分应用切换，无额外常驻守护；保留原厂 Power/Perf HAL、温控与动态降频 |
| **OPlus 内核兼容层** | GKI 外挂模块 / 文档 | `kernel-compat/` | 基于设备精确 GKI build 13606743 编译窄接口兼容层；首个模块恢复 Horae `/proc/shell-temp` 原厂 ABI，并由 FixModule 在 post-fs-data 一次性加载。完整 HybridSwap、zram/zsmalloc 与 OPlus 调度栈不自动加载，须按内核移植文档分阶段验证 |
| **base-fix 基础修复** | LSPosed Hook | `com.aclaniakea.colorosostatsguard` | AON YUV 归一化、环境光色温桥接、BWV 唤醒链路、电池健康、CPU/GPU 信息、OStats 日志防护、移植 Thermal HAL 的 skin 状态恢复 |
| **pen-bridge 手写笔桥接** | KernelSU 模块 | `lenovo_pen_bridge` | 原厂 CoreService BLE 连接/断开、CPS 上电、真实 ACL/GATT/Hall 状态同步、PenHidCtl HID 控制；内置同签名 Hook 副本供 LSPosed 冷启动稳定读取 |
| **pen-bridge 手写笔桥接** | LSPosed Hook | `com.aclaniakea.lenovopenbridge` | 手写笔状态/设置/设备空间桥接，书写触觉直连 GATT、版本字段跨进程同步与真实 GATT 断开 |
| **PenHidCtl** | priv-app | `com.aclaniakea.penhidctl` | HID 连接控制（纯服务、无桌面图标） |

详细修复清单见 [修复汇总.md](修复汇总.md)。内核层边界与后续路线见
[内核兼容性与后续移植说明.md](内核兼容性与后续移植说明.md)；尝试启动主线内核与 Arch Linux 前，请先阅读
[主线Linux内核与ArchLinux启动可行性说明.md](主线Linux内核与ArchLinux启动可行性说明.md)。

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
adb shell su -c 'ksud module install /sdcard/FixModule-v2.0.13.zip'
adb shell su -c 'ksud module install /sdcard/PenBridge-Module-v1.1.20.zip'
adb shell su -c 'ksud module install /sdcard/SM8650Q-Scene-Scheduler-v1.0.5.zip'

# 4. 安装 Hook APK（LSPosed）
adb install --no-incremental BaseFix-Hook-v1.1.12.apk
adb install --no-incremental -r PenBridge-Hook-v1.1.20.apk

# 5. 重启
adb reboot
```

> `PenHidCtl.apk` 已作为 priv-app 随 `PenBridge-Module` 挂载，无需单独安装。`FixModule` 内置一份签名 BaseFix Hook 副本并在 zygote 前固定 LSPosed 路径，用于避免 ColorOS 冷启动时随机 `/data/app` 路径尚不可见；外部 APK 仍需非增量安装，以注册 Dolby Bridge 服务和 LSPosed 模块包。

## 推荐作用域

`FixModule` 会把 BaseFix 内置 `scope.list` 自动补齐到 LSPosed 白名单，包括系统框架（APK 中的 `android` / 数据库中的 `system`）与下列应用。同步只使用 `INSERT OR IGNORE` 增补必需作用域，不会删除用户额外勾选的包：

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
python3 scheduler-module/tools/build_scheduler.py           # SM8650Q 专用 Scene 调度
python3 pen-bridge/hook/tools/build_hook_v1.py <输入APK>     # pen-bridge Hook APK
python3 pen-bridge/penhidctl/tools/build_penhid.py          # PenHidCtl APK
python3 pen-bridge/module/tools/build_root.py pen-bridge/module <输出zip>
```

> **OEM 二进制不随仓库分发**：`payload/aon-libs/`、`payload/voice/`、`odm/lib64/`、`zygisk/`、`libpeninput.so`、`pen-cps-gpio` 等为原厂固件/运行时，已通过 `.gitignore` 排除，可从设备已安装模块目录或原厂备份还原；`PenHidCtl.apk` 与 `bin/card-protocol-patcher.jar` 均由仓库内源码构建生成（后者由 `base-fix/module/tools/smali/` 经 `tools/build_patcher.py` 自动重编译，需 smali.jar）。
> 修复模块内 `payload/bin/init.qcom.post_boot.sh` 与 `payload/bin/init.kernel.post_boot.sh`
> 为高通开机脚本的一次性补丁（开机 swappiness 100→50，见 `fix-module/tools/patch_postboot.sh`），
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
├── scheduler-module/
│   ├── module/                       # SM8650Q 专用 Scene/UPerf 标准调度接口
│   └── tools/                        # build_scheduler.py
├── kernel-compat/
│   └── oplus_compat/                 # 精确 GKI 外挂兼容模块与构建说明
└── pen-bridge/
    ├── module/                       # 手写笔 Root 模块 + PenHidCtl priv-app
    ├── hook/                         # 手写笔 LSPosed Hook 源码 + smali 补丁
    └── penhidctl/                    # PenHidCtl 源码
```

## 已知问题

- 小布 DSP（SoundTrigger/UIM）唤醒无开源替代方案，采用 BWV CPU 路径（识别率/延迟受 CPU 占用影响），待机耗电较高；
- 小布说话开头偶发卡顿暂未稳定复现，待修复。
- 手写笔连接状态与振动：v1.1.20 保留 BLE/电量/充电/触觉事件链，将 Hall、蓝牙与缓存轮询降为事件优先的低频兜底，并移除亮屏时 Root/Hook 双重 Hall 回放；Provider 负责隔离 system_server，振动写入复用原厂 IPeManager 已连接的 GATT 会话，避免第二客户端抢占与首笔重启。
- 查找设备功能由于缺少RPMB内的服务器公钥，无法注册本设备，但可以查看其他设备

## 致谢与免责

- 本项目仅供个人学习与调试，不构成对任何系统/固件的官方支持；
- 不包含设备序列号、签名私钥或刷入包；已编译 APK/JAR 均为构建产物（源码在仓库内，构建脚本见上文）。
- 安装前请保留系统备份，刷入风险自负。

## 开源协议

本项目采用 [MIT License](LICENSE)。仅供个人学习与设备移植研究使用；请自行评估风险，作者不对使用后果负责。
