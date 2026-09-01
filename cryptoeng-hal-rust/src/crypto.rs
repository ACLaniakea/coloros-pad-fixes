//! 密码学辅助：RSA / AES(ECB/CBC) / 随机 / FindPhone 配置解密（移植自桥接实现）。

use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use rand::RngCore;
use rsa::traits::PublicKeyParts;

use crate::request::Request;
use crate::storage::Storage;
use crate::types::Param;

/// 查找设备初始化公钥（RSA-1024，X.509 SPKI）。
pub const FIND_PHONE_INIT_PUBLIC_KEY: &str = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC2rR5Lb45wLLu+NXJyQSr1ueiQmf1qIebBtHBdfz5K/WDCM5s9JDvYLyKDyDrc6IxkrhdttbAzMg8cOqbPdIxUq8AfXLIRfLWIMCPkiQAXMdAxAJqoXqZIhgAbRbHS0Bzc/heX89r98+Qe7hIuFmEl8rpg1IlkKfS85ELUO3UMgQIDAQAB";

pub fn random_bytes(n: usize) -> Vec<u8> {
    let mut v = vec![0u8; n];
    rand::thread_rng().fill_bytes(&mut v);
    v
}

fn load_pubkey(b64: &str) -> Result<rsa::RsaPublicKey, String> {
    let der = B64.decode(b64).map_err(|e| format!("key b64: {e}"))?;
    rsa::pkcs8::DecodePublicKey::from_public_key_der(&der).map_err(|e| format!("key parse: {e}"))
}

/// RSA PKCS1 v1.5 多块加密。
pub fn rsa_encrypt(data: &[u8]) -> Result<Vec<u8>, String> {
    let pub_key = load_pubkey(FIND_PHONE_INIT_PUBLIC_KEY)?;
    let key_bytes = pub_key.n().to_bytes_be().len();
    let chunk = key_bytes - 11;
    let mut out = Vec::new();
    for c in data.chunks(chunk) {
        out.extend_from_slice(
            &pub_key
                .encrypt(&mut rsa::rand_core::OsRng, rsa::Pkcs1v15Encrypt, c)
                .map_err(|e| format!("rsa: {e}"))?,
        );
    }
    Ok(out)
}

/// RSA-1024 PKCS1 单块（2010 固定）。
pub fn rsa_raw_single(data: &[u8]) -> Result<Vec<u8>, String> {
    let pub_key = load_pubkey(FIND_PHONE_INIT_PUBLIC_KEY)?;
    let key_bytes = pub_key.n().to_bytes_be().len();
    if data.len() > key_bytes - 11 {
        return Err("rsa single-block too long".into());
    }
    pub_key
        .encrypt(&mut rsa::rand_core::OsRng, rsa::Pkcs1v15Encrypt, data)
        .map_err(|e| format!("rsa: {e}"))
}

pub fn rsa_raw(data: &[u8]) -> Result<Vec<u8>, String> {
    rsa_encrypt(data)
}

fn unpad_pkcs5(mut out: Vec<u8>) -> Vec<u8> {
    if let Some(&last) = out.last() {
        let pad = last as usize;
        if pad > 0 && pad <= 16 && out.len() >= pad {
            out.truncate(out.len() - pad);
        }
    }
    out
}

/// AES/ECB/PKCS5。
pub fn aes_encrypt(plain: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    use aes::cipher::{BlockEncrypt, KeyInit};
    use aes::Aes128;
    let cipher = Aes128::new_from_slice(&key[..16.min(key.len())]).map_err(|e| format!("aes: {e}"))?;
    let block_size = 16;
    let mut padded = plain.to_vec();
    let pad = block_size - (padded.len() % block_size);
    padded.extend(std::iter::repeat(pad as u8).take(pad));
    let mut out = vec![0u8; padded.len()];
    for (i, block) in padded.chunks(block_size).enumerate() {
        let mut b = [0u8; 16];
        b.copy_from_slice(block);
        cipher.encrypt_block((&mut b).into());
        out[i * block_size..(i + 1) * block_size].copy_from_slice(&b);
    }
    Ok(out)
}

