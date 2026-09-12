# 文档索引

联想小新 Pad Pro GT（TB710FU / OPD2513，SM8650Q）ColorOS 16 移植修复项目的全部文档。
安装步骤与模块说明在仓库根目录的 [README](../README.md)。

## 发布说明

每个版本"改了什么、怎么验证、还剩什么问题"。深度定位过程另见排查记录。

| 版本 | 主题 |
| --- | --- |
| [4.0.4](release-notes/4.0.4.md) | 接回被移植弄断的 AOT 与内存链路 |
| [4.0.3](release-notes/4.0.3.md) | 调度链三项与模块清单清理 |
| [4.0.2](release-notes/4.0.2.md) | 相机身份层去写死 UID，新增 8GB 内存档 |
| [4.0.1](release-notes/4.0.1.md) | 统一版本号，重写模块与应用简介 |
| [4.0.0](release-notes/4.0.0.md) | 自建内核基线 |
| [3.2.1](release-notes/3.2.1.md) | CryptoEng 并入 FixModule |
| [3.2.0](release-notes/3.2.0.md) | AON 相机与模块统一 |
| [3.1.0](release-notes/3.1.0.md) | 查找设备、互传联系人、前摄指示灯 |

## 排查记录

完整的定位过程、实测数据，以及**被证伪或排除的假设**——后者同样重要，免得重复排查。

| 文档 | 内容 |
| --- | --- |
| [4.0.4 内存与 AOT 排查](investigations/4.0.4-内存与AOT排查.md) | system_server 解释执行、碎片度上报缺失、osvelte 命名、hybridswap 熄屏闸门；以及排除掉的四条 |
| [4.0.3 调度链与 SELinux 排查](investigations/4.0.3-调度链与SELinux排查.md) | sched_assist 负载均衡未触发、UX→WALT 翻译、帧组提频、per-CPU 预留 |
| [AON 相机 DSP 修复过程](investigations/AON-相机-DSP修复过程.md) | 常亮感知相机与 DSP 链路 |
| [扬声器破音排查](investigations/扬声器破音排查.md) | 音频失真定位 |
| [桌面旋转位移动画消失](investigations/桌面旋转位移动画消失.md) | 桌面动画缺失 |
| [CryptoengHAL 软件实现分析](investigations/CryptoengHAL软件实现分析.md) | CryptoEng HAL 的软件替代 |
| [CryptoEng 10003 分流代理说明](investigations/CryptoEng-10003分流代理说明.md) | 分流代理设计 |
| [查找设备 init 公钥接入](investigations/查找设备-init公钥接入.md) | 查找设备的公钥注入 |

## 参考资料

长期有效、跨版本的背景材料。

| 文档 | 内容 |
| --- | --- |
| [修复汇总](reference/修复汇总.md) | 项目全部修复项的总账，按子系统组织 |
| [内核兼容性与后续移植说明](reference/内核兼容性与后续移植说明.md) | GKI 基线、vendor ABI 门禁、可移植性边界 |
| [主线 Linux 内核与 ArchLinux 启动可行性说明](reference/主线Linux内核与ArchLinux启动可行性说明.md) | 主线内核可行性评估 |
| [LSPosed 依赖审计与 KernelSU 收敛方案](reference/LSPosed-依赖审计与KernelSU收敛方案.md) | 减少对 LSPosed 的依赖 |
| [刷机事故与救援手册](../kernel-compat/刷机事故与救援手册.md) | 变砖时怎么救 |
| [一加模块移植进度](../kernel-compat/一加模块移植进度.md) | 各 OPlus 内核模块能否移植的逐项结论 |
| [自建内核 ABI 门禁结果](../kernel-compat/自建内核ABI门禁结果.md) | 289 个 vendor 模块、3615 个符号的门禁记录 |

## 目录约定

```
docs/
  release-notes/    每版一份，简短：改了什么、怎么验证、已知问题
  investigations/   专题排查记录，长文，含被排除的假设
  reference/        跨版本的长期参考资料
  assets/           文档用到的图片与密钥等附件
```
