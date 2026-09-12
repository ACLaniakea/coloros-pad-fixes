# TB710FU 主线 Linux 内核与 Arch Linux 启动可行性说明

更新时间：2026-08-24  
目标设备：Lenovo TB710FU / SM8650Q / `pineapple` 六核平板  
当前 Android 内核：`6.1.128-android14-11-g5c2cea985a84-ab13606743`

## 结论

**可以尝试，而且进入 Linux initramfs/Arch 命令行的成功概率明显高于“完整桌面可用”。**

主线 Linux 已有 SM8650 SoC 级设备树、时钟、互连、UFS、USB、DPU 等基础代码，
也已有 SM8650 MTP、QRD、HDK 和 AYANEO Pocket S2 等板级设备树；但上游没有
TB710FU 的板级 DTS。更关键的是，通用 `sm8650.dtsi` 描述标准八核 SM8650，
本机却是只呈现 CPU0-5 的 SM8650Q 六核变体。因此不能直接把
`sm8650-mtp.dtb` 或 `sm8650-qrd.dtb` 与主线 `Image` 打包后刷入。

本项目推荐依次达到四个目标：

1. **不刷写启动**：bootloader 临时加载测试镜像，内核能够输出早期日志；
2. **最小 Linux**：主线内核进入自带 BusyBox/Dropbear 的 initramfs；
3. **Arch 命令行**：UFS 或 USB 可用后 `switch_root` 进入 Arch Linux ARM；
4. **平板可用性**：显示、触摸、GPU、Wi-Fi、音频、电池、休眠逐项恢复。

前三步现实可行；第四步是长期板级主线化项目。摄像头、原厂手写笔完整功能、
144Hz/DSC 显示、深度休眠和全部音频路径很可能是最后一批完成的功能。

## 1. 为什么可行

### 1.1 CPU 架构和用户空间匹配

SM8650Q 是 AArch64 平台，Arch Linux ARM 提供通用 AArch64 rootfs。这个 rootfs
不负责让某块平板启动；它只提供 systemd、pacman、shell 和其它用户空间包，内核、
DTB、固件和启动封装仍需由我们提供。Arch Linux ARM 官方也明确把 generic AArch64
包定位为需要开发者自行完成启动支持的根文件系统。

这里所说的是 **Arch Linux ARM**，不是 Arch Linux 官方 x86_64 安装镜像。不要把
x86_64 ISO 写入分区，也不要期待通用 rootfs 自带 TB710FU 的 DTB。

### 1.2 主线已有 SM8650 基础

Linux 主线目前包含：

- `arch/arm64/boot/dts/qcom/sm8650.dtsi`；
- SM8650 MTP、QRD、HDK 和 AYANEO Pocket S2 板级 DTS；
- SM8650 的 GCC/DISPCC/GPUCC、RPMh 电源域、互连、UFS、USB、DPU/MDSS 等绑定和驱动；
- Qualcomm SCM、remoteproc、PMIC GLINK、A7xx GPU 等通用基础设施。

这意味着不必从零编写整个 SoC 支持，主要工作是新建
`sm8650q-lenovo-tb710fu.dts`，把 Lenovo 的板级电源、GPIO、面板、触摸、UFS、
USB、固件和保留内存关系翻译到主线接口。

### 1.3 现有 Android 制品提供了可靠硬件事实

当前可启动系统能够提供：

- `/sys/firmware/fdt` 中 bootloader 实际传入的扁平设备树；
- `vendor_boot`、`dtbo` 和 vendor ramdisk；
- 当前 DTBO 应用后的 `/proc/device-tree`；
- PMIC、UFS、USB、I2C/SPI、GPIO、面板和触摸节点；
- `/vendor/firmware_mnt`、`/vendor/firmware` 中设备所需固件；
- 六核实际 MPIDR、CPU capacity、cpufreq 域和中断信息。

这些只能作为“硬件事实与寄存器连接”的参考，不能把 Android vendor DTS 原样
复制进主线。Android 树中大量私有 compatible、供应商属性和驱动在主线不存在，
必须逐节点改成上游 binding，并通过 `make dtbs_check`。

## 2. 最大的设备特有风险

### 2.1 六核 SM8650Q 不是标准八核 SM8650

