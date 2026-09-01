//! 命令类型与方法号。

/// FindPhone / PKI / PMS 命令号（与真机 cryptoeng TA / 软实现一致）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MethodType {
    FindphoneEncodeByPublicKey,          // 2001
    FindphoneGetAllRegisterInfo,         // 2002
    FindphoneDecryptRegisterResult,      // 2003
    FindphoneEncryptByPublicKey,         // 2004
    FindphoneUnregisterAndClearData,     // 2005
    FindphoneDecodeAndCheckInstruction,  // 2006
    FindphoneClearLockDeadByKeyguardToken, // 2007
    FindphoneClearLockDeadByBootReg,     // 2008
    FindphoneEncodeByAes,                // 2009
    FindphoneGetEncryptedTmpAes,         // 2010
    FindphoneUpdatePublicKey,            // 2011
    FindphoneMoveDataToRpmb,             // 2013
    FindphoneGetMemberFromRpmb,          // 2014
    FindphoneResetRpmb,                  // 2015
    FindphoneGetAllRpmbInfo,             // 2016
    FindphoneAppendTmpAesAndEncrypt,     // 2017
    FindphoneIsSupportUnlockByPasswd,    // 2018
    FindphoneSetPasswdType,              // 2019
    FindphoneGetPasswdType,              // 2020
    FindphoneVerifyPasswd,               // 2021
    FindphoneGetSalt,                    // 2022
    PkiHkdf,                             // 10003（一加互传联系人模式 Beacon 派生）
    PmsEnroll,
    PmsVerify,
    PmsModify,
    PmsDelete,
    PmsGetInfo,
    PmsRequestEncryptData,
    PmsResetAllData,
    PmsSaveSp,
    PmsGetSp,
    Unknown(u32),
}

impl From<u32> for MethodType {
    fn from(v: u32) -> Self {
        use MethodType::*;
        match v {
            2001 => FindphoneEncodeByPublicKey,
            2002 => FindphoneGetAllRegisterInfo,
            2003 => FindphoneDecryptRegisterResult,
            2004 => FindphoneEncryptByPublicKey,
            2005 => FindphoneUnregisterAndClearData,
            2006 => FindphoneDecodeAndCheckInstruction,
            2007 => FindphoneClearLockDeadByKeyguardToken,
            2008 => FindphoneClearLockDeadByBootReg,
            2009 => FindphoneEncodeByAes,
            2010 => FindphoneGetEncryptedTmpAes,
            2011 => FindphoneUpdatePublicKey,
            2013 => FindphoneMoveDataToRpmb,
            2014 => FindphoneGetMemberFromRpmb,
            2015 => FindphoneResetRpmb,
            2016 => FindphoneGetAllRpmbInfo,
            2017 => FindphoneAppendTmpAesAndEncrypt,
            2018 => FindphoneIsSupportUnlockByPasswd,
            2019 => FindphoneSetPasswdType,
            2020 => FindphoneGetPasswdType,
            2021 => FindphoneVerifyPasswd,
            2022 => FindphoneGetSalt,
            10003 => PkiHkdf,
            other => Unknown(other),
        }
    }
}

impl MethodType {
    pub fn code(self) -> u32 {
        use MethodType::*;
        match self {
            FindphoneEncodeByPublicKey => 2001,
            FindphoneGetAllRegisterInfo => 2002,
            FindphoneDecryptRegisterResult => 2003,
            FindphoneEncryptByPublicKey => 2004,
            FindphoneUnregisterAndClearData => 2005,
            FindphoneDecodeAndCheckInstruction => 2006,
            FindphoneClearLockDeadByKeyguardToken => 2007,
            FindphoneClearLockDeadByBootReg => 2008,
            FindphoneEncodeByAes => 2009,
            FindphoneGetEncryptedTmpAes => 2010,
            FindphoneUpdatePublicKey => 2011,
            FindphoneMoveDataToRpmb => 2013,
            FindphoneGetMemberFromRpmb => 2014,
            FindphoneResetRpmb => 2015,
            FindphoneGetAllRpmbInfo => 2016,
            FindphoneAppendTmpAesAndEncrypt => 2017,
            FindphoneIsSupportUnlockByPasswd => 2018,
            FindphoneSetPasswdType => 2019,
            FindphoneGetPasswdType => 2020,
            FindphoneVerifyPasswd => 2021,
            FindphoneGetSalt => 2022,
            PkiHkdf => 10003,
            Unknown(v) => v,
            _ => 0,
        }
    }
}

/// 请求参数（与真机 MethodBuffer 的 paramType 一致）。
#[derive(Debug, Clone)]
pub struct Param {
    pub ptype: u32,
    pub data: Vec<u8>,
}

impl Param {
    pub fn as_str(&self) -> String {
        String::from_utf8_lossy(&self.data).into_owned()
    }
}
