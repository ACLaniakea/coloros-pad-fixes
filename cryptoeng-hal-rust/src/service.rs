//! 服务层：接收 MethodBuffer 并分发（共享 Storage，状态跨请求持久）。

use std::sync::Mutex;

use rsbinder::Interface;

use crate::handlers;
use crate::request::Request;
use crate::storage::Storage;

pub struct CryptoengService {
    pub storage: Mutex<Storage>,
}

impl CryptoengService {
    pub fn new() -> Self {
        CryptoengService {
            storage: Mutex::new(Storage::open()),
        }
    }

    fn log_line(&self, msg: &str) {
        use std::io::Write;
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open("/data/local/tmp/ce_hal.log")
        {
            let _ = writeln!(f, "[{}] {}", std::process::id(), msg);
        }
    }

    /// 处理原始 MethodBuffer，返回响应 MethodBuffer。
    pub fn invoke_command(&self, buf: &[u8]) -> Result<Vec<u8>, String> {
        match Request::parse(buf) {
            Ok(req) => {
                self.log_line(&format!(
                    "REQ method={} uid={} nparams={}",
                    req.method.code(),
                    req.client_uid,
                    req.params.len()
                ));
                let mut storage = match self.storage.lock() {
                    Ok(s) => s,
                    Err(_) => return Err("storage lock poisoned".into()),
                };
                let resp = handlers::dispatch(&req, &mut storage);
                let bytes = resp.to_bytes();
                self.log_line(&format!("RESP method={} len={}", req.method.code(), bytes.len()));
                Ok(bytes)
            }
            Err(e) => {
                self.log_line(&format!("PARSE_ERR {e}"));
                Err(e)
            }
        }
    }
}

impl Interface for CryptoengService {}

impl crate::vendor::oplus::hardware::cryptoeng::ICryptoeng::ICryptoeng for CryptoengService {
    fn cryptoeng_invoke_command(&self, buffer: &[u8]) -> rsbinder::BinderResult<Vec<u8>> {
        self.invoke_command(buffer)
            .map_err(|_| rsbinder::StatusCode::Unknown.into())
    }
}
