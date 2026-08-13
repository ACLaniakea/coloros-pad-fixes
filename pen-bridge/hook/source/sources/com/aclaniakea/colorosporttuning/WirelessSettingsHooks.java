package com.aclaniakea.colorosporttuning;

import android.content.ContentResolver;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;

/* loaded from: classes.dex */
final class WirelessSettingsHooks {
    private static final ThreadLocal<Boolean> logged = new ThreadLocal<>();

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "android.provider.Settings$Global", "getInt", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.WirelessSettingsHooks.1
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                String string;
                if (methodHookParam.args != null && methodHookParam.args.length >= 2 && (methodHookParam.args[0] instanceof ContentResolver) && (methodHookParam.args[1] instanceof String) && "ipe_pencil_charging_state".equals(methodHookParam.args[1])) {
                    try {
                        string = Settings.Global.getString((ContentResolver) methodHookParam.args[0], "lenovo_pen_physical_docked");
                    } catch (Throwable unused) {
                        string = null;
                    }
                    if ("0".equals(string)) {
                        try {
                            methodHookParam.setResult(HookUtils.adapt(((Method) methodHookParam.method).getReturnType(), 0));
                        } catch (Throwable unused2) {
                            methodHookParam.setResult(0);
                        }
                        if (WirelessSettingsHooks.logged.get() == null) {
                            WirelessSettingsHooks.logged.set(Boolean.TRUE);
                            HookUtils.log("WirelessSettings charge read gated: physical undocked");
                        }
                    }
                }
            }
        });
        HookUtils.log("WirelessSettings hooks installed");
    }

    private WirelessSettingsHooks() {
    }
}
