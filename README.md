# ColorOS Pad Port Fixes — ACLaniakea 1.0.0

联想小新 Pro GT（OPD2513 / SM8650Q / pineapple）ColorOS 16 移植系统的三套独立修复源码。
源码与构建脚本保留在仓库；**不含**设备序列号、签名私钥、已编译 APK 或刷入包。

| 项目 | 组成 | 用途 |
| --- | --- | --- |
| `base-fix` | KernelSU 模块 `coloros_port_base_fix` + LSPosed Hook APK `com.aclaniakea.colorosostatsguard` | 基础修复：AON QNN 生命周期与环境光自适应、小布 BWV 语音唤醒与录音增益、144Hz 刷新率、显示色域、Tango/序列号/性能 HAL 兼容 |
| `port-tuning` | KernelSU 模块 `coloros_port_tuning` | 内存调优：恢复 ROM 基线 swappiness 继承，消除唤醒/解锁 direct-reclaim 卡顿 |
| `pen-bridge` | KernelSU 模块 `lenovo_pen_bridge` + LSPosed Hook APK `com.aclaniakea.lenovopenbridge` + priv-app `com.aclaniakea.penhidctl` | 联想手写笔桥接：原厂 CoreService BLE 连接/断开、CPS/Hall 状态同步、HID 控制 |

## 版本约定

- 三套 KernelSU 模块与全部 APK 统一版本 `1.0.0` / `versionCode=1000`
- 作者统一为 `ACLaniakea`，包名统一为 `com.aclaniakea.*`

## 安装顺序（全新安装）

1. 卸载旧包：`com.codex.colorosostatsguard`、`com.codex.lenovopenbridge`、`com.codex.penhidctl`
2. 卸载旧模块：`coloros_port_base_fix`、`coloros_port_tuning`、`lenovo_pen_bridge`
3. 安装 `releases/` 下三个模块 ZIP（`ksud module install`）与两个 Hook APK
4. 重启后，在 LSPosed 管理器按各模块 `scope.list` 手动勾选作用域

## 构建

```text
python3 base-fix/hook/tools/build_integrated_hook.py   # 构建 base hook APK
python3 base-fix/module/tools/build_release.py          # 构建 base fix 模块
python3 pen-bridge/hook/tools/build_hook_v1.py          # 构建 pen hook APK
python3 pen-bridge/module/tools/build_root.py           # 构建 pen bridge 模块
python3 port-tuning/tools/build_tuning.py               # 构建调优模块
```

构建产物统一输出到 `releases/`。
