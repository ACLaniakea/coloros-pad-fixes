package com.aclaniakea.lenovopenbridgeguard;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
/** Keeps the base module from applying source-device pen assumptions to Lenovo's bridge. */
public final class LenovoPenBridgeGuard implements IXposedHookLoadPackage {
    private static final String PEN_PACKAGE = "com.inkdye.lenovopentocoloros";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (PEN_PACKAGE.equals(loadPackageParam.packageName)) {
            try {
                ClassLoader classLoader = loadPackageParam.classLoader;
                XposedHelpers.findAndHookMethod("com.inkdye.lenovopentocoloros.bridge.ColorOsPenStateWriter", classLoader, "writeSettings", new Object[]{Context.class, Class.forName("com.inkdye.lenovopentocoloros.bridge.PenState", true, classLoader), new XC_MethodHook() { // from class: com.aclaniakea.lenovopenbridgeguard.LenovoPenBridgeGuard.1
                    private int previousEnable;

                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        this.previousEnable = Settings.Global.getInt(((Context) methodHookParam.args[0]).getContentResolver(), "settings_enable_oppo_pencil", 0);
                    }

                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        if (this.previousEnable == 0) {
                            Settings.Global.putInt(((Context) methodHookParam.args[0]).getContentResolver(), "settings_enable_oppo_pencil", 0);
                        }
                    }
                }});
                XposedHelpers.findAndHookMethod("com.inkdye.lenovopentocoloros.bridge.ColorOsPenStateWriter", classLoader, "notifyColorOsCoreService", new Object[]{Context.class, Class.forName("com.inkdye.lenovopentocoloros.bridge.PenState", true, classLoader), new XC_MethodHook() { // from class: com.aclaniakea.lenovopenbridgeguard.LenovoPenBridgeGuard.2
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        try {
                            ((Context) methodHookParam.args[0]).getPackageManager().getServiceInfo(new ComponentName("com.oplus.ipemanager", "com.oplus.ipemanager.btadsorb.CoreService"), 512);
                        } catch (Throwable unused) {
                            methodHookParam.setResult((Object) null);
                        }
                    }
                }});
                XposedBridge.log("LenovoPenBridgeGuard: hooks installed");
            } catch (Throwable th) {
                XposedBridge.log("LenovoPenBridgeGuard: install failed: " + th);
            }
        }
    }
}
