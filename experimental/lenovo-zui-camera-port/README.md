# Lenovo ZUI Camera Port - 尝试记录（归档）

本目录归档 2026-09-01 尝试把联想 ZUI 相机（TB710FU 原厂）移植到 ColorOS 16 移植包的全部工作。
**结论：在当前 ColorOS 移植包上无法完整移植**，详见下方。

## 尝试内容
- **lenovo-zui-camera-module/**: ZUI 相机三件套（ZuiCamera 18.0.1.251216192500 + Assistant + QR）systemless 模块
- **ZuiCameraCompatBridge.java**: LSPosed hook（AppFeatures/CameraDeviceInfo/AlgoUtils 设备身份 -> TB710FU/TB132，modes 修复，跳过 Morpho native init）
- **lcaf-config/**: Morpho 降噪/超分 XML（assets/lgsi 与 assets/topaz 提取）
- **len_cam_module*.zip**: 联想 cameraserver + Pandora 算法栈（40 库）overlay 模块（v1/v2 无 rc、v3 带 rc）
- **provider_fixed2.so**: camx.provider-impl.so binderDied abort 修复（= bca67ef9，与社区补丁一致）

## 关键结论
1. **HAL 完全兼容**: ColorOS 移植包的 camx 库/配置与联想原厂逐字节一致，无需移植
2. **ZUI 相机崩溃链**:
   - 基础打开/modes: 由 hook 修复（设备身份 TB710FU）
   - 微距: lcaf XML 配置补丁修复
   - 拍照后处理/人像追踪: Morpho 库（ImageRefiner/auto_framing）在 ColorOS 上 SIGILL（native 深层兼容，Java hook 无效）
3. **联想 cameraserver 无法集成**: init 解析 rc 早于模块 overlay（动态分区也无法直接修改）-> init.svc.cameraserver 为空
4. **provider 崩循环**: ColorOS cameraserver 提前释放回调触发原厂 provider binderDied abort -> 已修复（bca67ef9），该修复保留在生产（系统稳定关键）

## 当前生产状态（回退后）
- 系统稳定: provider 修复 + 相机 3 设备 + AON 正常
- ZUI 相机: 已移除（拍照/人像追踪闪退）
- 相机: aperture + ColorOS 相机
