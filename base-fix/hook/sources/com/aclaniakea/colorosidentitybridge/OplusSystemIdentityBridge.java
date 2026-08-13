package com.aclaniakea.colorosidentitybridge;

import android.os.Build;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/* loaded from: classes.dex */
/** Limits UI compatibility checks without changing the tablet's persistent device identity. */
public final class OplusSystemIdentityBridge implements IXposedHookLoadPackage {
    private static final String TAG = "OplusSystemIdentityBridge";
    private static final Set<String> TARGET_PACKAGES = new HashSet(Arrays.asList("com.heytap.speechassist", "com.oplus.aiunit", "com.oplus.aimemory", "com.oplus.metis", "com.oplus.pantanal.ums", "com.oplus.linker", "com.heytap.mydevices", "com.heytap.accessory", "com.oplus.padconnect", "com.oplus.cast", "com.coloros.oshare", "com.oplus.account", "com.heytap.cloud", "com.heytap.mcs", "com.heytap.htms", "com.heytap.openid", "com.heytap.vip", "com.heytap.market", "com.nearme.instant.platform", "com.oplus.themestore", "com.heytap.themestore", "com.nearme.themespace", "com.nearme.themestore", "com.oplus.cosa", "com.oplus.games", "com.oplus.romupdate", "com.oplus.sau", "com.oplus.sauhelper", "com.oplus.ota"));

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (TARGET_PACKAGES.contains(loadPackageParam.packageName)) {
            setBuildField("BRAND", "OnePlus");
            setBuildField("MANUFACTURER", "OnePlus");
            setBuildField("MODEL", "OPD2513");
            setBuildField("PRODUCT", "OPD2513");
            setBuildField("DEVICE", "OP6547L1");
            hookSystemProperties(loadPackageParam.classLoader);
            XposedBridge.log("OplusSystemIdentityBridge: source identity enabled for " + loadPackageParam.packageName + "/" + loadPackageParam.processName);
        }
    }

    private static void setBuildField(String str, String str2) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, str, str2);
        } catch (Throwable th) {
            XposedBridge.log("OplusSystemIdentityBridge: failed Build." + str);
            XposedBridge.log(th);
        }
    }

    private static void hookSystemProperties(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", classLoader, "get", new Object[]{String.class, new PropertyHook()});
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", classLoader, "get", new Object[]{String.class, String.class, new PropertyHook()});
        } catch (Throwable th) {
            XposedBridge.log("OplusSystemIdentityBridge: SystemProperties hook failed");
            XposedBridge.log(th);
        }
    }

    private static final class PropertyHook extends XC_MethodHook {
        private PropertyHook() {
        }

        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
            String strSourceValue;
            if (methodHookParam.args == null || methodHookParam.args.length == 0 || !(methodHookParam.args[0] instanceof String) || (strSourceValue = OplusSystemIdentityBridge.sourceValue((String) methodHookParam.args[0])) == null) {
                return;
            }
            methodHookParam.setResult(strSourceValue);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String sourceValue(String str) {
        if ("ro.product.brand".equals(str) || "ro.product.manufacturer".equals(str)) {
            return "OnePlus";
        }
        if ("ro.product.model".equals(str) || "ro.product.name".equals(str) || "ro.build.product".equals(str)) {
            return "OPD2513";
        }
        if ("ro.product.device".equals(str)) {
            return "OP6547L1";
        }
        return null;
    }
}
