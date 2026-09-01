//! 响应构造：与请求同构的 MethodBuffer。

use crate::types::Param;

#[derive(Debug, Clone)]
pub struct Response {
    pub method: u32,
    pub params: Vec<Param>,
}

impl Response {
    pub fn new(method: u32) -> Self {
        Response { method, params: Vec::new() }
    }

    pub fn with(method: u32, params: Vec<Param>) -> Self {
        Response { method, params }
    }

    pub fn param(ptype: u32, data: Vec<u8>) -> Param {
        Param { ptype, data }
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut size = 12usize;
        for p in &self.params {
            size += 8 + p.data.len();
        }
        let mut out = Vec::with_capacity(size);
        out.extend_from_slice(&self.method.to_be_bytes());
        out.extend_from_slice(&0u32.to_be_bytes());
        out.extend_from_slice(&(self.params.len() as u32).to_be_bytes());
        for p in &self.params {
            out.extend_from_slice(&p.ptype.to_be_bytes());
            out.extend_from_slice(&(p.data.len() as u32).to_be_bytes());
            out.extend_from_slice(&p.data);
        }
        out
    }
}
