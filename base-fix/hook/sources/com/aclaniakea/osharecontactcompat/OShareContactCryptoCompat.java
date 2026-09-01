package com.aclaniakea.osharecontactcompat;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps the OShare contact-mode HKDF salt aligned with a locally provisioned
 * CryptoEng compatibility record. Disabled unless the system property exists.
 */
public final class OShareContactCryptoCompat implements IXposedHookLoadPackage {
    private static final String TARGET = "com.heytap.accessory";
    private static final String PROP = "persist.sys.cryptoeng.contact_salt";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        try {
            Class<?> params = XposedHelpers.findClass("m.a", lpparam.classLoader);
            XposedHelpers.findAndHookMethod("rm.i", lpparam.classLoader, "i",
                    String.class, params, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            byte[] salt = configuredSalt();
                            if (salt == null || param.args[1] == null) return;
                            XposedHelpers.setObjectField(param.args[1], "f8815d", salt);
                        }
                    });
            XposedBridge.log("OShareContactCryptoCompat: hook installed");
        } catch (Throwable t) {
            XposedBridge.log("OShareContactCryptoCompat: hook failed");
            XposedBridge.log(t);
        }
    }

    private static byte[] configuredSalt() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String value = (String) XposedHelpers.callStaticMethod(properties, "get", PROP, "");
            if (value == null || value.length() != 8) return null;
            byte[] result = new byte[4];
            for (int i = 0; i < result.length; i++) {
                int hi = Character.digit(value.charAt(i * 2), 16);
                int lo = Character.digit(value.charAt(i * 2 + 1), 16);
                if (hi < 0 || lo < 0) return null;
                result[i] = (byte) ((hi << 4) | lo);
            }
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
