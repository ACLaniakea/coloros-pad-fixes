# 联想手写笔桥接 — Root 模块

模块 ID：`lenovo_pen_bridge`，作者 ACLaniakea。版本以 `module.prop` 与 release 为准（本文不再跟版本号）。

- Hall/CPS/BLE 状态监控与真实磁吸胶囊广播；
- 开机按已绑定手写笔地址调用原厂 CoreService `CONNECT_PENCIL`，不以磁吸为前提；
- 设置页断开调用 `DISCONNECT_PENCIL`，由配套 Hook 执行真实 GATT 断开；
- `PenHidCtl.apk`（priv-app，无启动器）HID 控制辅助；
- TB710FU 刷新率策略 bind mount（post-fs-data）；
- 屏幕唤醒后 1.2 秒延迟状态回放，`pen_wakeup_*` 节点只在开机按需写一次；
- 模块内含与独立安装包同签名的 Hook 副本，只用于 LSPosed 的早期稳定读取；不执行 `pm install`、不覆盖 `/data/app`。

作用域：`com.oplus.ipemanager`、`com.heytap.mydevices` 等（见 `scope.list`），由用户手动勾选。
