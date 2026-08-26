# 自建 GKI 6.1.128 与 vendor ABI 门禁结果

## 结论（2026-08-26 实测）

用与官方一致的源码、工具链和 **未经任何削减的 .config** 重编 GKI，
289 个 Lenovo vendor 模块所需的 3615 个 (符号, CRC) 对里，
**0 处不匹配**——自建 Image 不会让任何一个 vendor 模块拒载。

```
编译目标数 3033    real 5m28s    报错 0
modules            : 289
required (sym,crc) : 3615
kernel-provided    : 2319
inter-module       : 1296  (由 vendor 树自身提供)
CRC mismatches     : 0
PASS: every kernel-provided symbol matches; vendor modules would still load.
```

抽样比对（官方 Module.symvers vs 自建 vmlinux.symvers）：

| 符号 | 官方 | 自建 |
| --- | --- | --- |
| `module_layout` | 0xea759d7f | 0xea759d7f |
| `system_state` | 0xf7370f56 | 0xf7370f56 |
| `__alloc_pages` | 0x7870d49b | 0x7870d49b |
| `__arch_clear_user` | 0x4d296494 | 0x4d296494 |
| `thermal_zone_device_register` | 0x9b7989e7 | 0x9b7989e7 |

## 更正：先前"1598 处不匹配"的记录作废

那个数字来自一次**带偏离的编译**：因为主机缺 pahole、且 resolve_btfids 编不过，
当时关掉了 `CONFIG_TRIM_UNUSED_KSYMS` 和 `CONFIG_DEBUG_INFO_BTF`。
本项目一度记为"TRIM 和 BTF 都单独试过、CRC 没动"——**这句话是错的**：
日志显示那两次所谓的"验证"分别只编了 2 个和 4 个目标，
Module.symvers 根本没有重新生成，比对的是上一次的陈旧产物。

真实关系是：**这两个开关就是全部原因**。两者恢复后不匹配数从 1598 直接归零。
原因也很直接——`TRIM_UNUSED_KSYMS` 决定哪些符号进入导出集合，
`DEBUG_INFO_BTF` 参与 modversion 计算链路，任何一个关掉，
导出符号集合和 CRC 都不再是官方那一套。

**教训写进脚本**：`tools/build_gki_ref.sh` 在跑门禁前会先断言编译目标数是四位数，
不足四位直接退出——增量编译的陈旧 symvers 不允许再被当成结论。

## 复现方式

```sh
kernel-compat/tools/build_gki_ref.sh
```

要素：

- 源码：AOSP common `android14-6.1` @ `5c2cea985a841939e6d074cbed2019dec0245fcd`
  （即 `6.1.128-android14-11-g5c2cea985a84-ab13606743` 对应提交）；
- 工具链：Android Clang `r487747c`，版本字符串与真机 `/proc/version` 逐字节一致；
- `.config`：真机 `/proc/config.gz` 直接 `olddefconfig`，**不做任何 -d 削减**；
- 主机需 `bc` 与 `pahole`；
- 唯一的源码改动是 `tools/lib/bpf/libbpf.c` 的一处 `(char *)` 强转，
  用于让 `resolve_btfids` 这个**主机工具**在新版编译器下通过 `-Werror`，
  不进入内核产物。

## 这一步解锁了什么

自建内核与现有 vendor 模块 ABI 兼容既已成立，下列改动就可以从用户层搬回内核层：

1. 移除虚假温度传感器 / 修正 thermal zone，不再靠 `payload/` bind mount 顶替
   `/vendor/etc/thermal-engine_*.conf`；
2. 6 核簇拓扑直接进 DT，不再靠 `targetconfig.xml` 覆盖；
3. OPlus 的 `frame_boost` / `sched_assist` 走真正的 `android_vh_*` 钩子，
   而不是用户态轮询。

下一步：先核对 `frame_boost` / `sched_assist` 所需的 `android_vh_*` 是否在
本内核的导出集合内，再决定这两个模块是移植还是重写。
