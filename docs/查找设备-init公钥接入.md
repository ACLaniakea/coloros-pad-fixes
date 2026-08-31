# 查找设备：拿到 findphone-init 公钥后如何接入

## 背景

查找设备注册（`openFind` → 2010/2011）要用**服务器认可的 RSA-1024 公钥**加密本机
注册数据。这把公钥在正版 OPPO 设备的 cryptoeng TA RPMB 里，`GET_RPMB_VALUE` 等所有
客户端命令都读不出来（详见 `cryptoeng-investigation-backup/`）。

社区有人通过逆向 cryptoeng HAL/TA 拿到了这把公钥，导出为 `findphone-init-rsa1024.pem`
/ `.der`。文件名里的 **init** = 出厂预置的初始公钥；真机实测 `rsa_version=0` 且服务器
接受版本 0，说明 OPPO 未轮换，这把 init 公钥即当前生效的密钥。

**这是公钥，不是私钥，可以自由分享，取得它没有安全或法律问题。**

我们扫过手上的全部材料（真机 PKX110 完整 TA、ce_pms/ce_common 数据、ROM 的 tz.img），
都没有这把独立的 1024 公钥——只有别的子系统的 RSA-2048。所以我们复现不了这次提取，
需要直接拿到那个文件。

## 接入方式（拿到文件后，分钟级）

BaseFix Hook（`ColorOsCryptoEngBridge`）已支持从文件覆盖公钥，**无需改源码重编**：

把 `findphone-init-rsa1024.pem` 放到设备的 `/data/local/tmp/`，改名并放开读权限：

```
adb push findphone-init-rsa1024.pem /data/local/tmp/findphone_init_key.pem
adb shell su -c "chmod 644 /data/local/tmp/findphone_init_key.pem"
adb shell su -c "am force-stop com.coloros.findmyphone"
```

`.der`（二进制 SPKI）也支持，改名为 `/data/local/tmp/findphone_init_key.der` 即可。

命中后 Hook 日志会打印：

```
ColorOsCryptoEngBridge: init key loaded from pem, len=216
ColorOsCryptoEngBridge: rsaRaw override(init) outLen=128
```

此时覆盖公钥作为**唯一** RSA 公钥使用，内置候选与 `persist.key_idx` 全部忽略。

## 为什么用 /data/local/tmp 而不是 /data/adb

- `/data/adb` 是 KernelSU 目录，权限 700，查找 App（普通 uid）读不了。
- 系统属性（`persist.findphone.initkey`）有 92 字节上限，装不下 216 字节的 SPKI。
- `/data/local/tmp` 是 0771 shell:shell，others 有穿越权限，文件设 0644 即可被 App
  按全路径打开。已实测查找 App 能读到并成功加密。

## 验证

放好文件后解锁设备、打开「查找当前设备」开关，观察服务器返回码：

- `Result{code='8000'}` = 密文仍解不开，这把公钥不对；
- 开关成功打开 = 注册通过，公钥正确。

注册通过后，远程定位/响铃/锁定是否还需要每次过 cryptoeng 验签，需实测确认。