pub fn aes_decrypt(ct: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    use aes::cipher::{BlockDecrypt, KeyInit};
    use aes::Aes128;
    let cipher = Aes128::new_from_slice(&key[..16.min(key.len())]).map_err(|e| format!("aes: {e}"))?;
    let mut out = vec![0u8; ct.len()];
    for (i, block) in ct.chunks(16).enumerate() {
        let mut b = [0u8; 16];
        b.copy_from_slice(block);
        cipher.decrypt_block((&mut b).into());
        out[i * 16..(i + 1) * 16].copy_from_slice(&b);
    }
    Ok(unpad_pkcs5(out))
}

/// 手写 AES-CBC 解密（PKCS5 去填充）。
fn cbc_decrypt(raw: &[u8], key: &[u8], iv: &[u8; 16]) -> Result<Vec<u8>, String> {
    use aes::cipher::{BlockDecrypt, KeyInit};
    use aes::Aes128;
    if raw.len() % 16 != 0 || raw.is_empty() {
        return Err("bad cbc length".into());
    }
    let cipher = Aes128::new_from_slice(&key[..16.min(key.len())]).map_err(|e| format!("aes: {e}"))?;
    let mut prev = *iv;
    let mut out = Vec::with_capacity(raw.len());
    for block in raw.chunks(16) {
        let mut b = [0u8; 16];
        b.copy_from_slice(block);
        cipher.decrypt_block((&mut b).into());
        for j in 0..16 {
            out.push(b[j] ^ prev[j]);
        }
        prev.copy_from_slice(block);
    }
    Ok(unpad_pkcs5(out))
}

/// AES/CBC/PKCS5 零 IV。
fn aes_decrypt_cbc_zero(raw: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    cbc_decrypt(raw, key, &[0u8; 16])
}

/// AES/CBC/PKCS5 前 16B 为 IV。
fn aes_decrypt_cbc_iv_prefix(raw: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    if raw.len() <= 16 || (raw.len() - 16) % 16 != 0 {
        return Err("bad iv-prefix length".into());
    }
    let (iv, body) = raw.split_at(16);
    let mut ivb = [0u8; 16];
    ivb.copy_from_slice(iv);
    cbc_decrypt(body, key, &ivb)
}

/// 三种模式尝试解密，只接受 JSON 或纯数字结果。
fn decrypt_aes_text(raw: &[u8], key: &[u8]) -> Option<String> {
    let mut candidates: Vec<Vec<u8>> = Vec::new();
    if let Ok(v) = aes_decrypt(raw, key) {
        candidates.push(v);
    }
    if let Ok(v) = aes_decrypt_cbc_zero(raw, key) {
        candidates.push(v);
    }
    if let Ok(v) = aes_decrypt_cbc_iv_prefix(raw, key) {
        candidates.push(v);
    }
    for c in candidates {
        let text = String::from_utf8_lossy(&c).trim().to_string();
        let looks_ok = (text.starts_with('{') && text.ends_with('}'))
            || (text.len() >= 3 && text.chars().all(|ch| ch.is_ascii_digit()));
        if looks_ok {
            return Some(text);
        }
    }
    None
}

/// message_key 处理：Base64 文本 ASCII 24B（AES-192）优先；hex 32 位兼容；否则解码。
pub fn current_aes_key(store: &Storage) -> Vec<u8> {
    let k = store.get("message_key").unwrap_or_default();
    if k.is_empty() {
        return crate::util::aes_string_key(16).into_bytes();
    }
    // message_key 现为 16 字符集串：其 16 个 ASCII 字节即 AES-128 密钥。
    if k.len() == 16 {
        return k.into_bytes();
    }
    // 兼容旧格式：24B base64 文本（AES-192）/ 32 位 hex / base64。
    if k.len() == 24 && k.ends_with("==") {
        return k.into_bytes();
    }
    if k.len() == 32 && k.chars().all(|c| c.is_ascii_hexdigit()) {
        let mut out = Vec::with_capacity(16);
        for i in 0..16 {
            out.push(u8::from_str_radix(&k[i * 2..i * 2 + 2], 16).unwrap_or(0));
        }
        return out;
    }
    B64.decode(&k).unwrap_or_default()
}

