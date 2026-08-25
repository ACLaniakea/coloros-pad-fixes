# thermal-engine 配置覆盖的两个隐形陷阱

## 1. SELinux context —— 这是真正的根因

KernelSU 的 `system/` 覆盖把模块内的文件原样挂到 `/vendor/etc`，而模块 zip 里
的文件带的是 `u:object_r:system_file:s0`。thermal-engine 跑在 vendor 域，读不了
`system_file`：

```
u:object_r:system_file:s0         /vendor/etc/thermal-engine_gpu_0.conf   ← 我们的覆盖
u:object_r:vendor_configs_file:s0 /vendor/etc/thermal-engine_gpu_2.conf   ← 原厂
```

结果是**整份配置被静默丢弃**：策略不生效、不报错、logcat 与 dmesg 里都没有
任何痕迹（连 avc denial 都不出现在常规过滤里）。

实测判据（注入 47.5 °C 到外壳温热区）：

| context | GPU 反应 |
|---|---|
| `system_file` | `gpu_cdev=0`、`max_gpuclk=903000000`，毫无反应 |
| `vendor_configs_file` | `gpu_cdev=3`、`max_gpuclk=720000000`，撤掉后回满 |

挂载点是只读的，事后对 `/vendor/etc/...` 执行 `chcon` **无效**（实测执行后
context 仍是 `system_file`）。所以修法不是重新打标签，而是改用本项目已验证可行的
bind mount：源文件放在模块的 `payload/thermal/`，先 `chcon` 成
`vendor_configs_file`，再 `mount --bind` 到原路径——见 `post-fs-data.sh` 的
`bind_thermal_configs()`。

**这意味着在此之前所有经由该覆盖下发的 thermal-engine 配置都没有真正生效过**
（包括 battery_0/battery_2 里那个"充电时小核限频触发点抬到 52 °C"的调优）。

## 2. 一次误判，记在这里以免重犯

排查过程中我一度把原因归给"配置里的注释行"，因为去掉注释的那份能生效。
实际上那份文件是我手动 `chcon` 过的，生效的原因是 context 而不是注释。
两个变量同时改动 → 归因错误。现在这几个 `.conf` 仍保持无注释、纯 ASCII
（与原厂格式一致），但**注释是否真的有害并未被单独验证过**，不要当成结论。

## 3. 同一根因的第二处：/vendor/etc/perf/targetconfig.xml

同目录十份配置里只有我们覆盖的那份是 `system_file`，perf HAL 跑在
`vendor_hal_perf_default` / `vendor_perfservice`，读不到。为 SoC ID 696 补的
6 核 4 簇拓扑因此从未生效。拓扑本身经硬件核对是正确的
（`present=0-5`；policy 0 / 1-2 / 3-4 / 5；capacity 379 / 867x4 / 1024）。
已迁到 `payload/perf/`，与温控配置共用 `bind_vendor_config()`。

`/system_ext/etc/horae/horae_.conf` 不受影响：同目录原厂文件本来就是
`system_file`，Horae 读得到。

**教训**：凡是要被 vendor 域进程读取的覆盖文件，都不能走 KernelSU 的
`system/` 覆盖。检查方法很简单——`ls -Z` 比对同目录的原厂文件，标签不一致
就是没生效。
