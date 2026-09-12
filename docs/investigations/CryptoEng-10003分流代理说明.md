# CryptoEng 10003 分流代理说明

## 结论

当前共享软件 HAL 能正常处理查找设备，但 `CeCmdRunPkiHkdf`（10003）会返回
Binder `UnknownTransaction`。直接用本项目尚未完全兼容的 FindPhone 重构替换它，
会破坏已经可用的 2001–2022 注册链路。

因此采用双实例分流：

- `vendor.oplus.hardware.cryptoeng.ICryptoeng/default`：本项目的轻量代理；
- `vendor.oplus.hardware.cryptoeng.ICryptoeng/backing`：共享软件 HAL，仅把二进制中
  等长的实例名 `default` 改成 `backing`，其余字节不变；
- 方法 10003 在代理内执行 HKDF-SHA256；
- 其他方法将原始 MethodBuffer 交给 backing，并原样返回 Binder 结果。

## 10003 语义

请求参数 600 是 JSON，字段包括 `key_label`、`info`、`salt`、`okm_len`、`hash`。
`hash=2` 使用 SHA-256，IKM 来自现有 `/data/vendor_de/0/cryptoeng/cryptoeng.key`，
输出为参数类型 602（`PKI_RSP_TYPE_T`）的 UTF-8 JSON，包含 Base64 编码的 `okm`
和必需的 `version` 字段。`info` 同时兼容 `Beacon` 明文和 Base64 表示。

## 设备实测

- 原共享 HAL 的 10003：`UnknownTransaction`；
- 代理 10003：返回 52 字节响应，其中 OKM 为 32 字节；
- 代理 2002：经 backing 返回 404 字节查找设备状态响应；
- backing 与原共享 HAL 的文件主体相同，唯一二进制差异是等长服务实例名。

## 回滚

禁用或移除 `cryptoeng_hal_fix` 模块并重启，即恢复 ROM 自带 HAL。若只需恢复共享
软件 HAL，可把模块中原始共享二进制放回 ODM 目标路径并执行 `setup.sh`。
