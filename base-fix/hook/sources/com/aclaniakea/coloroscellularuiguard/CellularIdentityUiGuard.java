package com.aclaniakea.coloroscellularuiguard;

import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
/** Guards phone-only cellular UI code that is not applicable to this Wi-Fi tablet. */
public final class CellularIdentityUiGuard implements IXposedHookLoadPackage {
    private static final String[] CONTROLLERS = {"com.oplus.settings.feature.deviceinfo.controller.OplusBasebandVersionPreferenceController", "com.oplus.settings.feature.deviceinfo.controller.ImeiSVPreferenceController"};
    private static final String TARGET = "com.android.settings";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (DeviceGate.isSupported() && TARGET.equals(loadPackageParam.packageName)) {
            for (String str : CONTROLLERS) {
                try {
                    XposedHelpers.findAndHookMethod(str, loadPackageParam.classLoader, "isAvailable", new Object[]{new XC_MethodHook() { // from class: com.aclaniakea.coloroscellularuiguard.CellularIdentityUiGuard.1
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                            methodHookParam.setResult(false);
                        }
                    }});
                    XposedBridge.log("CellularIdentityUiGuard: hidden " + str);
                } catch (Throwable th) {
                    XposedBridge.log("CellularIdentityUiGuard: failed " + str);
                    XposedBridge.log(th);
                }
            }
        }
    }
}
