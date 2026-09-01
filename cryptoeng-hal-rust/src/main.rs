//! CryptoEng HAL 服务入口：注册 vendor.oplus.hardware.cryptoeng.ICryptoeng/default。

use rsbinder::*;
use vendor_oplus_cryptoeng::service::CryptoengService;
use vendor_oplus_cryptoeng::vendor::oplus::hardware::cryptoeng::ICryptoeng::BnCryptoeng;

const SERVICE_NAME: &str = "vendor.oplus.hardware.cryptoeng.ICryptoeng/default";

fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let svc = CryptoengService::new();

    ProcessState::init_default()?;
    ProcessState::start_thread_pool();

    let binder = BnCryptoeng::new_binder(svc);
    hub::add_service(SERVICE_NAME, &binder)?;
    println!("Cryptoeng service registered as {SERVICE_NAME}");
    ProcessState::join_thread_pool()?;
    Ok(())
}
