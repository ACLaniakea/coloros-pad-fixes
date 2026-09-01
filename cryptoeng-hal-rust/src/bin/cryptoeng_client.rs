//! 测试客户端：通过 binder 调用 HAL 的 cryptoeng_invoke_command（10003）。

use rsbinder::*;
use vendor_oplus_cryptoeng::vendor::oplus::hardware::cryptoeng::ICryptoeng::ICryptoeng;

fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    let instance = args.get(1).map(String::as_str).unwrap_or("default");
    let method: u32 = args.get(2).map(String::as_str).unwrap_or("10003").parse()?;
    let service_name = format!("vendor.oplus.hardware.cryptoeng.ICryptoeng/{instance}");
    ProcessState::init_default()?;
    let svc: rsbinder::Strong<dyn ICryptoeng> = hub::wait_for_interface(&service_name)?;
    println!("service acquired");

    let json = "{\"only_key_label\":\"0\",\"key_label\":\"SMYijOgbT1JfVMug\",\"info\":\"QmVhY29u\",\"salt\":\"WZaNnw==\",\"okm_len\":\"32\",\"hash\":\"2\"}";
    let mut buf: Vec<u8> = Vec::new();
    buf.extend_from_slice(&method.to_be_bytes());
    buf.extend_from_slice(&10000u32.to_be_bytes());
    if method == 10003 {
        buf.extend_from_slice(&1u32.to_be_bytes());
        buf.extend_from_slice(&600u32.to_be_bytes());
        buf.extend_from_slice(&(json.len() as u32).to_be_bytes());
        buf.extend_from_slice(json.as_bytes());
    } else {
        buf.extend_from_slice(&0u32.to_be_bytes());
    }

    let resp = svc.cryptoeng_invoke_command(&buf)?;
    println!("RESP_OK len={}", resp.len());
    for b in &resp {
        print!("{:02x}", b);
    }
    println!();
    Ok(())
}