/// 2011 配置解密（tmp_aes 双候选 + 三模式）。
pub fn decrypt_config(b64: &str, store: &Storage) -> Option<String> {
    let key_b64 = store.get("tmp_aes")?;
    if key_b64.is_empty() {
        return None;
    }
    let raw = B64.decode(b64).ok()?;
    let mut keys: Vec<Vec<u8>> = Vec::new();
    keys.push(key_b64.as_bytes().to_vec());
    if let Ok(k) = B64.decode(&key_b64) {
        keys.push(k);
    }
    for key in &keys {
        if let Some(text) = decrypt_aes_text(&raw, key) {
            if text.starts_with('{') && text.ends_with('}') {
                return Some(text);
            }
        }
    }
    None
}

/// 从服务器配置 JSON 提取 publicKey 并存储。
pub fn store_config_key(body: &str, store: &mut Storage) {
    let Ok(v) = serde_json::from_str::<serde_json::Value>(body) else {
        return;
    };
    let mut root = v;
    if let Some(d) = root.get("data") {
        root = d.clone();
    }
    if let Some(pk) = root.get("publicKey") {
        let modulus = pk.get("modulus").and_then(|x| x.as_str()).unwrap_or("").to_string();
        let data = pk.get("data").and_then(|x| x.as_str()).unwrap_or("").to_string();
        let version = pk.get("version").and_then(|x| x.as_str()).unwrap_or("").to_string();
        let pub_key = if !data.is_empty() { data } else { modulus };
        store.set("current_public_key", pub_key);
        store.set("current_key_version", version);
    } else if let Some(pk) = root.get("public_key").and_then(|x| x.as_str()) {
        store.set("current_public_key", pk.to_string());
    }
}

/// 2003 注册回包数据持久化。
fn store_registration_data(root: &serde_json::Value, store: &mut Storage) {
    let mut data = root.clone();
    if let Some(d) = root.get("data") {
        data = d.clone();
    }
    let take = |key: &str| -> String {
        data.get(key).and_then(|x| x.as_str()).unwrap_or("").to_string()
    };
    let account_name = take("accountName");
    let ssoid = take("ssoid");
    let unique_id = take("uniqueId");
    let device_id = take("deviceId");
    let message_key = {
        let a = take("aesKey");
        if a.is_empty() { take("messageKey") } else { a }
    };
    if !account_name.is_empty() { store.set("account_name", account_name); }
    if !ssoid.is_empty() { store.set("ssoid", ssoid); }
    if !unique_id.is_empty() { store.set("unique_id", unique_id); }
    if !device_id.is_empty() { store.set("device_id", device_id); }
    if !message_key.is_empty() { store.set("message_key", message_key); }
    let lockdead = data.get("lockdeadState").and_then(|x| x.as_i64()).unwrap_or(1);
    store.set("lock_dead_state", lockdead.to_string());
    store.set("registration_complete", "1".to_string());
}

/// 2003/2005 响应解密。
pub fn decrypt_response(method: u32, req: &Request, store: &mut Storage) -> Result<Vec<Param>, String> {
    let ct = req
        .param(20)
        .map(|p| p.as_str())
        .unwrap_or_default();
    let use_tmp_aes = req.param(31).map(|p| p.as_str() == "1").unwrap_or(false);
    let mut keys: Vec<Vec<u8>> = Vec::new();
    if use_tmp_aes {
        let tmp = store.get("tmp_aes").unwrap_or_default();
        if !tmp.is_empty() {
            keys.push(tmp.as_bytes().to_vec());
            if let Ok(k) = B64.decode(&tmp) {
                keys.push(k);
            }
        } else {
            keys.push(current_aes_key(store));
        }
    } else {
        keys.push(current_aes_key(store));
    }
    let raw = B64.decode(&ct).unwrap_or_default();
    for key in &keys {
        if let Some(text) = decrypt_aes_text(&raw, key) {
            let mut result_code = "200".to_string();
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                result_code = v
                    .get("code")
                    .and_then(|x| x.as_str())
                    .unwrap_or("200")
                    .to_string();
                if method == 2003 && result_code == "200" {
                    store_registration_data(&v, store);
                }
            } else if text.len() >= 3 && text.chars().all(|c| c.is_ascii_digit()) {
                result_code = text;
            }
            return Ok(vec![Param { ptype: 27, data: result_code.into_bytes() }]);
        }
    }
    Ok(vec![Param { ptype: 27, data: b"500".to_vec() }])
}

/// 应用自带默认配置（暂返回最小结构）。
pub fn bundled_config() -> String {
    r#"{"code":"200","codeMsg":"Ok","data":{}}"#.to_string()
}
