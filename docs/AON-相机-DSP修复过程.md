# AON、相机与 DSP 修复过程

本文记录 TB710FU（SM8650Q）上的 ColorOS 16 移植包在 AON 注视感知和相机共存方面的排查、失败路径与最终实现。目标不是伪造“人在看屏幕”的结果，也不使用 CPU 推理兜底；最终链路保持为真实前摄帧、原厂 AON 模型、QNN 与 Hexagon DSP。

## 最终状态

- 前摄和后摄均由稳定的 Camera Provider 提供，Provider 不再因 `SIGPIPE` 循环重启。
- AON 读取真实前摄 `YUV_420_888` 帧，完成对 Lenovo CamX 非连续 UV 平面的归一化。
- AON 模型的 90 个节点全部由 `TfLiteQnnDelegate` 交给 QNN HTP/DSP；没有 CPU fallback。
- `AttentionDetector` 已实际收到 `onSuccess: 1`，系统沿原有 Attention/SmartDim 回调保持亮屏。
- 每次检测结束均执行原厂 `AIBoost_Destroy`，返回 `status = 0`；不再通过拦截析构来维持状态。
- 正常相机打开时，现有 Camera/AppOps 仲裁钩子负责暂停 AON；相机服务 PID 在测试中保持稳定。

## 根因分层

### 1. Camera Provider 被 SIGPIPE 杀死

原始 Provider 在 Lenovo CamX 与移植系统交界处会收到 `SIGPIPE`，init 随即重启 `vendor.camera-provider`。这会造成设备重新枚举、预览黑屏、AON 看似抢相机，以及错误的开关相机回调。

模块中的 `payload/camera/vendor.qti.camera.provider-service_64` 在构造阶段为信号 13 安装 `SIG_IGN`，再尾调用原构造函数。部署仍沿用原厂 Provider 的 SELinux 域与启动路径；`sepolicy.rule` 仅补充 `init -> hal_camera_default` 的 nosuid 域切换许可。

### 2. CameraService 拒绝 Lenovo CamX 的动态深度标签

ColorOS 的 `libcameraservice.so` 对 Lenovo CamX 返回的动态 depth tags 处理过严，返回 `-22` 后会加剧 provider 异常。模块中的 `payload/lib64/libcameraservice.so` 仅将该非致命返回归零，让三颗相机继续枚举；没有改动相机算法或相机 ID。

### 3. AON 的 QNN 用户态与 Lenovo DSP skeleton 版本不匹配

移植 AON 带的是 ColorOS QNN 2.34 用户态，而 Lenovo vendor 的 RFSA V75 skeleton 是 QNN 2.21。只把用户态 `.so` 放进 AON 私有 linker namespace 时，DSP 远端仍解析 vendor 的 2.21 skeleton，日志为：

```text
Skel lib id mismatch: expected v2.34..., detected v2.21...
InferenceAiboost nativeModelCreate fail
```

此时相机帧可以到达 AON，但模型不会创建，所有结果都会是“未注视”。

这次还确认了一个会让问题在每次重启后表现不同的第二层原因：KernelSU
会为 AON 建立私有 mount namespace。即使根命名空间中的 RFSA skeleton
已经被替换，AON 进程仍可能看见 Lenovo 的 2.21 文件。因此 namespace
loader 必须在每个新 AON PID 恢复运行前，把同一份已校验的 2.34 skeleton
bind 到该 PID 私有视图中的 `/vendor/lib/rfsa/adsp/libQnnHtpV75Skel.so`。
只挂载应用库目录或只改全局 `/vendor` 都不充分。

同时，恢复的 delegate 原本把 `ADSP_LIBRARY_PATH` 写成以分隔符开头的路径
串，首项为空。TB710FU 上这会让 QNN 在创建 HTP session 前失败。模块使用
SHA-256 限定的一字节修正移除空首项，保留原有的绝对路径及搜索顺序；不改
模型、结果回调或 CPU/DSP 选择。

### 4. Lenovo CamX 的 YUV 平面布局不同

前摄输出为 `YUV_420_888`，Y 平面连续，但 U/V 平面是 `rowStride=320`、`pixelStride=2` 的交错布局。移植 AON 直接按连续 planar YUV 读取，导致模型虽能运行，输入却失真。

`AonYuvLayoutBridge` 仅在原始 `transform(Image)` 返回后将三平面复制为 AON 期望的连续 Y、U、V 缓冲区；不生成脸、视线或 Attention 回调。