上游 `sm8650.dtsi` 描述八个 CPU 节点。当前 Android 实机只呈现 CPU0-5，容量为：

```text
CPU0     379
CPU1-4   867
CPU5    1024
```

cpufreq 则是 `policy0/1/3/5` 四个硬件频域。建立主线 DTS 时必须从运行中 FDT
提取每个真实 CPU 节点的 `reg`/MPIDR、电源域、频域和 OPP 关系，再决定删除或
禁用哪两个标准 SM8650 CPU 节点。**不能仅按 Linux 逻辑编号删除 upstream 的
cpu6/cpu7**，因为逻辑编号不保证等于硬件 MPIDR 排列。

错误的 CPU/电源域描述可能表现为：次级 CPU 无法上线、cpufreq probe defer、
PSCI 错误、随机冻结，甚至在某个 OPP 切换时重启。因此首个主线镜像应先只启动
boot CPU，确认日志和中断，再逐个开放次级 CPU 与 cpufreq。

### 2.2 保留内存和远程处理器不能猜

高通平台的 ADSP/CDSP/SLPI、modem、GPU、共享内存、PIL 区域和 secure heap
依赖准确的 reserved-memory。ARM64 启动规范规定：DT 中未标为保留的内存会被
Linux 当作普通 RAM 使用。若直接套 MTP 内存布局，内核可能覆盖仍由固件、
bootloader 或远程处理器使用的区域。

第一版 TB710FU DTS 必须优先移植当前 FDT 的：

- `/memory` 实际起始地址和容量；
- `/reserved-memory` 全部范围、`no-map` 和共享关系；
- ramoops/pstore（如存在）；
- SMEM、TZ、hyp、remoteproc carveout；
- bootloader 动态修补的 `/chosen` 信息。

### 2.3 面板亮起不是第一目标

主线已有 SM8650 DPU/MDSS，并不等于已支持本机面板。还需要确认：

- 面板厂商和准确型号；
- DSI lane、时序、DSC 参数、初始化命令、复位/使能 GPIO；
- panel regulator、背光和触摸供电顺序；
- 60/90/120/144Hz 模式切换；
- bootloader splash 移交和连续显存。

错误 DSI 命令存在面板无显示、花屏或异常耗电风险。最初阶段应关闭 DPU、GPU、
面板和触摸，依赖串口、USB gadget、SSH 或 pstore 取日志。先得到 headless shell，
再做显示。

### 2.4 固件可复用，驱动不能混用

主线 remoteproc、ath12k、GPU 等驱动可能需要从 Android 提取的签名固件。固件可在
本机实验中复制到 Arch `/lib/firmware/qcom/...`，但 Android 的 `.ko` 不能加载到
主线内核：内核版本、内部结构、KMI 和配置全部不同。

发布镜像时还需单独核对 Lenovo/Qualcomm 固件的再分发许可；源码仓库不应直接提交
从 Android 分区提取的闭源固件。

## 3. 推荐的双路线验证

### 路线 A：先用现有 Lenovo 6.1 内核启动 Arch

这一步不等于主线化，但非常重要。它只验证：

- boot/vendor_boot/init_boot 的解包与重打包是否正确；
- bootloader 是否接受测试镜像；
- initramfs 能否执行；
- Arch AArch64 rootfs 和 `switch_root` 是否正常；
- 根文件系统放置方案是否可靠。

推荐建立一个最小 initramfs，包含 BusyBox、`/init`、必要模块、mount、blkid、
dropbear 或 USB gadget 配置。先复用当前 Lenovo Image、DTB/DTBO 与启动参数，
让 `/init` 不进入 Android，而是挂载测试 rootfs 后 `switch_root`。

若这条路线失败，问题在启动封装、AVB、ramdisk 或 rootfs，而不是主线驱动；修好后
再换主线 Image，可大幅减少排查变量。

注意：Android 6.1 内核可能缺少 Arch systemd 所需的个别 config，第一轮可以先
进入 BusyBox shell；随后检查 namespaces、cgroup、devtmpfs、tmpfs、overlayfs、
ext4/f2fs、loop、netfilter、VT 和 systemd 需要的配置，再决定重编 Lenovo 内核。

