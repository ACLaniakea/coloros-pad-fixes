package com.aclaniakea.stdidcompat;

import com.aclaniakea.devicegate.DeviceGate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Compatibility fallback for the port's absent Oplus StdID APK.
 *
 * <p>On a normal Oplus device, StdIDSDK receives the global GUID from
 * system_server. The transplanted framework's native IDHelper instead depends
 * on an Oplus PCBA backend that does not exist on this Lenovo tablet and
 * returns an empty GUID. OMK/SRP rejects that empty value before it can create
 * the Password Book key. Prefer repairing the system-side source of that
 * value, so every original StdIDSDK client receives the same persisted GUID.
 * The normal whitelist, account, KMS and SRP paths stay untouched.</p>
 */
public final class StdIdGuidCompat implements IXposedHookLoadPackage {
    private static final String TAG = "StdIdGuidCompat";
    private static final String STDSP = "com.oplus.stdsp";
    private static final String CODEBOOK = "com.coloros.codebook";
    private static final String SDK = "com.oplus.stdid.sdk.StdIDSDK";
    private static final String INFO = "com.oplus.stdid.bean.StdIDInfo";
    // Password Book 17.x bundles a differently-obfuscated StdID client.  Its
    // getStatus request reads GUID from this API rather than StdIDSDK above.
    private static final String CODEBOOK_SDK = "com.oplus.stdid.sdk.O8〇oO8〇88";
    private static final String CODEBOOK_INFO = "com.oplus.stdid.bean.O8〇oO8〇88";
    // Preinstalled CodeBook 17.1.x uses 〇O8(int); later extracted builds
    // renamed the same method to 〇Ooo(int).
    private static final String CODEBOOK_GET_IDS = "〇O8";
    private static final String CODEBOOK_GUID_FIELD = "O8〇oO8〇88";
    private static final String SYSTEM_PROCESS = "android";
    private static final String SYSTEM_STDID = "com.android.server.notification.StdID";
    private static final String SYSTEM_STDID_MANAGER = "com.android.server.notification.StdIDManager";
    private static final String OPENID = "com.heytap.openid";
    private static final String OPENID_BINDER = "com.heytap.openid.IdentifyBinder";
    private static final int TYPE_GUID = 16;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!DeviceGate.isSupported()) {
            return;
        }
        if (STDSP.equals(lpp.packageName)) {
            // SRP runs in com.oplus.stdsp:remote.oplus.omes.srpservice.  Keep
            // this diagnostic at package-load time: it proves the scope covers
            // that remote process before a cloud-sync attempt is made.
            XposedBridge.log(TAG + ": loaded in " + lpp.processName);
        }
        if (SYSTEM_PROCESS.equals(lpp.packageName)) {
            installSystemFallback(lpp);
            return;
        }
        if (OPENID.equals(lpp.packageName)) {
            installOpenIdFallback(lpp);
            return;
        }
        if ((!STDSP.equals(lpp.packageName) && !CODEBOOK.equals(lpp.packageName))
                || lpp.processName == null || !lpp.processName.startsWith(lpp.packageName)) return;
        // Password Book bundles an independently obfuscated StdID SDK.  It
        // does not contain the regular StdIDSDK/StdIDInfo classes used by
        // StdSP, so do not attempt that API first.
        if (CODEBOOK.equals(lpp.packageName)) {
            installCodeBookSdkFallback(lpp);
            return;
        }
        installSdkFallback(lpp);
    }

    private static void installSystemFallback(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(SYSTEM_STDID, lpp.classLoader, "getGUID",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object result = param.getResult();
                                if (result instanceof String && !((String) result).isEmpty()) return;
                                String fallback = stableGuid();
                                if (fallback.isEmpty()) return;
                                XposedHelpers.setObjectField(param.thisObject, "mGUID", fallback);
                                XposedHelpers.setObjectField(param.thisObject, "mCurrentGUID", fallback);
                                XposedHelpers.callMethod(param.thisObject, "saveConfigFile");
                                param.setResult(fallback);
                                XposedBridge.log(TAG + ": persisted stable local GUID in system StdID");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": system fallback failed");
                                XposedBridge.log(error);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed system StdID empty-GUID fallback");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": system install failed");
            XposedBridge.log(error);
        }
        try {
            XposedHelpers.findAndHookMethod(SYSTEM_STDID_MANAGER, lpp.classLoader, "getGUID",
                    String.class, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.getResult() instanceof String)
                                        || !((String) param.getResult()).isEmpty()) return;
                                if (param.args.length < 1 || !OPENID.equals(param.args[0])) return;
                                String fallback = stableGuid();
                                if (fallback.isEmpty()) return;
                                param.setResult(fallback);
                                XposedBridge.log(TAG + ": supplied stable local GUID to OpenID caller");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": manager fallback failed");
                                XposedBridge.log(error);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed StdIDManager OpenID GUID fallback");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": manager install failed");
            XposedBridge.log(error);
        }
    }

    private static void installSdkFallback(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            final Class<?> infoClass = XposedHelpers.findClass(INFO, lpp.classLoader);
            XposedHelpers.findAndHookMethod(SDK, lpp.classLoader, "getStdIds",
                    android.content.Context.class, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.args[1] instanceof Integer)) {
                                    return;
                                }
                                int requestedTypes = ((Integer) param.args[1]).intValue();
                                if ((requestedTypes & TYPE_GUID) == 0) {
                                    return;
                                }
                                Object result = param.getResult();
                                String guid = result == null ? "" : (String) XposedHelpers.callMethod(result, "getGUID");
                                if (guid != null && !guid.isEmpty()) return;

                                String stableGuid = stableGuid();
                                if (stableGuid.isEmpty()) return;
                                // This interception is only for requests that include GUID.
                                // StdIDInfo's non-GUID accessor names changed between OPlus
                                // releases (for example getAPID -> a mangled accessor).  Do
                                // not reflect those unrelated fields: a missing optional getter
                                // must never prevent the SRP-critical GUID from being returned.
                                param.setResult(infoClass.getConstructor(String.class, String.class,
                                        Boolean.TYPE, String.class, String.class, String.class)
                                        .newInstance(stableGuid, "", Boolean.FALSE,
                                                "", "", ""));
                                XposedBridge.log(TAG + ": supplied stable local GUID to " + lpp.packageName);
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": fallback failed");
                                XposedBridge.log(error);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed SDK empty-GUID fallback for " + lpp.packageName);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": install failed");
            XposedBridge.log(error);
        }
    }

    /**
     * Password Book's embedded StdID 17.x API returns a five-field object
     * from {@code m6100Ooo(int)}.  Type bit 16 is GUID.  The port's original
     * framework returns that field empty, even though the stable system GUID
     * has already been provisioned for StdSP.  Populate only that empty GUID
     * result, leaving every other ID type and the app's normal SDK flow alone.
     */
    private static void installCodeBookSdkFallback(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            final Class<?> infoClass = XposedHelpers.findClass(CODEBOOK_INFO, lpp.classLoader);
            XposedHelpers.findAndHookMethod(CODEBOOK_SDK, lpp.classLoader, CODEBOOK_GET_IDS,
                    Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int requestedTypes = param.args[0] instanceof Integer
                                        ? ((Integer) param.args[0]).intValue() : 0;
                                if ((requestedTypes & TYPE_GUID) == 0) {
                                    return;
                                }
                                Object result = param.getResult();
                                String guid = result == null ? "" : (String) XposedHelpers.getObjectField(
                                        result, CODEBOOK_GUID_FIELD);
                                if (guid != null && !guid.isEmpty()) return;
                                String fallback = stableGuid();
                                if (fallback.isEmpty()) return;
                                param.setResult(infoClass.getConstructor(String.class, String.class,
                                                String.class, String.class, String.class)
                                        .newInstance(fallback, "", "", "", ""));
                                XposedBridge.log(TAG + ": supplied stable local GUID to Password Book");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": Password Book fallback failed");
                                XposedBridge.log(error);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed Password Book StdID GUID fallback");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": Password Book fallback install failed");
            XposedBridge.log(error);
        }
    }

    private static void installOpenIdFallback(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(OPENID_BINDER, lpp.classLoader,
                    "checkValidAndGetOpenID", android.content.Context.class,
                    "com.heytap.openid.oaidcontrolled.OAIDPermissionHandler",
                    String.class, String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.getResult() instanceof String)
                                        || !((String) param.getResult()).isEmpty()) return;
                                if (param.args.length < 5 || !"GUID".equals(param.args[4])) return;
                                String fallback = stableGuid();
                                if (fallback.isEmpty()) return;
                                param.setResult(fallback);
                                XposedBridge.log(TAG + ": supplied stable local GUID via OpenID service");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": OpenID fallback failed");
                                XposedBridge.log(error);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed OpenID GUID fallback");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": OpenID install failed");
            XposedBridge.log(error);
        }
    }

    private static String stableGuid() {
        String serial = getProperty("ro.serialno");
        if (serial.isEmpty()) serial = getProperty("ro.boot.serialno");
        String soc = getProperty("ro.soc.model");
        if (serial.isEmpty() || soc.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("ColorOS-StdID-Lenovo-Local-v1|" + serial + "|" + soc)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) out.append(String.format("%02x", item & 0xff));
            return out.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String getProperty(String key) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getDeclaredMethod("get", String.class).invoke(null, key);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
