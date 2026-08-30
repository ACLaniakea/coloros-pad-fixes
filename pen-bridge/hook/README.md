# 联想手写笔桥接 — LSPosed Hook

包名：`com.aclaniakea.lenovopenbridge`。版本以 `AndroidManifest.xml` 与 release 为准（本文不再跟版本号）。

- 桥接联想手写笔 BLE/GATT、System Server、IPeManager、Hall、设备空间与设置逻辑；
- 设置页断开时反射调用原厂 `BleManager.a()` 的 `BluetoothGatt.disconnect()`，设备/HID 真实离开连接态；
- 设备空间手写笔存在状态只跟随真实蓝牙连接；电量未知时保留最后一次有效硬件电量；
- 不涉及亮度、背光或屏幕电源参数；
- 安装顺序：先卸载旧包再安装新包；作用域在 LSPosed 管理器按 `scope.list` 手动勾选。
