//! vendor_oplus_cryptoeng — ColorOS CryptoEng HAL 可复现重构。
//!
//! 架构（与 Cryptoeng.zip 软实现 / 原厂 HAL 同构）：
//!   request::Request   — standard/native 两种请求解析
//!   handlers           — compat(FindPhone) / pms / native / pki
//!   storage::Storage   — cryptoeng.key / cryptoeng.dat 持久化
//!   service            — rsbinder 服务入口（ICryptoeng/default）
//!
//! 本重构的目标：
//!   1. 函数级还原 Cryptoeng.zip 软实现（FindPhone 2001-2022 协议）；
//!   2. 新增 PKI handler（含 10003 CeCmdRunPkiHkdf），修复一加互传
//!      联系人模式依赖的 Beacon HKDF 派生；
//!   3. 纯软件、仅依赖 libc/libdl，SELinux 域 hal_cryptoeng_oplus。

pub mod crypto;
pub mod request;
pub mod response;
pub mod types;
pub mod storage;
pub mod handlers;
pub mod service;

// 生成 ICryptoeng binder 绑定（aidl/build.rs）
rsbinder::include_aidl!("icryptoeng", crate::vendor::oplus::hardware::cryptoeng::ICryptoeng::*);
pub mod util;
pub mod cert;
