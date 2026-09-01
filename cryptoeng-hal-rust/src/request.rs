//! 请求解析：MethodBuffer {method u32 BE, clientUid u32 BE, paramCount u32 BE,
//!                        (paramType u32 BE, paramLen u32 BE, data)*}

use crate::types::{MethodType, Param};

#[derive(Debug, Clone)]
pub struct Request {
    pub method: MethodType,
    pub client_uid: u32,
    pub params: Vec<Param>,
}

impl Request {
    /// 解析 MethodBuffer（与 LSPosed 桥接 / 真机 TA 同构）。
    pub fn parse(buf: &[u8]) -> Result<Request, String> {
        if buf.len() < 12 {
            return Err(format!("request too short: {}", buf.len()));
        }
        let method = be32(buf, 0);
        let client_uid = be32(buf, 4);
        let count = be32(buf, 8) as usize;
        let mut off = 12usize;
        let mut params = Vec::with_capacity(count);
        for i in 0..count {
            if off + 8 > buf.len() {
                return Err(format!("param {} header truncated at {}", i, off));
            }
            let ptype = be32(buf, off);
            let plen = be32(buf, off + 4) as usize;
            off += 8;
            if off + plen > buf.len() {
                return Err(format!("param {} data truncated: len {} at {}", i, plen, off));
            }
            params.push(Param {
                ptype,
                data: buf[off..off + plen].to_vec(),
            });
            off += plen;
        }
        Ok(Request {
            method: MethodType::from(method),
            client_uid,
            params,
        })
    }

    pub fn param(&self, ptype: u32) -> Option<&Param> {
        self.params.iter().find(|p| p.ptype == ptype)
    }
}

pub(crate) fn be32(b: &[u8], off: usize) -> u32 {
    u32::from_be_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}
