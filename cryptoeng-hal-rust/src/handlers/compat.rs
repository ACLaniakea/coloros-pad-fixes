//! compat handler：FindPhone 2001-2022 协议（从 LSPosed 桥接 ColorOsCryptoEngBridge 移植）。

use base64::{engine::general_purpose::STANDARD as B64, Engine as _};

use crate::request::Request;
use crate::storage::Storage;
use crate::types::Param;

fn param_str(req: &Request, ptype: u32) -> String {
    req.param(ptype).map(|p| p.as_str()).unwrap_or_default()
}

fn ok(method: u32) -> Vec<Param> {
    let _ = method;
    vec![Param { ptype: 27, data: b"200".to_vec() }]
}

fn json_esc(s: &str) -> String {
    s.replace("\\", "\\\\").replace("\"", "\\\"")
}

pub fn handle(req: &Request, store: &mut Storage) -> Result<Vec<Param>, String> {
    let method = req.method.code();
    // 初始化稳定身份（device_id/unique_id/imei 等），供 2010/2014 使用。
    crate::util::ensure_identity(store);
    match method {
        2001 | 2004 => {
            let text = param_str(req, 8) + &param_str(req, 21);
            let ct = crate::crypto::rsa_raw(text.as_bytes())?;
            Ok(vec![Param { ptype: 20, data: ct }])
        }
        2002 => {
            let account_name = param_str(req, 11);
            let device_id = param_str(req, 4);
            let token = param_str(req, 12);
            let mobile_name = param_str(req, 14);
            let ticket = param_str(req, 15);
            let protocol_version = param_str(req, 1);
            let phone_card = param_str(req, 13);
            // 软件 HAL：message_key = 16 字符集串（与 TA crypto_eng_aes_string_key_generate 一致），
            // 这 16 个 ASCII 字节即 AES-128 密钥，服务器按文本当密钥。
            let message_key = crate::util::aes_string_key(16);
            store.set("message_key", message_key.clone());
            let json = format!(
                "{{\"accountName\":\"{}\",\"deviceId\":\"{}\",\"token\":\"{}\",\"mobileName\":\"{}\",\"ticket\":\"{}\",\"protocolVersion\":\"{}\",\"phoneCard\":\"{}\",\"messageKey\":\"{}\"}}",
                json_esc(&account_name), json_esc(&device_id), json_esc(&token),
                json_esc(&mobile_name), json_esc(&ticket), json_esc(&protocol_version),
                json_esc(&phone_card), message_key
            );
            store.set("register_json", json.clone());
            let ct = crate::crypto::rsa_encrypt(json.as_bytes())?;
            store.set("last_cipher", B64.encode(&ct));
            Ok(vec![Param { ptype: 20, data: ct }])
        }
        2003 | 2005 => crate::crypto::decrypt_response(method, req, store),
        2006 | 2015 => {
            store.clear();
            Ok(ok(method))
        }
        2007 => {
            let body = param_str(req, 19) + &param_str(req, 22);
            store.set("last_instruction", body.clone());
            Ok(vec![
                Param { ptype: 27, data: b"200".to_vec() },
                Param { ptype: 21, data: body.into_bytes() },
            ])
        }
        2008 | 2009 => Ok(ok(method)),
        2010 => {
            // tmp_aes = 16 字符集串，原样发送并存储；其 16 字节即 AES-128 密钥。
            let tmp_aes = crate::util::aes_string_key(16);
            store.set("tmp_aes", tmp_aes.clone());
            let imei = store.get("imei").unwrap_or_default();
            let unique_id = store.get("unique_id").unwrap_or_else(|| "860000000000000".into());
            let payload = format!(
                "{{\"imei\":\"{}\",\"tmpAes\":\"{}\",\"uniqueId\":\"{}\"}}",
                json_esc(&imei), tmp_aes, json_esc(&unique_id)
            );
            store.set("last_tmp_aes_payload", payload.clone());
            let ct = crate::crypto::rsa_raw_single(payload.as_bytes())?;
            Ok(vec![Param { ptype: 20, data: ct }])
        }
        2011 => {
            let ct = param_str(req, 20);
            store.set("last_public_key_update", ct.clone());
            match crate::crypto::decrypt_config(&ct, store) {
                Some(d) => {
                    crate::crypto::store_config_key(&d, store);
                    Ok(vec![
                        Param { ptype: 27, data: b"200".to_vec() },
                        Param { ptype: 21, data: d.into_bytes() },
                    ])
                }
                None => {
                    let local = crate::crypto::bundled_config();
                    Ok(vec![
                        Param { ptype: 27, data: b"200".to_vec() },
                        Param { ptype: 21, data: local.into_bytes() },
                    ])
                }
            }
        }
        2012 => {
            let text = param_str(req, 8) + &param_str(req, 21);
            let key = crate::crypto::current_aes_key(store);
            let out = crate::crypto::aes_encrypt(text.as_bytes(), &key)?;
            Ok(vec![Param { ptype: 20, data: B64.encode(out).into_bytes() }])
        }
        2013 => {
            for p in &req.params {
                let value = String::from_utf8_lossy(&p.data).into_owned();
                match p.ptype {
                    4 => store.set("device_id", value),
                    5 => store.set("ssoid", value),
                    6 => store.set("unique_id", value),
                    11 => store.set("account_name", value),
                    16 => store.set("message_key", value),
                    29 => store.set("rsa_version", value),
                    _ => store.set(&format!("param_{}", p.ptype), value),
                }
            }
            Ok(ok(method))
        }
        2014 => {
            let field_type = req.params.first().map(|p| p.ptype).unwrap_or(28);
            let mut val = match field_type {
                5 => store.get("ssoid").unwrap_or_default(),
                4 => store.get("device_id").unwrap_or_else(|| "0db683cee188696671337eff1d4ee7922fa28b26923455503758dafe3ca19c58".into()),
                6 => store.get("unique_id").unwrap_or_default(),
                11 => store.get("account_name").unwrap_or_default(),
                28 => store.get("update_data").unwrap_or_else(|| "1".into()),
                26 => store.get("lock_dead_state").unwrap_or_default(),
                33 => store.get("pwd_type").unwrap_or_default(),
                37 => store.get("salt").unwrap_or_default(),
                29 => store.get("rsa_version").unwrap_or_else(|| "0".into()),
                _ => store.get(&format!("rpmb_{}", field_type)).unwrap_or_default(),
            };
            if val.is_empty() {
                val = if field_type == 4 || field_type == 6 { "860000000000000".into() } else { "0".into() };
            }
            let int_field = matches!(field_type, 28 | 26 | 33 | 32 | 34 | 36);
            let data = if int_field {
                let iv: i32 = val.trim().parse().unwrap_or(0);
                iv.to_be_bytes().to_vec()
            } else {
                val.into_bytes()
            };
            Ok(vec![Param { ptype: field_type, data }])
        }
        2016 => {
            let nz = |s: String, fb: &str| if s.is_empty() { fb.to_string() } else { s };
            Ok(vec![
                Param { ptype: 6, data: nz(store.get("unique_id").unwrap_or_default(), "860000000000000").into_bytes() },
                Param { ptype: 4, data: nz(store.get("device_id").unwrap_or_default(), "0db683cee188696671337eff1d4ee7922fa28b26923455503758dafe3ca19c58").into_bytes() },
                Param { ptype: 5, data: nz(store.get("ssoid").unwrap_or_default(), "0").into_bytes() },
                Param { ptype: 11, data: nz(store.get("account_name").unwrap_or_default(), "0").into_bytes() },
                Param { ptype: 29, data: nz(store.get("rsa_version").unwrap_or_default(), "0").into_bytes() },
            ])
        }
        2017 => {
            let tmp_s = store.get("tmp_aes").unwrap_or_default();
            let tmp = if tmp_s.is_empty() {
                crate::util::aes_string_key(16).into_bytes()
            } else {
                tmp_s.into_bytes()
            };
            let text = param_str(req, 8) + &param_str(req, 21);
            let mut payload = tmp;
            payload.extend_from_slice(text.as_bytes());
            let ct = crate::crypto::rsa_raw(&payload)?;
            Ok(vec![Param { ptype: 20, data: ct }])
        }
        2018 => Ok(vec![Param { ptype: 32, data: b"1".to_vec() }]),
        2019 => Ok(vec![Param { ptype: 33, data: b"0".to_vec() }]),
        2020 => Ok(vec![Param { ptype: 33, data: store.get("pwd_type").unwrap_or_default().into_bytes() }]),
        2021 => Ok(vec![Param { ptype: 34, data: b"1".to_vec() }]),
        2022 => {
            let salt = if store.get("salt").unwrap_or_default().is_empty() {
                let s = B64.encode(crate::crypto::random_bytes(16));
                store.set("salt", s.clone());
                s
            } else {
                store.get("salt").unwrap_or_default()
            };
            Ok(vec![Param { ptype: 37, data: salt.into_bytes() }])
        }
        _ => Err(format!("compat: unknown method {method}")),
    }
}
