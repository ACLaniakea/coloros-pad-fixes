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

## 后续：第一个一加内核特性已经补进去并编过

按"能用 ColorOS 逻辑就替代、替代不了才桥接"的原则，第一步挑的是
**`frame_boost`（动画/滑动卡顿的首要嫌疑）**。结果比预期干净：

- `frame_boost` 引用 8 个 vendor hook、`sched_assist` 引用 33 个，
  本内核已导出的分别是 **7/8** 和 **32/33**——只差同一个
  `android_vh_binder_proc_transaction_end`；
- 这个钩子官方 GKI 里没有（只有 `_entry` / `_finish`），
  是一加在自己的 common 内核里加的，**不在 Lenovo 的 Module.symvers 里，
  所以补上它不会与任何现有模块冲突**。

补丁一共四处，见 `kernel-compat/patches/apply_oplus_hooks.py`：

| 文件 | 改动 |
| --- | --- |
| `include/trace/hooks/binder.h` | 新增 `DECLARE_HOOK(android_vh_binder_proc_transaction_end, …)` |
| `drivers/android/binder.c` | 在 `_finish` 调用点后加一行 `trace_…_end(current, proc->tsk, …)` |
| `drivers/android/vendor_hooks.c` | `EXPORT_TRACEPOINT_SYMBOL_GPL(…_end)` |
| `abi_symbollist.raw` | 补 `__tracepoint_` / `__traceiter_` 两条，否则 TRIM 会把它裁掉 |

模块侧是三处 5.10→6.1 的 API 漂移（同一脚本处理）：
`task_running()` → `task_on_cpu()`、`p->state` → `p->__state`、
补 `#include <linux/sched/cputime.h>`（`account_group_exec_runtime` 在那里）。

打完之后：

```
内核重编 退出码 0  报错 0
vmlinux.symvers 含 binder_proc_transaction_end: 2 条
CRC mismatches : 0        PASS: vendor modules would still load.
frame_boost 编译 退出码 0  报错 0  无未定义符号
oplus_bsp_frame_boost(.ko) 746952 字节
```

**尚未上机**。下一步是把 `sched_assist` 也编出来（frame_boost 的
`sa_common.h` 依赖它），再决定装载顺序与开关，然后才谈刷机验证；
在此之前 Image 只是产物，不进 boot 分区。
