# SM8650Q 后续内核特性回移实验

本目录只用于把较新 Android/Linux 内核中有实际收益的能力，逐项回移到
TB710FU 当前可启动的 Android common 6.1 基线。它不是 6.6 内核分支，不能直接
用于刷机。

## 固定基线

- 目标源码：`android14-6.1`，提交 `5c2cea985a841939e6d074cbed2019dec0245fcd`；
- 真机基线：`6.1.128-android14-11-g5c2cea985a84-ab13606743-ACLaniakea`；
- 编译后必须通过 `kernel-compat/tools/verify_vendor_abi.py` 的 289 个 Lenovo
  vendor 模块、3615 个 `(symbol, CRC)` 门禁；
- 不改变 vendor_boot 的加载顺序，不替换 KernelSU，不自动刷写。

## 2026-09-07 的源码核对

以 Android common `android15-6.6` 为参照，6.1 基线已经拥有下列常被误认为
“6.6 新特性”的实现：

| 能力 | 6.1 状态 | 结论 |
| --- | --- | --- |
| 多代 LRU (`LRU_GEN`) | 已有 | 先读取真机配置和运行计数，不能重复移植 |
| DAMON 核心 / sysfs | 已有；`DAMON_RECLAIM`、`PADDR` 未启用 | 仅可作为独立、默认关闭的压力回收实验 |
| zsmalloc / LZ4 / Zstd | 已有；`ZSWAP` 未启用 | 只允许调参或小补丁；不能叠加到正在工作的 HybridSwap/zram |
| sched_ext | 当前基线没有完整实现 | 保留在旧 `kernel-sched-ext` 实验，禁止并入 |
| EEVDF/CFS 大改 | 6.6 改动跨越调度热路径 | 不回移；会与 WALT、lenovohyperSched 冲突 |
| 6.6 `ZSWAP_EXCLUSIVE_LOADS_DEFAULT_ON` | 参照树只有配置/参数入口，未形成可独立回移的完整收益链 | 不纳入 |

当前优先级是 **DAMON_RECLAIM 的配置与实测**，而不是增加一个常驻用户空间服务。
它在内核中只会在回收条件满足时工作；默认仍关闭，只有完成编译、ABI、启动和
内存压力对照测试后才可能启用。

真机 `HA2DXWR8` 于 2026-09-07 的只读配置检查结果：

- `CONFIG_LRU_GEN=y`、`CONFIG_DAMON=y`、`CONFIG_ZSMALLOC=m`；
- `CONFIG_DAMON_PADDR` 与 `CONFIG_DAMON_RECLAIM` 未启用；
- `CONFIG_ZSWAP=n`。这与当前标准 zram 路径相符，不是错误，也不应为了实验
  强行开启 zswap。

因此第一项候选只会在独立构建中加入
[`damon-reclaim.fragment`](damon-reclaim.fragment)，并且运行策略仍保持关闭，
直到真机压力对照通过。

## 实验流程

1. 把真机 `/proc/config.gz` 保存为 `running-config`，执行 `feature_gate.py`；
2. 仅在独立源码副本修改 Kconfig/实现，不在本目录直接改生产补丁；
3. 全量编译并通过 vendor ABI 门禁；
4. 先用临时 boot 镜像验证开机、相机、音频、AON、触控和 WLAN；
5. 对比冷启动首次解锁、长待机唤醒、多任务切换的帧时间与 PSI/zram 数据；
6. 任一项退化，保留记录并回到已验证的 boot 镜像。

`feature_gate.py` 只做只读预检，明确拒绝把 `sched_ext`、hybridswap 或完整
调度器替换伪装成低风险回移。
