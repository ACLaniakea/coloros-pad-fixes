# ColorOS Cryptoeng HAL 软件实现分析（Cryptoeng.zip / LazyBones）

> 结论先行：这套软件 cryptoeng HAL 是**完整可用的系统级替代品**，设备上现有的
> rc/manifest/SELinux 策略（hal_cryptoeng_oplus）已经和它完全配套，只需替换
> /odm/bin/hw/vendor-oplus-hardware-cryptoeng-service 这一个二进制即可切换。

## 1. 二进制概况

- vendor-oplus-hardware-cryptoeng-service：ELF64 aarch64 PIE，Android 29 target，stripped
- Rust 编写，crate vendor_oplus_cryptoeng（handlers::compat / handlers::pms / storage / request / lib）
- Binder 用 rsbinder（纯 Rust 的 binder 实现），仅动态依赖 libdl.so + libc.so（Rust std 静态链接）
- 版本串：20260720-lockscreen-password-fix-v44（作者构建目录 /home/lzy/cryptoenghal）
- 服务名：vendor.oplus.hardware.cryptoeng.ICryptoeng/default
- 日志开关：persist.sys.oplus.cryptoeng.verbose

## 2. 命令覆盖（完整）

**FindPhone（compat handler，2001-2022）全部实现：**
CeCmdRunFindphone*：EncodeByPublicKey / GetAllRegisterInfo / DecryptRegisterResult /
EncryptByPublicKey / UnregisterAndClearData / DecodeAndCheckInstruction /
ClearLockDeadByKeyguardToken / ClearLockDeadByBootReg / GetEncryptedTmpAes / EncodeByAes /
MoveDataToRpmb / GetMemberFromRpmb / ResetRpmb / GetAllRpmbInfo / AppendTmpAesAndEncrypt /
IsSupportUnlockByPasswd / SetPasswdType / GetPasswdType / VerifyPasswd / GetSalt

**PMS（锁屏密码管理）：** Enroll/Verify/Modify/Delete/GetInfo/RequestEncryptData/ResetAllData/SaveSp/GetSp

**Core（基础密码学）：** AesEnc/Dec、RsaEnc/Dec/Verify、GetDeviceid、安全连接（Request/Response
Connect/Establish、SecureConnectInit、CommonEnc/Dec）

**PKI / ID-Olock / 其它：** PkiHash/Hmac/Hkdf/Aes/Rsa/Ecdh/Sign/Attestation…（需要 OEM key backend）；
IdOlock*；GoogleAttestation、HDCP、Widevine、CleanUp、GetSecuretype 等。

**非 FindPhone/PMS 命令 → Delegating command to the platform cryptoeng fallback**（委托平台 cryptoeng）。

## 3. 存储模型

- 主存储：/data/vendor_de/0/cryptoeng（rc 里 mkdir 0770 system system encryption=None）
- 持久化：/mnt/vendor/persist/data/cryptoeng
- 文件：cryptoeng.key（存储密钥，派生自 CRYPTOENG_DEVICE_SERIAL=ro.serialno/gsm.serial/ro.boot.cpuid）、
  cryptoeng.dat（加密存储）、findphone.dat（FindPhone 持久化备份）、key.corrupt
- 数据模型（serde struct）：
  - Storage（9 元素）：update_data / find_phone_open / lock_dead_state / account_name / aes_key /
    device_id / unique_id / ssoid / sha_256 / rsa_key_version / version / device_identity /
    rsa_public_key / password_type / password_hash / email_secq / retry_count / lock_until / …
  - FindPhoneRpmbData（11 元素）、FindPhonePersistBackup（4 元素）、PrivacyData（8 元素）
- 状态键：findphone.registration_response / findphone.rsa_public_key /
  findphone.pending_aes / findphone.tmp_aes / findphone.pending_account_name /
  findphone.pending_device_id / findphone.lock_dead_authorized /
  findphone.v33_unlock_recovery_done / findphone.local_unlock_pending /
  findphone.local_unlock_bridge_sent

## 4. FindPhone 协议实现（compat handler）

