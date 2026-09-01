//! 与原厂一致的 ICryptoeng AIDL（version 1 / hash 765136eb...）
package vendor.oplus.hardware.cryptoeng;

interface ICryptoeng {
    byte[] cryptoeng_invoke_command(in byte[] buffer);
}

