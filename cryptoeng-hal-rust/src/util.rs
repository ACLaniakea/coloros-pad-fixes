//! 工具：base62 编码、Android 系统属性读取、FindPhone 身份初始化。

use num_bigint::BigUint;

const B62: &[u8] = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

/// 16 字节 → base62 字符串（22 字符），与软件 HAL 的 message_key 一致。
pub fn base62(data: &[u8]) -> String {
    let mut arr = [0u8; 16];
    let n16 = data.len().min(16);
    arr[..n16].copy_from_slice(&data[..n16]);
    let mut n = u128::from_be_bytes(arr);
    if n == 0 {
        return "0".to_string();
    }
    let mut s = String::new();
    while n > 0 {
        let r = (n % 62) as usize;
        s.push(B62[r] as char);
        n /= 62;
    }
    s.chars().rev().collect()
}

/// 读 Android 系统属性。该符号由 bionic 提供，不属于 host libc；保留一个
/// 可预测的宿主机实现，使协议/密码学回归测试不依赖 Android 运行时。
#[cfg(target_os = "android")]
pub fn sysprop(key: &str) -> String {
    unsafe extern "C" {
        fn __system_property_get(
            name: *const std::ffi::c_char,
            value: *mut std::ffi::c_char,
        ) -> libc::c_int;
    }
    let c_key = std::ffi::CString::new(key).unwrap_or_default();
    let mut buf = [0u8; 256];
    let len = unsafe { __system_property_get(c_key.as_ptr(), buf.as_mut_ptr().cast()) };
    if len <= 0 {
        return String::new();
    }
    let bytes: Vec<u8> = buf[..len as usize].to_vec();
    String::from_utf8_lossy(&bytes).into_owned()
}

#[cfg(not(target_os = "android"))]
pub fn sysprop(key: &str) -> String {
    std::env::var(key).unwrap_or_default()
}

/// SHA-256 后取十进制数字（前 length 位）。
pub fn decimal_id(material: &str, length: usize) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(material.as_bytes());
    let n = BigUint::from_bytes_be(&digest);
    let digits = n.to_string();
    let mut out = String::new();
    while out.len() + digits.len() < length {
        out.push('0');
    }
    out.push_str(&digits);
    if out.len() > length {
        out.truncate(length);
    }
    out
}

pub const DEFAULT_DEVICE_ID: &str =
    "0db683cee188696671337eff1d4ee7922fa28b26923455503758dafe3ca19c58";

/// 初始化稳定的 RPMB 身份（device_id/unique_id/imei/lock_dead_state）。
pub fn ensure_identity(store: &mut crate::storage::Storage) {
    let empty = |s: Option<String>| s.map(|v| v.is_empty()).unwrap_or(true);
    if empty(store.get("device_id")) {
        store.set("device_id", DEFAULT_DEVICE_ID.to_string());
    }
    if empty(store.get("unique_id")) {
        let material = format!(
            "{}|{}|{}|{}|{}",
            store.get("device_id").unwrap_or_default(),
            sysprop("ro.serialno"),
            sysprop("ro.boot.serialno"),
            sysprop("ro.product.vendor.device"),
            sysprop("ro.build.fingerprint")
        );
        store.set("unique_id", decimal_id(&material, 41));
    }
    if empty(store.get("imei")) {
        let material = format!("{}|imei", store.get("device_id").unwrap_or_default());
        store.set("imei", decimal_id(&material, 15));
    }
    if empty(store.get("lock_dead_state")) {
        store.set("lock_dead_state", "1".to_string());
    }
    if empty(store.get("rsa_version")) {
        store.set("rsa_version", "0".to_string());
    }
}

/// 与 TA crypto_eng_aes_string_key_generate 一致：n 个随机字节，每个字节映射到
/// 62 字符集里的一个字符（byte % 62），得到 n 个字符的字符串。这 n 个 ASCII
/// 字节直接作为 AES 密钥（n=16 → AES-128）。注意这不是把整数做进制转换，
/// 与 base62() 完全不同。
pub fn aes_string_key(n: usize) -> String {
    const CHARSET: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    crate::crypto::random_bytes(n)
        .iter()
        .map(|b| CHARSET[(*b as usize) % CHARSET.len()] as char)
        .collect()
}
