//! CryptoEng split proxy: implement PKI/HKDF 10003 locally and preserve the
//! proven software HAL for every other command.

use std::process::Command;
use std::sync::Mutex;
use sha2::{Digest, Sha256};

#[cfg(target_os = "android")]
use std::os::unix::process::CommandExt;

use rsbinder::{hub, Interface, ProcessState, Strong};
use vendor_oplus_cryptoeng::handlers::pki;
use vendor_oplus_cryptoeng::request::Request;
use vendor_oplus_cryptoeng::response::Response;
use vendor_oplus_cryptoeng::storage::Storage;
use vendor_oplus_cryptoeng::types::MethodType;
use vendor_oplus_cryptoeng::vendor::oplus::hardware::cryptoeng::ICryptoeng::{
    BnCryptoeng, ICryptoeng,
};

const PUBLIC_SERVICE: &str = "vendor.oplus.hardware.cryptoeng.ICryptoeng/default";
const BACKING_SERVICE: &str = "vendor.oplus.hardware.cryptoeng.ICryptoeng/backing";
const BACKING_BINARY: &str = "/mnt/ce_hal/ce_backing";
// Temporary diagnostic pin for the official peer certificate captured on the
// test device. This is deliberately exact-match only; it must be replaced by
// OPlus Service CA E1 chain verification before release.
const DIAGNOSTIC_CERT_SHA256: [u8; 32] = [
    0x03, 0x21, 0x76, 0xd1, 0xd2, 0x4f, 0x3f, 0x41,
    0x98, 0x07, 0xc0, 0xbc, 0xbb, 0xe7, 0xe8, 0x5d,
    0x2a, 0x77, 0x21, 0xa5, 0x35, 0xf7, 0x02, 0x23,
    0xb7, 0xdb, 0x01, 0x29, 0x83, 0x3f, 0x8b, 0xa7,
];

/// OMK/KMS uses a distinct, big-endian TA framing rather than the public
/// CryptoEng MethodBuffer framing:
///   cmd_id | payload_len | parameter_count | (type | len | value)*
///
/// Keep this recogniser deliberately narrow.  A KMS packet is not a public
/// MethodBuffer even when its first word happens to look like a command id.
fn kms_command_id(buffer: &[u8]) -> Option<u32> {
    if buffer.len() < 12 {
        return None;
    }
    let command = u32::from_be_bytes(buffer[0..4].try_into().ok()?);
    // OMK command IDs currently occupy the 0x320 range.  This prevents
    // ordinary CryptoEng MethodBuffers from being misclassified.
    if !(0x320..=0x3ff).contains(&command) {
        return None;
    }
    let payload_len = u32::from_be_bytes(buffer[4..8].try_into().ok()?) as usize;
    let parameter_count = u32::from_be_bytes(buffer[8..12].try_into().ok()?) as usize;
    if payload_len != buffer.len().checked_sub(12)? || parameter_count > 64 {
        return None;
    }
    Some(command)
}

/// Build a successful OMK/KMS TA response.  The result layout is the same
/// as the request framing, prefixed by a zero TA status word.
fn kms_success(command: u32, parameters: &[(u32, &[u8])]) -> Vec<u8> {
    let payload_len: usize = parameters.iter().map(|(_, value)| 8 + value.len()).sum();
    let mut out = Vec::with_capacity(12 + payload_len);
    out.extend_from_slice(&command.to_be_bytes());
    out.extend_from_slice(&0u32.to_be_bytes());
    out.extend_from_slice(&(parameters.len() as u32).to_be_bytes());
    for (kind, value) in parameters {
        out.extend_from_slice(&kind.to_be_bytes());
        out.extend_from_slice(&(value.len() as u32).to_be_bytes());
        out.extend_from_slice(value);
    }
    out
}

fn log_event(message: &str) {
    use std::io::Write;
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open("/data/local/tmp/ce_proxy.log")
    {
        let _ = writeln!(file, "[{}] {message}", std::process::id());
    }
}

struct ProxyService {
    backing: Strong<dyn ICryptoeng>,
    storage: Mutex<Storage>,
    cert_cache: Mutex<std::collections::HashSet<Vec<u8>>>,
}

impl Interface for ProxyService {}

