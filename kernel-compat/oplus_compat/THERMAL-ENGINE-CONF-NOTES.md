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

## 3. 待查

`fix-module/module/system/vendor/etc/perf/` 仍然走 KernelSU 的 system 覆盖，
属于同一类风险（perf HAL 同样是 vendor 域）。尚未验证它是否真的被读到。
