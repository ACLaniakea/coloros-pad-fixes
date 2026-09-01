//! 命令分发：compat(FindPhone) / pms / native / pki。

pub mod compat;
pub mod pki;

use crate::request::Request;
use crate::response::Response;
use crate::storage::Storage;
use crate::types::MethodType;

/// 分发请求。10003 走 pki；2001-2022 走 compat；其余暂未实现。
/// storage 为共享可变引用，保证 2013 写入的字段在 2014 等命令中可见。
pub fn dispatch(req: &Request, storage: &mut Storage) -> Response {
    let method_code = req.method.code();
    match req.method {
        MethodType::PkiHkdf => match pki::handle_pki_hkdf(req, storage) {
            Ok(params) => Response::with(method_code, params),
            Err(e) => {
                log::warn!("pki hkdf error: {e}");
                Response::new(method_code)
            }
        },
        MethodType::Unknown(_) => {
            log::warn!("unknown method {method_code}");
            Response::new(method_code)
        }
        _ if (2001..=2022).contains(&method_code) => match compat::handle(req, storage) {
            Ok(params) => Response::with(method_code, params),
            Err(e) => {
                log::warn!("compat error: {e}");
                Response::new(method_code)
            }
        },
        _ => {
            log::warn!("method {method_code} not yet implemented");
            Response::new(method_code)
        }
    }
}