### 路线 B：主线内核 + TB710FU DTS

建议以当时最新的稳定主线或 Qualcomm maintainer tree 建立独立分支，而不是在当前
Android common 6.1 分支上声称“主线”。初始配置可从 `defconfig` 加 Qualcomm、
initramfs、pstore、USB gadget 和调试选项开始。

首个 DTS 的范围只应包括：

1. model/compatible、chosen 和 aliases；
2. 准确 memory/reserved-memory；
3. boot CPU、GIC、timer、PSCI；
4. RPMh、基础 regulator/clock/interconnect；
5. TLMM、UART（若物理可访问）；
6. UFS 或 USB 中至少一条根文件系统通道；
7. ramoops/pstore；
8. 其余设备全部 `status = "disabled"`。

达到 initramfs shell 后再按以下顺序开放：

```text
次级 CPU/CPUFreq
  -> UFS 与稳定根文件系统
  -> USB gadget/USB host
  -> PMIC、电池和充电只读状态
  -> 面板/背光/触摸
  -> GPU
  -> Wi-Fi/蓝牙
  -> 音频
  -> 休眠/唤醒
  -> 摄像头和手写笔扩展
```

每次只开放一组节点，保留上一版可启动 Image/DTB 和日志。

## 4. Android 启动链如何承载 Linux

当前设备是 GKI/A-B 世代，启动信息不是只存在一个传统 `boot.img`。AOSP 的格式中：

- `boot` 主要承载 GKI Image；
- Android 13 及以后通常由 `init_boot` 承载 generic ramdisk；
- `vendor_boot` 承载 vendor ramdisk、vendor cmdline 和 DTB；
- `dtbo` 可能继续叠加板级设备树；
- `vbmeta`/AVB 决定镜像校验策略。

开始实验前必须用实际镜像确认 header version、ramdisk fragments、DTB 位置和
bootconfig，不能凭 Android 版本猜打包参数。所有重打包参数应由原镜像解包结果
生成并写入脚本，包括 page size、header version、cmdline 和 fragment 类型。

### 4.1 安全启动优先级

按安全性从高到低：

1. `fastboot boot test-boot.img` 临时启动，重启即回原系统；
2. bootloader 提供的专用临时/恢复启动通道；
3. 确认 A/B、当前活动槽和自动回退行为后，只写**非活动槽**；
4. 最后才考虑切换槽启动测试。

不要在尚未证明 fastboot/EDL/另一槽可恢复时覆盖当前唯一能启动的 `boot`、
`vendor_boot`、`init_boot`、`dtbo` 或 `vbmeta`。`fastboot boot` 是否被 Lenovo
bootloader 支持必须只读查询并实际测试，不能假设所有解锁设备都支持。

### 4.2 必须先备份的分区

至少保存 A/B 两槽（存在者）的：

```text
boot_a / boot_b
init_boot_a / init_boot_b
vendor_boot_a / vendor_boot_b
dtbo_a / dtbo_b
vbmeta_a / vbmeta_b
vendor_dlkm_a / vendor_dlkm_b
system_dlkm_a / system_dlkm_b
super、metadata、persist 的分区表与必要元数据
```

备份必须离机保存并记录 SHA-256。不要只把备份放在即将实验的 userdata 中。

## 5. Arch 根文件系统放在哪里

### 阶段一：initramfs 自包含

第一目标只需要 BusyBox/Dropbear 和诊断工具，直接打进 initramfs。这样不依赖 UFS、
Android 加密 userdata、USB host 或网络，是最容易判断“内核是否启动”的方法。

### 阶段二：USB/NFS root

当 DWC3 USB gadget 或 host 工作后，优先使用：

- 外接 USB 存储上的 ext4 rootfs；或
- USB gadget Ethernet + NFS root。

这两种方法不修改内部 GPT，也不碰 Android 动态分区，适合早期开发。

### 阶段三：内部 UFS 独立分区

只有在 UFS、关机、重启和掉电恢复已稳定，并且有完整 GPT/EDL 回退后，才考虑为
Arch 创建独立 ext4 分区。不要把 rootfs 文件直接放进 Android CE userdata 后期待
主线 initramfs 自动读取：该分区可能使用 metadata/file-based encryption，离开
Android vold/KeyMint 后无法按普通 f2fs 目录访问。

