//! PKI 处理器 —— 新增：10003 CeCmdRunPkiHkdf（一加互传联系人模式 Beacon 派生）。
//!
//! 真机语义（从原厂 TA / PantaConnect 调用方逆向）：
//!   输入 JSON: {"only_key_label":"0","key_label":"<label>",
//!               "info":"<base64>","salt":"<base64>","okm_len":"<n>","hash":"<id>"}
//!   IKM       = key_label 对应的设备密钥（本实现：cryptoeng.key，32 字节）
//!   hash=2    = SHA-256
//!   OKM       = HKDF-SHA256(IKM, salt, info, okm_len)

use base64::{
    engine::general_purpose::{STANDARD as B64, URL_SAFE, URL_SAFE_NO_PAD},
    Engine as _,
};
use hkdf::Hkdf;
use sha2::Sha256;

use crate::request::Request;
use crate::storage::Storage;
use crate::types::Param;

const CONTACT_COMPAT_PATH: &str = "/data/vendor_de/0/cryptoeng/contact_compat.json";
const CRYPTOENG_TA_PATH: &str = "/odm/vendor/firmware/cryptoeng.b04";
const CONTACT_KEY_LABEL: &str = "SMYijOgbT1JfVMug";

#[derive(serde::Deserialize)]
struct ContactCompat {
    key_label: String,
    salt_hex: String,
    okm: String,
    version: String,
}

#[derive(Debug)]
pub struct PkiHkdfParams {
    pub only_key_label: String,
    pub key_label: String,
    pub info: Vec<u8>,
    pub salt: Vec<u8>,
    pub okm_len: usize,
    pub hash: u32,
}

pub fn parse_pki_hkdf(json: &str) -> Result<PkiHkdfParams, String> {
    let v: serde_json::Value =
        serde_json::from_str(json).map_err(|e| format!("bad pki hkdf json: {e}"))?;
    let text = |k: &str| -> String {
        match v.get(k) {
            Some(serde_json::Value::String(value)) => value.clone(),
            Some(serde_json::Value::Number(value)) => value.to_string(),
            _ => String::new(),
        }
    };
    let bytes = |k: &str| -> Result<Vec<u8>, String> {
        let value = text(k);
        if value.is_empty() {
            return Ok(Vec::new());
        }
        if k == "info" && value == "Beacon" {
            return Ok(value.into_bytes());
        }
        // OEM callers in different ColorOS branches use either base64 or a
        // literal context string (notably "Beacon"). Accept both without
        // altering the byte representation supplied by a valid base64 caller.
        for engine in [&B64, &URL_SAFE, &URL_SAFE_NO_PAD] {
            if let Ok(decoded) = engine.decode(&value) {
                return Ok(decoded);
            }
        }
        Ok(value.into_bytes())
    };
    let okm_len = text("okm_len").parse().unwrap_or(32);
    if okm_len == 0 || okm_len > 255 * 32 {
        return Err(format!("pki hkdf: invalid okm_len {okm_len}"));
    }
    Ok(PkiHkdfParams {
        only_key_label: text("only_key_label"),
        key_label: text("key_label"),
        info: bytes("info")?,
        salt: bytes("salt")?,
        okm_len,
        hash: text("hash").parse().unwrap_or(2),
    })
}

fn derive_hkdf(ikm: &[u8], salt: &[u8], info: &[u8], okm_len: usize) -> Result<Vec<u8>, String> {
    let hk = Hkdf::<Sha256>::new(Some(salt), ikm);
    let mut okm = vec![0u8; okm_len];
    hk.expand(info, &mut okm)
        .map_err(|e| format!("pki hkdf expand: {e}"))?;
    Ok(okm)
}

/// 从设备自带的 OPlus CryptoEng TA 固件提取 provisioning 记录。
///
/// b04 中该记录按 NUL 分隔存放为：key_label、Base64(IKM)、数字版本。
/// 这里不内置密钥，只读取设备已有的 OEM provisioning，并对结构做严格限制，
/// 避免把固件中的无关字符串误当作密钥材料。
fn load_ta_provisioning(path: &str, requested_label: &str) -> Result<(Vec<u8>, String), String> {
    if requested_label != CONTACT_KEY_LABEL {
        return Err(format!("pki hkdf: unsupported key label {requested_label}"));
    }

    let firmware = std::fs::read(path).map_err(|e| format!("pki hkdf: read TA firmware: {e}"))?;
    let label = requested_label.as_bytes();
    let start = firmware
        .windows(label.len())
        .position(|window| window == label)
        .ok_or("pki hkdf: provisioning label absent from TA firmware")?;
    let tail = &firmware[start + label.len()..];
    let fields: Vec<&[u8]> = tail
        .split(|byte| *byte == 0)
        .filter(|field| !field.is_empty())
        .take(2)
        .collect();
    if fields.len() != 2 {
        return Err("pki hkdf: incomplete TA provisioning record".into());
    }

    let encoded = std::str::from_utf8(fields[0])
        .map_err(|_| "pki hkdf: provisioning key is not UTF-8")?;
    let version = std::str::from_utf8(fields[1])
        .map_err(|_| "pki hkdf: provisioning version is not UTF-8")?;
    if version.len() < 10 || version.len() > 20 || !version.bytes().all(|b| b.is_ascii_digit()) {
        return Err("pki hkdf: invalid provisioning version".into());
    }
    let ikm = B64
        .decode(encoded)
        .map_err(|e| format!("pki hkdf: invalid provisioning key encoding: {e}"))?;
    if ikm.len() != 44 {
        return Err(format!("pki hkdf: invalid provisioning key length {}", ikm.len()));
    }
    Ok((ikm, version.to_owned()))
}

