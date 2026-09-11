# QTI Perf 用户态仲裁探针

`qti_perf_probe` 只调用 QTI AIDL `IPerf.getInterfaceVersion()`，用来确认目标
模块/守护进程所在 SELinux 与 Binder 上下文能否访问：

```text
vendor.qti.hardware.perf2.IPerf/default
```

它不提交任何 `perfLockAcquire`、`perfHint` 或资源 ID，故可安全用于部署前检查。

已从设备自带 `vendor.qti.hardware.perf2-V1-ndk.so` 还原的后续接口包括：

```text
perfLockAcquire(handle, durationMs, resourceList, reservedList)
perfLockRelease(handle, reservedList)
perfHintAcqRel(...)
```

QTI 的资源表把 `0x3/0x20` 映射到 `/proc/sys/walt/sched_per_task_boost`；它是
PerfHAL 可管理的配置入口，并不是一个能由普通用户态调用者指定目标 Linux task 的
安全桥接 API。只有该 probe 在最终运行上下文通过、并完成调用者权限与锁生命周期
设计后，才考虑实现写入型适配器。

## OPD2513 当前结果

探针已在 `u:r:ksu:s0` 下运行。传统 `service call` 可读取该服务的 interface
version（`1`），但 NDK proxy 在 `AIBinder_associateClass()` 阶段失败，因而尚未
发出任何 `IPerf` 业务 transaction。这表明不能把一个普通 Magisk/KernelSU
二进制直接当作 QTI HAL 客户端。

下一步必须二选一：复用/注入设备已有的 QTI generated `IPerf` client，或让一个
受 SELinux 允许的 vendor 组件代为提交请求。不要绕过此检查手工发送 transaction
`11`；那会失去接口类型检查和调用者权限边界。

## 已核对的旧式系统入口（不是 UX task bridge）

设备还提供 `vendor.perfservice`（`u:r:vendor_perfservice:s0`）。它使用系统侧
`libqti-perfd-client_system.so` 转发至 QTI Perf2；系统的 `QPerformance.jar` 已带有
`com.qualcomm.qti.IPerfManager` 的类型安全客户端。已用无副作用的
`getPerfHalVer()` 确认该入口与 Perf2 都在运行（返回 2.3）。

该旧接口的 `perfLockAcquire(durationMs, resourceList)` 虽可让 QTI 仲裁资源，却没有
“指定某一个 Linux task”的参数。`0x3/0x20` 对应的是 WALT 的
`sched_per_task_boost` 配置入口，不等价于内核桥对 *当前 OPlus UX task* 调用
`set_task_boost()`。因此不要把它做成 root 脚本或手工 Binder transaction：它既会
绕过 QPerformance 的平台/特权调用限制，也可能覆盖 PowerHAL 已持有的更长 boost。

结论：`vendor.perfservice` 是现有频率/功耗提示的健康依赖，应保持 running；它不是
本项目 UX-to-WALT bridge 的替代实现。当前可选内核桥仍保持默认关闭，并以短时人工
测试为限，直到存在可合并 WALT boost 生命周期的公开接口。

补充实测：桥未加载的 8 次 launcher swipe 没有产生 `sched_task_handler` 记录，且
`perfboostsconfig.xml` 没有 profile 引用该资源；当前原生滑动路径没有使用
`sched_per_task_boost`。这只能说明默认场景不存在已观测的冲突，不能授权我们绕过
QTI/系统调用边界，也不能作为默认启用桥的依据。