- **2010 GetEncryptedTmpAes**：生成 16B tmp AES -> 内嵌 init 公钥 RSA-1024 包裹 ->
  FindPhone temporary AES key generated and wrapped；编码选项 base62 / pkcs1-v1_5 / raw；
  IMEI 来自 ro.ril.oem.imei / persist.vendor.radio.imei / persist.radio.imei / vendor.ril.imei
- **2011 UpdatePublicKey**：用 tmp_aes 解密服务器配置（aes_key_candidates 多候选），
  校验 key_version / key_exponent / key_fingerprint（key DER 的 SHA-256，见下），
  should_update / already_current
- **2002 GetAllRegisterInfo**：构造注册 JSON（accountName/deviceId/token/mobileName/ticket/
  protocolVersion/phoneCard/messageKey）-> 服务器公钥 RSA 加密 -> FindPhone registration payload encrypted
- **2003 DecryptRegisterResult**：用 messageKey（Base64 文本 ASCII 24B / AES-192）解密服务器
  响应，校验 server_accepted / missing_account_name|aes_key|device_id|unique_id|ssoid，
  保存 registration_response -> FindPhone registration result processed
- **2013/2014/2016**：RPMB 语义（MoveDataToRpmb / GetMemberFromRpmb / GetAllRpmbInfo），
  字段包括 update_data、find_phone_open、lock_dead_state、ssoid 等
- **2017 AppendTmpAesAndEncrypt**：位置上报等附加负载加密（FindPhone JSON payload wrapped
  with fresh temporary AES key）
- **open_state 跟踪**（compat.rs 1714/1740/1751）：open_state_len —— 打开状态机，这是
  LSPosed 钩子方案缺失的关键状态
- **unlock 流程**：2012 观察（unlock_2012_observed）、lock_dead_armed、SystemUI 解锁桥
  （oplus.intent.action.unlock_findphone_keyguard -> com.coloros.findmyphone 的 KeyguardReceiver，
  通过 /system/bin/cmd broadcast 触发）

## 5. 密钥与身份

- 内嵌 **FindPhone initialization public key**（RSA-1024，SPKI 162B）：
  MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC2rR5Lb45w…
  **key_fingerprint = SHA-256(DER) = 74a5a023ca3a6eb3fd23d1f09c65b7673ba38298441fa32cadfbb36fdda1033b**（已实测验证）
- 设备身份：CRYPTOENG_DEVICE_ID（pms-device-id / stable-device-identity）、
  device-identity 键；uniqueId 默认 41 个 0；IMEI 默认 15 个 0
- OEM key backend：PKI command is unavailable without the OEM key backend（PKI 命令需要私钥，FindPhone 不需要）

## 6. 与 LSPosed 钩子方案的本质区别

| 维度 | LSPosed 钩子（ColorOsCryptoEngBridge） | 软件 HAL（本分析） |
|---|---|---|
| 位置 | app 进程内模拟 | 系统级 binder 服务 |
| ssoid | 缺失（注册只有一半） | 完整存储（registration_response / ssoid） |
| open_state | 无 | 有完整状态机（open_state_len） |
| 锁屏密码（PMS） | 简单模拟 | 完整实现 + SystemUI 解锁桥 |
| 非 FindPhone 命令 | 不处理 | 委托平台 cryptoeng fallback |
| 持久化 | app files/rpmb_emulator_store.txt | /data/vendor_de/0/cryptoeng（加密） |

## 7. 部署方案（设备现状已配套）

设备现有的 cryptoeng HAL 同样是社区移植（OemPorts10T/Danda420，rc 作者注释 2026/02/16）：
- rc：service hal_cryptoeng_oplus /odm/bin/hw/vendor-oplus-hardware-cryptoeng-service（与 zip 完全一致）
- manifest：vendor.oplus.hardware.cryptoeng AIDL v1 instance default（与 zip 完全一致）
- SELinux：u:object_r:hal_cryptoeng_oplus_exec:s0（二进制）、u:r:hal_cryptoeng_oplus:s0（进程）
- 存储目录：/data/vendor_de/0/cryptoeng（rc 已建）

**因此只需替换 /odm/bin/hw/vendor-oplus-hardware-cryptoeng-service 二进制**（KernelSU 模块 overlay），
重启 hal_cryptoeng_oplus 服务，app 就会走软件实现。测试成功后，把该文件固化进 /odm
（system 镜像 / dat 分区）即可永久生效。
