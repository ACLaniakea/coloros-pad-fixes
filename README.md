# ColorOS Pad Port Fixes

联想小新 Pad Pro GT（**TB710FU** / SM8650Q / pineapple）跑 ColorOS 16 移植包（OPD2513）的一套适配修复。

![Platform](https://img.shields.io/badge/platform-SM8650Q%20%2F%20pineapple-blue)
![Android](https://img.shields.io/badge/Android-16%20(ColorOS%2016)-green)
![Version](https://img.shields.io/badge/version-3.0.0-orange)
![License](https://img.shields.io/badge/license-MIT-blue)

移植包刷上去能开机，但不少东西是瘸的：温控曲线用错了传感器、内存扩展档位对不上、一加那套内核调度栈加载不起来、手写笔和 AON 注视感知没人接。这个项目就是把这些一条条接回去。

**下载 → [Releases](../../releases/latest)**。安装步骤在下面第三节，也可以直接看 release 说明。

> ### ⚠️ 3.0.0 不能单独安装
>
> **必须先刷 `boot` 和 `vendor_boot` 两个分区，再装模块。** 这两个 img 要成对刷，
> 缺一不可。跳过这一步直接装模块，轻则功能不生效，重则 **kernel panic 开不了机**，
> 还可能出现各种无法预知的问题 —— 模块里的内核态部分（外挂 ko、调度标记 ABI、
> zram 写回）都依赖这个内核提供的符号与接口。
>
> **不想刷分区的话，用 [2.0.15](../../releases/tag/v2.0.15)** —— 那是最后一个纯模块、
> 不碰任何分区的版本。代价是它**不包含 3.0.0 及之后的所有修复**（手电亮度分档、
> 长待机解锁卡顿、内存扩展档位、通信共享等等），也不会再更新。

---

## 一、发布内容

| 文件 | 是什么 | 必装 |
|---|---|---|
| `FixModule.zip` | **主修复模块**。六核性能与温控曲线、内存与交换分层、调度基线、显存回收、音频与传感器链路 | ✅ |
| `BaseFix-Hook.apk` | LSPosed 模块。框架层修复：AON 注视感知、杜比音效、环境光、144Hz、设备标识 | ✅ |
| `boot-ACLaniakea-*.img` | 自建 GKI 内核 6.1.128，配置对齐 KernelSU LKM 的加载条件 | ✅ **先刷** |
| `vendor_boot-hyperSched-stub.img` | 配套 vendor_boot，**必须与上面成对刷** | ✅ **先刷** |
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

顺序是：**内核 → Root 模块 → LSPosed**，这个顺序不能颠倒也不能跳步。

### 3.1 刷内核（3.0.0 起是必做的第一步）

`boot` 和 `vendor_boot` 必须**成对刷**：vendor_boot 里带的是配套的
`lenovohyperSched.ko` 桩，只刷一个会在开机早期卡住或直接 panic。

> **刷 `boot` 不会给你 root。** 本机的 root 来自修补 `init_boot` —— KernelSU 是
> LKM，那个 `kernelsu.ko` 躺在 `init_boot` 的 ramdisk 里，`boot` 里没有它
> （实测 `boot` 分区搜不到 `kernelsu` 字样，`init_boot` 搜得到）。这里的 `boot`
> 只提供内核本体，配置是按 KernelSU LKM 的加载条件对齐的。所以刷之前
> **`init_boot` 必须已经修补好**，顺序别反。

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

校验这两个 img（刷之前对一下）：

```
boot-ACLaniakea-6.1.128-13.img    34844672 字节   md5 d049b5cc3d790a3c9d96586b19f7e88c
vendor_boot-hyperSched-stub.img   12402688 字节   md5 42436f9b56fa2ba92216fd1b23b5fdf3
```

这两份是开发机上**实际在跑**的那一对——用 `dd` 从 `boot_a` / `vendor_boot_a` 读回来逐字节比对过，
不是"目录里最新的那个文件"。

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

## 五、不想刷分区怎么办

用 **[2.0.15](../../releases/tag/v2.0.15)**，那是最后一个纯模块、不碰任何分区的版本，
装 `FixModule` + `BaseFix-Hook` 即可，温控、内存分层、音频、传感器都在里面。

代价说清楚：

- 少的是一加那套内核级调度增强与压缩内存（`OplusBSP-Modules` 依赖自建内核）
- **不包含 3.0.0 及之后的任何修复**，包括手电亮度分档、长待机解锁卡顿、
  内存扩展档位、通信共享等等
- 这条线不再更新，后续修复只会进需要刷分区的版本

3.0.0 之后模块与内核是绑在一起的：模块里的内核态部分要用到自建内核提供的符号
与接口，**把 3.0.0 的模块装在原厂内核上不是"少几个功能"，是会出问题**。

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

修完之后仍然存在、且已经查过的几件事：

### 手电亮度只有 42–78 这一段

四档亮度能用了（42/54/66/78），但跨度比硬件能力窄。原因是点灯只能由相机 HAL 做，
而 camx 点灯时一定先把电流写成它自己的 `torchCurrent=57`，我们只能在这之后覆盖。
最低档若取到硬件能做到的 13，每次开灯就会先亮 57 再掉下去，低档位下是明显的爆闪。
42 是"四档分得开"和"开灯不闪"之间量出来的折中，细节见 `修复汇总.md`。

### 开机后第一次解锁卡顿

**原因未明。** 只在每次开机后的第一次解锁出现，之后不复现。查过内存回收、
调度基线、模块开机写入时序，都没能对上，暂时没有结论。

### 查找设备：能找别人，注册不了自己

注册用的公钥在 **TEE** 里，读不出来，也就没法发出本机的注册指令。
所以本设备无法注册到「查找设备」，但**查看其他设备是正常的**。

### 小布语音唤醒：已放弃

两条路都走过：

- **DSP 路径不可修**。最小改动实验（只换 PAL 的 `vendor_uuid`，联想的图和
  profile 全不动）证明 PAL 能完整加载模型（`LoadSoundModel status 0`），
  但联想 ADSP 里的 `capi_aispeech_wakeup` 解不了小布的 BreenoSpeech 模型，
  20 秒崩 15 次。除非拿到「小布小布」的 aispeech 格式模型，无解。
- **BWV（CPU）路径效率太低**。能跑，但常驻要吃掉约三分之一个核，
  而且结果只在检测窗口结束时才出，唤醒比走 DSP 的手机慢一拍以上，
  多设备协同里基本抢不过手机。收益撑不起这个代价。

**入口已隐藏并停用。**

> 另外记一笔：AON 注视感知（`com.aiunit.aon`）的 CAMERA appop 卡在 `foreground`
> 模式，而它是后台绑定服务，会周期性开关前置摄像头。功能是正常的，代价是一部分
> CPU——这是权衡不是故障，想省这部分可以在系统设置里关掉注视感知。

## 十、致谢

- 酷安 **@徘徊于斗牛间**、**@长卿i** —— 测试支持
- 特别致谢 酷安 **@Tsukiko _Hakura** —— API 支持

## 十一、免责

仅供个人学习与设备移植研究。不含设备序列号、签名私钥或刷机包；已编译的 APK/ZIP 都是构建产物，源码和构建脚本都在仓库里。

**刷机前请备份，风险自负。**

## 协议

[MIT License](LICENSE)
