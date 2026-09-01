//! 存储：cryptoeng.key（主密钥）+ cryptoeng.dat（加密存储）+ findphone.dat。

use std::fs;
use std::path::PathBuf;

pub const DEFAULT_MASTER_KEY_PATH: &str = "/data/vendor_de/0/cryptoeng/cryptoeng.key";
pub const DEFAULT_DATA_PATH: &str = "/data/vendor_de/0/cryptoeng/cryptoeng.dat";
pub const DEFAULT_FINDPHONE_PATH: &str = "/mnt/vendor/persist/data/cryptoeng/findphone.dat";

#[derive(Debug, Clone)]
pub struct Storage {
    pub master_key_path: PathBuf,
    pub data_path: PathBuf,
    pub findphone_path: PathBuf,
    master_key: Option<Vec<u8>>,
    keys: std::collections::HashMap<String, Vec<u8>>,
    kv: std::collections::HashMap<String, String>,
}

impl Storage {
    pub fn open() -> Self {
        Self::open_with(
            PathBuf::from(DEFAULT_MASTER_KEY_PATH),
            PathBuf::from(DEFAULT_DATA_PATH),
            PathBuf::from(DEFAULT_FINDPHONE_PATH),
        )
    }

    pub fn open_with(master_key_path: PathBuf, data_path: PathBuf, findphone_path: PathBuf) -> Self {
        let master_key = fs::read(&master_key_path).ok();
        let mut s = Storage {
            master_key_path,
            data_path,
            findphone_path,
            master_key,
            keys: std::collections::HashMap::new(),
            kv: std::collections::HashMap::new(),
        };
        s.load_keys();
        s
    }

    /// 主存储密钥（cryptoeng.key，32 字节）。
    pub fn master_key(&self) -> Vec<u8> {
        self.master_key.clone().unwrap_or_default()
    }

    /// key_label -> 密钥。当前实现先查内存密钥表，回退主密钥。
    pub fn lookup_key(&self, label: &str) -> Option<Vec<u8>> {
        if let Some(k) = self.keys.get(label) {
            return Some(k.clone());
        }
        if self.master_key.is_some() {
            Some(self.master_key.clone().unwrap())
        } else {
            None
        }
    }

    pub fn set(&mut self, k: &str, v: String) {
        self.kv.insert(k.to_string(), v);
    }

    pub fn get(&self, k: &str) -> Option<String> {
        self.kv.get(k).cloned()
    }

    pub fn clear(&mut self) {
        self.kv.clear();
    }

    /// 从 cryptoeng.dat 尝试加载密钥表（后续逆向 serde 结构后完善）。
    fn load_keys(&mut self) {
        // TODO: 解析 CE3 格式的 cryptoeng.dat（AES 由 master_key 派生密钥加密）。
        // 先只加载主密钥，key_label 命中则回退主密钥。
    }
}