/// 执行 10003：用设备密钥做 HKDF。ColorOS PKI 调用方要求 602
/// (PKI_RSP_TYPE_T) 中承载 UTF-8 JSON，而不是直接返回原始 OKM。
pub fn handle_pki_hkdf(req: &Request, storage: &mut Storage) -> Result<Vec<Param>, String> {
    let json = req
        .param(600)
        .ok_or("pki hkdf: missing param 600")?
        .as_str();
    let p = parse_pki_hkdf(&json)?;
    if p.hash != 2 {
        return Err(format!("pki hkdf: unsupported hash {}", p.hash));
    }

    if let Ok(text) = std::fs::read_to_string(CONTACT_COMPAT_PATH) {
        if let Ok(compat) = serde_json::from_str::<ContactCompat>(&text) {
            let salt_hex: String = p.salt.iter().map(|b| format!("{b:02x}")).collect();
            if compat.key_label == p.key_label && compat.salt_hex.eq_ignore_ascii_case(&salt_hex) {
                let decoded = B64
                    .decode(&compat.okm)
                    .map_err(|e| format!("contact compat okm: {e}"))?;
                if decoded.len() != p.okm_len {
                    return Err(format!(
                        "contact compat okm length {} != {}",
                        decoded.len(), p.okm_len
                    ));
                }
                let payload = serde_json::json!({
                    "okm": compat.okm,
                    "version": compat.version,
                })
                .to_string()
                .into_bytes();
                return Ok(vec![Param { ptype: 602, data: payload }]);
            }
        }
    }

    // 联系人 Beacon 必须使用 OEM TA 中随设备提供的 GUK provisioning。
    // cryptoeng.key 属于查找设备软 HAL 的本地存储，二者不可互换。
    let _ = storage;
    let (ikm, version) = load_ta_provisioning(CRYPTOENG_TA_PATH, &p.key_label)?;

    let okm = derive_hkdf(&ikm, &p.salt, &p.info, p.okm_len)?;

    log::info!(
        "pki hkdf: label={} info={} okm_len={} -> {} bytes",
        p.key_label,
        String::from_utf8_lossy(&p.info),
        p.okm_len,
        okm.len()
    );
    let payload = serde_json::json!({
        "okm": B64.encode(&okm),
        "version": version,
    })
    .to_string()
    .into_bytes();

    Ok(vec![Param {
        ptype: 602,
        data: payload,
    }])
}

#[cfg(test)]
mod tests {
    use super::{derive_hkdf, load_ta_provisioning, parse_pki_hkdf, CONTACT_KEY_LABEL};
    use base64::{engine::general_purpose::STANDARD as B64, Engine as _};

    #[test]
    fn parses_coloros_string_and_numeric_fields() {
        let p = parse_pki_hkdf(
            r#"{"only_key_label":"0","key_label":"label","info":"Beacon","salt":"WZaNnw==","okm_len":32,"hash":2}"#,
        )
        .unwrap();
        assert_eq!(p.key_label, "label");
        assert_eq!(p.info, b"Beacon");
        assert_eq!(p.salt, [0x59, 0x96, 0x8d, 0x9f]);
        assert_eq!(p.okm_len, 32);
        assert_eq!(p.hash, 2);
    }

    #[test]
    fn hkdf_sha256_matches_rfc5869_case_1() {
        let ikm = [0x0b; 22];
        let salt: Vec<u8> = (0x00..=0x0c).collect();
        let info: Vec<u8> = (0xf0..=0xf9).collect();
        let okm = derive_hkdf(&ikm, &salt, &info, 42).unwrap();
        let actual: String = okm.iter().map(|byte| format!("{byte:02x}")).collect();
        assert_eq!(
            actual,
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        );
    }

    #[test]
    fn parses_ta_provisioning_and_matches_original_10003() {
        let mut fixture = vec![0x7f, 0x45, 0x4c, 0x46, 0];
        fixture.extend_from_slice(CONTACT_KEY_LABEL.as_bytes());
        fixture.extend_from_slice(b"\0PtaDHD4dfnqBI+0IP8YqNcWWTkht4TEsFQ9nBAgnok7FSFbTvBsk1RIAY5g=\01640086682674\0");
        let path = std::env::temp_dir().join(format!("cryptoeng-ta-test-{}", std::process::id()));
        std::fs::write(&path, fixture).unwrap();
        let (ikm, version) = load_ta_provisioning(path.to_str().unwrap(), CONTACT_KEY_LABEL).unwrap();
        let okm = derive_hkdf(&ikm, &[0x59, 0x96, 0x8d, 0x9f], b"Beacon", 32).unwrap();
        let _ = std::fs::remove_file(path);
        assert_eq!(version, "1640086682674");
        assert_eq!(B64.encode(okm), "2QmMdYUjUYyDFy30djomNXdYGj0CloFPuTPIriLlicw=");
    }
}