impl ICryptoeng for ProxyService {
    fn cryptoeng_invoke_command(&self, buffer: &[u8]) -> rsbinder::BinderResult<Vec<u8>> {
        // 0x321 is the stock OMK "is TEE purpose supported" probe.  It is a
        // capability query only: no key material is created, imported, or
        // exposed here.  The port has the KMS client and lock-screen binding,
        // but its compatible software backend omitted this TA probe entirely.
        // Reply in the stock KMS framing so the client can proceed to its
        // subsequent authenticated operations, which remain delegated until
        // each protocol is independently implemented.
        if kms_command_id(buffer) == Some(0x321) {
            const PURPOSE_TEE_SUPPORTED: u32 = 0x2d4;
            const TRUE_BE: [u8; 4] = 1u32.to_be_bytes();
            log_event("OMK KMS capability probe 0x321 served");
            return Ok(kms_success(0x321, &[(PURPOSE_TEE_SUPPORTED, &TRUE_BE)]));
        }

        // 0x337 is the stock OMK TA-availability probe issued immediately
        // before SRP derives its lock-screen-bound verifier.  The official
        // client reads boolean parameter 0x2d4 (724); no key material or SRP
        // value is exchanged by this probe.  The shared software backend
        // returns an empty buffer for it, which aborts the whole registration
        // before the later authenticated operation can be dispatched.
        if kms_command_id(buffer) == Some(0x337) {
            const TA_AVAILABLE: u32 = 0x2d4;
            const TRUE_BE: [u8; 4] = 1u32.to_be_bytes();
            log_event("OMK KMS TA availability probe 0x337 served");
            return Ok(kms_success(0x337, &[(TA_AVAILABLE, &TRUE_BE)]));
        }

        let request = match Request::parse(buffer) {
            Ok(request) => request,
            Err(_) => return self.backing.cryptoeng_invoke_command(buffer),
        };

        if request.method.code() == 10009 {
            if let Some(cert) = request.param(600) {
                // 证书固定（ovi-mini-program）：SHA-256 缓存验证结果，避免重复 ECDSA 验签。
                let digest = Sha256::digest(&cert.data);
                let digest_bytes = digest.as_slice().to_vec();
                let cached = self
                    .cert_cache
                    .lock()
                    .map(|c| c.contains(&digest_bytes))
                    .unwrap_or(false);
                if cached {
                    return Ok(Response::new(10009).to_bytes());
                }
                if vendor_oplus_cryptoeng::cert::verify_10009_cert(&cert.data) {
                    if let Ok(mut c) = self.cert_cache.lock() {
                        c.insert(digest_bytes);
                    }
                    return Ok(Response::new(10009).to_bytes());
                }
                log_event("10009 certificate verification failed; forwarding");
            }
            return self.backing.cryptoeng_invoke_command(buffer);
        }

        if request.method != MethodType::PkiHkdf {
            return self.backing.cryptoeng_invoke_command(buffer);
        }

        // 10003：成功路径不写文件日志（避免高频 I/O），仅失败记录。
        let mut storage = match self.storage.lock() {
            Ok(s) => s,
            Err(_) => return Err(rsbinder::StatusCode::Unknown.into()),
        };
        let params = match pki::handle_pki_hkdf(&request, &mut storage) {
            Ok(params) => params,
            Err(error) => {
                log_event(&format!("10003 failed: {error}"));
                return Err(rsbinder::StatusCode::BadValue.into());
            }
        };
        Ok(Response::with(MethodType::PkiHkdf.code(), params).to_bytes())
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    ProcessState::init_default()?;
    ProcessState::start_thread_pool();

    // The backing binary is the byte-for-byte shared HAL already proven with
    // Find Device. Its only patch changes the registered instance name.
    let mut command = Command::new(BACKING_BINARY);
    #[cfg(target_os = "android")]
    unsafe {
        command.pre_exec(|| {
            if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM) != 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let mut child = command.spawn()?;
    let backing: Strong<dyn ICryptoeng> = match hub::wait_for_interface(BACKING_SERVICE) {
        Ok(service) => service,
        Err(error) => {
            let _ = child.kill();
            return Err(error.into());
        }
    };

    let proxy = ProxyService {
        backing,
        storage: Mutex::new(Storage::open()),
        cert_cache: Mutex::new(std::collections::HashSet::new()),
    };
    let binder = BnCryptoeng::new_binder(proxy);
    hub::add_service(PUBLIC_SERVICE, &binder)?;
    println!("CryptoEng proxy registered; backing pid={}", child.id());
    ProcessState::join_thread_pool()?;
    Ok(())
}
