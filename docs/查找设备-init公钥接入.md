# 查找设备：init 公钥已拿到并接入

## 结论

之前卡注册的那把「服务器 RSA-1024 注册公钥」（`FindPhone initialization public key`，
version 0）已经从 `Cryptoeng.zip`（ColorOS-CryptoengHAL 软实现二进制）里提取出来：

- 模数 1024 bit，指数 65537，SPKI DER 162 字节；
- 文件：`docs/findphone-init-rsa1024.pem`（PEM）、`docs/findphone-init-rsa1024.der`（二进制 SPKI）。

`ColorOsCryptoEngBridge` 已把该密钥内嵌为默认注册公钥：`initKeyB64()` 在未检测到
`persist.findphone.initkey` 属性或 `/data/local/tmp/findphone_init_key.*` 文件覆盖时，
直接返回内嵌密钥。因此**重编后的模块开箱即用，无需手动 push**。

## 不重编的接入方式（现有模块上直接生效）

把 PEM 推到设备即可（文件覆盖优先级高于内嵌）：

```
adb push docs/findphone-init-rsa1024.pem /data/local/tmp/findphone_init_key.pem
adb shell su -c "chmod 644 /data/local/tmp/findphone_init_key.pem"
adb shell su -c "am force-stop com.coloros.findmyphone"
```

`.der` 同理，改名为 `/data/local/tmp/findphone_init_key.der`。

命中后 Hook 日志会打印：

```
ColorOsCryptoEngBridge: init key loaded from pem, len=216
ColorOsCryptoEngBridge: rsaRaw override(init) outLen=128
```

（重编后的模块不 push 也会打印 `using embedded findphone init public key len=216`。）

## 验证

放好文件 / 装好新模块后解锁设备、打开「查找当前设备」开关：

- `Result{code='8000'}` = 密文仍解不开（这把公钥不对，或 2010/2002 报文格式仍不符）；
- 开关成功打开 = 注册通过。

注册通过后，远程定位/响铃/锁定是否还需要每次过 cryptoeng 验签，需实测确认。

## 备选：直接用软实现 HAL

`Cryptoeng.zip` 里除了这把公钥，还带了一个完整的软件 cryptoeng HAL
（`vendor-oplus-hardware-cryptoeng-service`，Rust/rsbinder 实现）。它内嵌这把 init 公钥，
实现全部 `CeCmdRunFindphone*`（2001–2022）命令，非 FindPhone 命令回落到平台 cryptoeng。
若 Xposed 模拟仍过不了注册，可评估用该 HAL 直接顶替设备上加载 TA 报 Error 12 的
原厂 cryptoeng HAL（需处理 SELinux/VINTF/init 部署），那是更接近真机的方案。