也不要在不了解 `super` 动态分区元数据时缩容或复用逻辑分区。相比破坏 super，
USB/NFS root 更适合作为长期开发环境。

## 6. Arch rootfs 准备原则

使用 Arch Linux ARM 通用 AArch64 rootfs，而不是使用其中自带的通用内核作为
TB710FU 内核。基本流程是：

1. 下载 rootfs、校验签名；
2. 以 root 身份用 `bsdtar -xpf` 解包，保留权限、ACL 和扩展属性；
3. 放入我们构建的 `/lib/modules/<kernelrelease>`；
4. 放入本机合法提取的 Qualcomm/Lenovo firmware；
5. 配置串口或 USB gadget 控制台、SSH、网络和 `fstab`；
6. 首次启动后初始化 Arch Linux ARM keyring。

在主线 UFS/USB 尚未稳定前，不应直接启动完整 systemd 服务集合。先用
`init=/bin/sh` 或最小自定义 init 验证 proc/sys/dev、时钟、存储和网络，再启用
systemd，能避免把驱动问题误判为用户空间问题。

## 7. 预期硬件支持难度

| 功能 | 首轮预期 | 主要工作 |
| --- | --- | --- |
| CPU0、GIC、timer | 较高 | 正确 memory、PSCI、reserved-memory |
| 六核 SMP | 中 | 从真实 FDT 还原 MPIDR/电源域，修正八核上游 DTS |
| UFS | 中到较高 | regulator、PHY、interconnect、ICE/固件和板级节点 |
| USB 2/3 | 中 | PMIC GLINK、Type-C orientation、QMP PHY、角色切换 |
| pstore/ramoops | 较高 | 找到安全保留区，便于无屏阶段取日志 |
| 面板/背光 | 中低 | 面板初始化、DSC、供电、GPIO、刷新率 |
| 触摸 | 中 | 确认控制器、总线、固件和主线驱动 |
| GPU | 中 | A7xx 驱动、GMU 固件、供电和 IOMMU |
| Wi-Fi/蓝牙 | 中低 | 芯片确认、ath12k/BT 固件、board data、校准数据 |
| 电池/充电 | 中 | PMIC GLINK 与 Lenovo 板级属性；初期只读 |
| 音频 | 低 | ADSP、LPASS、codec、SoundWire、拓扑与固件 |
| 手写笔完整功能 | 低 | HID 可能先工作，磁吸/充电/触觉依赖 Lenovo 私有链 |
| 摄像头/AON | 很低 | CAMSS、传感器、EEPROM、固件和专有算法链 |
| 深度休眠 | 低 | remoteproc、唤醒源、PMIC、firmware 全链稳定后再做 |

“低”不代表永远不能实现，只表示不应把它作为证明 Arch/主线启动成功的门槛。

## 8. 首轮开发产物建议

建议在仓库外建立独立工程，避免把 Linux 主线源码和本项目 Android 修复模块混在
一个 Git 历史中。最少包含：

```text
tb710fu-mainline/
├── manifests/                 # 上游提交、工具链、rootfs 和固件哈希
├── device-tree/
│   ├── extracted/             # 从本机导出的只读 FDT/DTBO（不公开闭源内容）
│   └── sm8650q-lenovo-tb710fu.dts
├── configs/
│   ├── tb710fu-debug.config
│   └── tb710fu-minimal.config
├── initramfs/
│   ├── init
│   └── overlay/
├── tools/
│   ├── unpack-stock-images.sh
│   ├── build-kernel.sh
│   ├── build-initramfs.sh
│   ├── pack-test-boot.sh
│   └── verify-images.sh
├── backups/SHA256SUMS         # 只记录哈希，镜像不提交 Git
└── TESTING.md
```

所有脚本必须把“构建”和“刷写”分开。默认目标只生成镜像和报告；刷写脚本要求明确
指定序列号、目标槽和镜像 SHA，不允许自动选择当前活动槽。

## 9. 分阶段验收条件

### M0：回滚链

- bootloader 已解锁；
- 能读取当前槽、分区尺寸和 boot header；
- 两槽关键镜像备份及 SHA 已离机保存；
- `fastboot boot` 或非活动槽回退已验证；
- 不依赖 Android 正常启动也能恢复。

