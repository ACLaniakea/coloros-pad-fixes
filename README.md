# ColorOS Pad Port Fixes

联想小新 Pad Pro GT（**TB710FU** / SM8650Q / pineapple）跑 ColorOS 16 移植包（OPD2513）的一套适配修复。

![Platform](https://img.shields.io/badge/platform-SM8650Q%20%2F%20pineapple-blue)
![Android](https://img.shields.io/badge/Android-16%20(ColorOS%2016)-green)
![Version](https://img.shields.io/badge/version-3.0.0-orange)
![License](https://img.shields.io/badge/license-MIT-blue)

移植包刷上去能开机，但不少东西是瘸的：温控曲线用错了传感器、内存扩展档位对不上、一加那套内核调度栈加载不起来、手写笔和 AON 注视感知没人接。这个项目就是把这些一条条接回去。

**下载 → [Releases](../../releases/latest)**。安装步骤在下面第三节，也可以直接看 release 说明。

---

## 一、发布内容

| 文件 | 是什么 | 必装 |
|---|---|---|
| `FixModule.zip` | **主修复模块**。六核性能与温控曲线、内存与交换分层、调度基线、显存回收、音频与传感器链路 | ✅ |
| `BaseFix-Hook.apk` | LSPosed 模块。框架层修复：AON 注视感知、杜比音效、环境光、144Hz、设备标识 | ✅ |
| `boot-ACLaniakea-*.img` | 自建 GKI 内核（含 KernelSU v3.2.5 LKM） | 装内核模块才要 |
| `vendor_boot-hyperSched-stub.img` | 配套 vendor_boot，**必须与上面成对刷** | 同上 |
| `OplusBSP-Modules.zip` | 30 个一加 BSP 内核模块：调度增强、压缩内存、后台冻结 | 依赖上面两个 img |
| `PenBridge-Module.zip` + `PenBridge-Hook.apk` + `PenHidCtl.apk` | 手写笔桥接三件套 | 用笔才装 |
| `SM8650Q-Scene-Scheduler.zip` | 给 Scene 提供四档调度配置 | 用 Scene 才装 |

---

## 二、前置条件

- 机型 **TB710FU**，已刷 ColorOS 16 移植包（OPD2513）
- 已解锁 bootloader
- **KernelSU**（本机的 root 是修补 `init_boot` 得来的）
- **LSPosed**（Zygisk 版）

模块的 `post-fs-data.sh` / `service.sh` 开头会校验 `ro.soc.model` 与 `ro.board.platform`，非目标设备整体跳过，装错机器不会生效也不会搞坏。

---

## 三、安装

顺序是：**内核 → Root 模块 → LSPosed**。

### 3.1 刷内核（只有要装 `OplusBSP-Modules` 才需要）

只想要基础修复的**跳过这一步**，直接看 3.2。不刷内核就别装 `OplusBSP-Modules`。

先备份：

```bash
adb shell su -c 'dd if=/dev/block/by-name/boot_a of=/sdcard/boot_a.bak'
adb shell su -c 'dd if=/dev/block/by-name/vendor_boot_a of=/sdcard/vendor_boot_a.bak'
```

两个分区一起刷：

```bash
adb reboot bootloader
fastboot flash boot         boot-ACLaniakea-6.1.128-13.img
fastboot flash vendor_boot  vendor_boot-hyperSched-stub.img
fastboot reboot
```

**为什么必须成对**：

- **只刷 boot** —— 联想真身的 `lenovohyperSched.ko` 还在，它和一加的 `sched_assist` 抢同一批 restricted vendor hook。那套钩子是**单注册者**语义（`DO_RESTRICTED_HOOK` 只调 `funcs[0]`），第二个注册者静默失效，一加整套调度栈白装。
- **只刷 vendor_boot** —— 里面那个空壳模块没有对应的内核符号表，`sched_walt` 拒载，开不了机。

刷完确认：

```bash
adb shell uname -r     # 应带 -ACLaniakea 后缀
```

### 3.2 装 Root 模块

在 KernelSU 里依次刷入，**全部装完再重启**：

1. `FixModule.zip` ← 必装
2. `OplusBSP-Modules.zip` ← 刷了内核才装
3. `PenBridge-Module.zip` ← 用笔才装
4. `SM8650Q-Scene-Scheduler.zip` ← 用 Scene 才装

命令行等价写法：

```bash
adb shell su -c 'ksud module install /sdcard/FixModule-v3.0.0.zip'
```

### 3.3 装 LSPosed 模块

```bash
adb install -r BaseFix-Hook-v3.0.0.apk
adb install -r PenBridge-Hook-v3.0.0.apk   # 用笔才装
adb install -r PenHidCtl-v3.0.0.apk        # 用笔才装
```

然后在 LSPosed 里**启用模块并勾选作用域**——勾错等于没装。`FixModule` 会用 `INSERT OR IGNORE` 把必需作用域补进 LSPosed 白名单，不会删掉你自己加的。

**Pro GT 基础修复**（`com.aclaniakea.colorosostatsguard`）：

```
android                          com.oplus.battery
com.android.settings             com.oplus.gesture
com.coloros.phonemanager         com.aiunit.aon
com.coloros.ocrscanner           com.coloros.findmyphone
com.heytap.speechassist          com.oplus.ovoicemanager.wakeup
com.inkdye.lenovopentocoloros
```

**联想手写笔桥接**（`com.aclaniakea.lenovopenbridge`）：

```
android                          com.oplus.ipemanager
com.coloros.note                 com.oplus.wirelesssettings
com.oplus.exsystemservice        com.oplus.screenshot
com.oplus.healthservice          com.heytap.mydevices
```

勾完**重启**。

---

## 四、开不了机怎么办

刷内核有风险，按这个顺序来：

1. **KernelSU 安全模式** —— 开机时按住**音量减**，禁用所有模块。能进系统就说明是某个模块的问题。
2. **回刷备份**：

   ```bash
   fastboot flash boot         boot_a.bak
   fastboot flash vendor_boot  vendor_boot_a.bak
   ```

3. **卡在 fastboot 出不来** —— 这台机器的救援 fastboot 会把 `partition-size` 报成 0、拒收大文件，看着像砖。真正管用的是：

   ```bash
   fastboot set_active a
   ```

   然后**拔掉数据线**再重启。

4. 以上都不行走 **9008 EDL**。完整步骤见 [`kernel-compat/刷机事故与救援手册.md`](kernel-compat/刷机事故与救援手册.md)，那份文档记了两次真实救援的全过程，包括哪些操作其实没用。

---

## 五、只想要基础修复

不刷内核也能用绝大部分东西，装这两个就够：

- `FixModule.zip`
- `BaseFix-Hook.apk`

温控、内存分层、音频、传感器都在里面。少的是一加那套内核级调度增强与压缩内存。

---

## 六、构建

工具链不随仓库分发，用环境变量指过去：

```bash
export PAD_KIT=/path/to/kit          # 下面几件的根目录
export ANDROID_SDK=$PAD_KIT/tools/sdk        # build-tools(aapt2/d8/zipalign/apksigner) + platforms
export XPOSED_STUBS=$PAD_KIT/tools/dex/xposed-api-82.jar
export ACL_R8_JAR=$PAD_KIT/tools/dex/r8.jar
export SMALI_JAR=$PAD_KIT/tools/dex/smali-fat.jar   # 注意是 fat 版，普通 smali.jar 没有主清单
export ACL_KS=$PAD_KIT/keys/aclaniakea.jks

bash tools/build_all.sh              # 产物输出到 releases/
```

分项构建：

```bash
python3 base-fix/hook/tools/build_integrated_hook.py     # BaseFix Hook APK
python3 fix-module/tools/build_fix.py                    # 主修复模块
python3 oplus-bsp-module/tools/build_oplus_bsp.py        # 一加 BSP ko 打包
python3 scheduler-module/tools/build_scheduler.py        # Scene 调度
python3 pen-bridge/hook/tools/build_hook_source.py       # 手写笔 Hook（从 Java 源码编）
python3 pen-bridge/penhidctl/tools/build_penhid.py       # PenHidCtl
python3 pen-bridge/module/tools/build_root.py pen-bridge/module <输出zip>
```

**不入库的东西**：原厂固件与运行时（`payload/aon-libs/`、`payload/voice/`、`odm/lib64/`、`zygisk/`、`libpeninput.so`、`pen-cps-gpio`）按 `.gitignore` 排除，可从设备上已装模块目录或原厂备份还原。`oplus-bsp-module/ko/` 那 30 个 `.ko` 也不入库，由 `kernel-compat/` 下的源码与补丁重建，成品在 release 的 zip 里。

---

## 七、目录结构

```text
.
├── fix-module/          主修复模块（活跃）
├── base-fix/
│   ├── hook/            BaseFix LSPosed Hook 源码（活跃）
│   └── module/          合并前的独立模块，只读参考
├── oplus-bsp-module/    30 个一加 BSP ko + 依赖序加载器
├── pen-bridge/          手写笔：Root 模块 / Hook / PenHidCtl
├── scheduler-module/    Scene 四档调度桥接
├── kernel-compat/       自建内核、ABI 门禁、模块移植、救援手册
├── port-tuning/         早期调优模块，只读参考
├── experimental/        试验性内容
└── tools/build_all.sh   一键全量构建
```

---

## 八、文档

| 文档 | 内容 |
|---|---|
| [修复汇总.md](修复汇总.md) | 逐条修复记录与实测数据，也包括走错的路和被推翻的结论 |
| [kernel-compat/刷机事故与救援手册.md](kernel-compat/刷机事故与救援手册.md) | 两次真实救援全过程，含 9008 EDL 单分区恢复 |
| [kernel-compat/一加模块移植进度.md](kernel-compat/一加模块移植进度.md) | 一加 BSP 模块的移植与 hyperSched 空壳做法 |
| [kernel-compat/自建内核ABI门禁结果.md](kernel-compat/自建内核ABI门禁结果.md) | 自建 GKI 与 vendor 模块的 ABI 校验 |
| [内核兼容性与后续移植说明.md](内核兼容性与后续移植说明.md) | 内核层边界与后续路线 |
| [主线Linux内核与ArchLinux启动可行性说明.md](主线Linux内核与ArchLinux启动可行性说明.md) | 想跑主线内核或 Arch 的先读这个 |

---

## 九、已知问题

**小布 DSP 唤醒：不可行，已停用。** 最小改动实验（只换 PAL 的 `vendor_uuid`，联想的图和 profile 全不动）证明 PAL 能完整加载模型（`LoadSoundModel status 0`），但联想 ADSP 里的 `capi_aispeech_wakeup` 解不了小布的 BreenoSpeech 模型，20 秒崩 15 次。除非拿到「小布小布」的 aispeech 格式模型，这条路无解。相关入口已隐藏。

**查找设备：只能看，不能注册。** RPMB 里缺服务器公钥，本机无法注册，但能查看其他设备。

**AON 注视感知有额外开销。** `com.aiunit.aon` 的 CAMERA appop 卡在 `foreground` 模式，而它是后台绑定服务，会周期性开关前置摄像头。功能正常，代价是一部分 CPU。

---

## 十、免责

仅供个人学习与设备移植研究。不含设备序列号、签名私钥或刷机包；已编译的 APK/ZIP 都是构建产物，源码和构建脚本都在仓库里。

**刷机前请备份，风险自负。**

## 协议

[MIT License](LICENSE)