## 已尝试但没有保留的方案

| 方案 | 结果 | 不保留原因 |
|---|---|---|
| 强制让 `checkAttention` 返回不可用 | 可阻止部分抢相机 | 直接让 Attention 失败，违背原厂功能；已删除。 |
| 把 SmartFaceGaze 映射为 `0x60001` | 可进入旧的一次性路径 | 混合两个生命周期，易出现已启动/提前停止；最终使用原框架 `0x60007`。 |
| 以 `am force-stop` 停 AON | 可能暂时释放相机 | AON 会被框架重新拉起，且开机早期会形成死锁/重启风险；已删除。 |
| Lenovo QNN 2.21 Delegate 与 ColorOS AIBoost 混用 | 能通过一部分 capability 检查 | TFLite ABI 不匹配，`TfLiteQnnDelegateCreate` 崩溃；已删除。 |
| CPU 推理或伪造“注视”结果 | 理论上可绕过 DSP 问题 | 不是真实 AON 路径，未采用。 |

## 最终实现

### QNN / DSP 运行时

`post-fs-data.sh` 在满足两项 SHA-256 校验时，将模块中与 ColorOS AON 用户态匹配的 V75 skeleton bind 到 RFSA 解析点：

```text
源：payload/aon-libs/cdsp/unsigned/libQnnHtpV75Skel.so
源 SHA-256：c43a2dcd4be3982b9baee6b34ba26ca02b12179dc331e129cafd817ce36188ea

目标：/vendor/lib/rfsa/adsp/libQnnHtpV75Skel.so
仅接受的 Lenovo 原始 SHA-256：2215f369e1b640cddfadf9fa3970f52f6f245b6dc0f676bab428b8627a5ea9ea
```

这是 systemless bind mount，不写 vendor 分区；卸载脚本会解除该挂载。若目标机的原始 SHA 不同，脚本拒绝覆盖并保留 vendor 原文件，避免把未知机型误接到错误 DSP ABI。

`aon-namespace-loader.sh` 还会对每个新 AON 进程执行同一 skeleton 的私有
namespace bind，并同时校验 JNI、ODM AIBoost 与 skeleton 的 SHA。运行时日志
只有在三项一致时才记录 `AON namespace runtime attached`；因此重启、AON
进程被系统回收后再次启动，都走相同的确定性部署路径。

### Hook 与原厂生命周期

- `AonSmartFaceGazeCompat` 只补齐移植 AON 缺失的 `0x60007` profile/capability 表项，框架的 SmartDim 状态机与 Binder 操作不改写。
- `AonYuvLayoutBridge` 只做相机缓冲区布局转换。
- `nativeCreate`、模型推理、`FaceInfo` 事件、Attention 成功/失败、`nativeDelete` 全部由 AON 原始实现处理。
- 调试阶段的逐帧探针已从实际安装路径移除，避免额外 logcat I/O 和 CPU 唤醒。

## 实机验证证据

重启后实机日志包含：

```text
QNN_DELEGATE API VERSION:0.24.0
Replacing 90 node(s) with delegate (TfLiteQnnDelegate) node
Applied delegate: QNN
AIBoost_Create out, session = ...
AttentionDetector: onSuccess: 1
AIBoost_Destroy out, session = ..., status = 0
```

并验证：

1. 在 15 秒屏幕超时下，连续 SmartDim 周期均产生 `AttentionDetector: onSuccess: 1`，且 `mWakefulness=Awake`。
2. 连续创建、推理、销毁均成功，`AIBoost_Destroy` 返回 `status = 0`。
3. 完整重启后，新 AON PID 的私有 RFSA skeleton SHA 仍为 2.34 修复版本，QNN 可再次创建 session。
4. 相机启动前后 Provider PID 未变化，未再次出现 provider 重启循环。

## 维护与回退

- 本修复严格限定在 TB710FU / SM8650Q 的模块设备门槛内。
- 不要把 QNN 2.21 Delegate 与 ColorOS 的 AIBoost 混装；它们不是兼容替代品。
- 若升级 vendor 后 RFSA skeleton SHA 变化，模块会安全跳过 DSP skeleton 挂载；此时应重新验证 ABI，不能删除校验强行覆盖。
- 若需临时回退，禁用/卸载模块后重启即可；`uninstall.sh` 也会主动解除 AON app runtime 与 RFSA skeleton 的 bind mount。
