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

---

## 第一次真机刷机：一次失败、一次成功，真凶是 MODULE_SIG_PROTECT

**结果：自建内核已经在真机上正常开机**（40 秒到 `sys.boot_completed=1`，
457 个模块，与原厂同数；WiFi 已连、蓝牙 ON、audioserver 正常、
手写笔三个 input 设备都在、KernelSU 与 19 个模块照旧）。

### 第一次为什么起不来

现象：内核起来了，adb 和 root 都能用，但 Android 卡在开机第二屏，
约 355 秒后 ColorOS 自己的开机看门狗触发 `sysrq triggered crash` 重启
（`sys.boot.reason=kernel_panic,sysrq_trigwarmd_crash`）。

关键数据是模块数：**自建 398 个，原厂 457 个**，少的 59 个里有
`cfg80211`、`mac80211`、`qca_cld3_kiwi_v2`、`bluetooth`、`btpower`、
`bt_fm_slim/swr`——WiFi 与蓝牙整条栈都没起来，ColorOS 等 HAL 等到超时。

原因在 `kernel/module/main.c:1281`：

```c
if (!mod->sig_ok && gki_is_module_protected_export(kernel_symbol_name(s))) {
        pr_err("%s: exports protected symbol %s\n", ...);
        return -EACCES;
}
```

`CONFIG_MODULE_SIG_PROTECT=y` 时，**没有用本内核签名密钥签过的模块，
不允许导出"受保护"符号**。我们自己编内核会现生成一把
`certs/signing_key.pem`，于是原厂那 457 个模块在我们内核眼里
全部 `sig_ok=false`；其中 `net/bluetooth/bluetooth.ko` 这类在
`android/gki_aarch64_protected_modules` 名单里的，一律 -EACCES 拒载，
再连累所有依赖它们的模块。

**修法**：`CONFIG_MODULE_SIG_PROTECT=n`。这个开关的用途是防止别人替换
GKI 模块，而我们恰恰一个 GKI 模块都没换——关掉它只是不再检查，
不改变任何符号、CRC 或结构体布局。

### 一条走错的岔路，记下来免得重犯

看到"59 个模块没加载"时，我第一反应是 `TRIM_UNUSED_KSYMS` 把符号裁多了，
还真去比了导出集：原厂 kallsyms 里 11949 个 `__ksymtab_`，我们只有 8072，
差 3881——看着非常像。**但这是错的**：`/proc/kallsyms` 里的
`__ksymtab_` 既有内核自身导出的，也有模块导出的。按 `[模块名]` 后缀分开之后：

```
原厂内核自身导出: 8069
原厂模块导出:     3880
我们内核导出:     8072   （多的 3 个正是我们新加的钩子）
```

**导出集本来就是一致的**，差的 3881 全是模块提供的。
我按错误假设改过一次白名单（合并 36 份厂商 symbol list），
编出来导出数一个没变——那次改动已经还原。

### 门禁工具的缺口

`verify_vendor_abi.py` 只检查"已导出符号的 CRC 是否一致"，
它对这次的故障完全无感：CRC 一个不差，模块照样拒载。
**签名/保护策略不在它的检查范围内**，这是它的边界，得写在文档里，
下次不要再拿"门禁 PASS"当"能开机"的证据。