### M1：内核生命迹象

- 无屏也能从 UART、USB 或 pstore 取得日志；
- 日志到达 `start_kernel`、设备树展开和 initramfs；
- 无 reserved-memory 覆盖、SError、watchdog 重启。

### M2：最小 Linux

- BusyBox shell 稳定运行30分钟；
- CPU、timer、内存容量正确；
- 重启和关机可控；
- 不写内部 Android 分区。

### M3：Arch 命令行

- UFS/USB/NFS root 至少一条稳定；
- `switch_root`、systemd、SSH 正常；
- pacman keyring 和时钟正常；
- 压力下无 UFS reset、IOMMU fault 或随机重启。

### M4：平板功能

- 显示/触摸/GPU/网络逐项启用并分别回归；
- 温控与电池读数真实；
- suspend/resume 连续测试；
- 最后才测试摄像头、手写笔扩展和高刷新率。

## 10. 明确不要做的事

- 不直接刷 `sm8650-mtp.dtb`/`sm8650-qrd.dtb`；
- 不把上游八核 CPU 拓扑原样用于六核 SM8650Q；
- 不把 Android vendor `.ko` 加载到主线内核；
- 不复制 SM8850/OPD2513 的 CPU、PMIC、thermal 或 reserved-memory 布局；
- 不为“先亮屏”跳过 reserved-memory、regulator 和面板电压核对；
- 不在主线尚不稳定时启用充电写控制、深度休眠或高温压力；
- 不覆盖当前活动槽作为第一次测试；
- 不在没有离机备份时修改 GPT、super 或 userdata 加密布局；
- 不把可进入 initramfs 宣称为完整 Arch 平板移植完成。

## 11. 推荐的实际起点

下一次连接平板后，最合理的第一轮工作不是立刻编译主线，而是只读采集：

1. 当前槽、分区尺寸、boot header version、AVB 状态；
2. `boot`、`init_boot`、`vendor_boot`、`dtbo` 的离机备份与哈希；
3. `/sys/firmware/fdt`、`/proc/device-tree` 和 DTBO 展开结果；
4. CPU MPIDR、PSCI、cpufreq、reserved-memory、UFS、USB、面板、触摸节点；
5. pstore/ramoops 是否可用；
6. bootloader 是否支持无刷写 `fastboot boot`。

采集完成后，先产出“Lenovo 6.1 + 自定义 initramfs”的临时测试镜像。它成功进入
shell 后，再创建主线 `sm8650q-lenovo-tb710fu.dts` 和最小配置。这个顺序总体
工作量更小，也最容易保持当前 Android 系统可随时恢复。

## 12. 上游资料

- [Linux 主线 SM8650 SoC DTSI](https://github.com/torvalds/linux/blob/master/arch/arm64/boot/dts/qcom/sm8650.dtsi)
- [Linux 主线 SM8650 板级 DTB 列表](https://github.com/torvalds/linux/blob/master/arch/arm64/boot/dts/qcom/Makefile)
- [SM8650 MTP 参考 DTS](https://github.com/torvalds/linux/blob/master/arch/arm64/boot/dts/qcom/sm8650-mtp.dts)
- [AYANEO Pocket S2 SM8650 参考 DTS](https://github.com/torvalds/linux/blob/master/arch/arm64/boot/dts/qcom/sm8650-ayaneo-pocket-s2.dts)
- [Linux ARM64 启动规范](https://docs.kernel.org/arch/arm64/booting.html)
- [Linux Device Tree 使用模型](https://docs.kernel.org/devicetree/usage-model.html)
- [AOSP boot image header](https://source.android.com/docs/core/architecture/bootloader/boot-image-header)
- [AOSP vendor_boot 格式](https://source.android.com/docs/core/architecture/partitions/vendor-boot-partitions)
- [AOSP DTB/DTBO 分区说明](https://source.android.com/docs/core/architecture/dto/partitions)
- [Arch Linux ARM Generic AArch64](https://archlinuxarm.org/platforms/armv8/generic)
- [现有 Android 内核兼容与移植说明](内核兼容性与后续移植说明.md)
