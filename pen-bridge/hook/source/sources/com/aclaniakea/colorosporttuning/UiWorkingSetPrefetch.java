package com.aclaniakea.colorosporttuning;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
public final class UiWorkingSetPrefetch implements IXposedHookLoadPackage {
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!DeviceGate.supported() || loadPackageParam == null || loadPackageParam.packageName == null) {
            return;
        }
        String str = loadPackageParam.packageName;
        str.hashCode();
        switch (str) {
            case "com.oplus.healthservice":
            case "com.oplus.exsystemservice":
                HookUtils.log("broadcast/Binder target active: " + loadPackageParam.packageName);
                break;
            case "android":
                SystemStylusHooks.install(loadPackageParam);
                break;
            case "com.oplus.ipemanager":
                IpeManagerHooks.install(loadPackageParam);
                break;
            case "com.oplus.wirelesssettings":
                WirelessSettingsHooks.install(loadPackageParam);
                break;
            case "com.android.systemui":
                SystemUiDeviceCardHooks.install(loadPackageParam);
                break;
            case "com.coloros.note":
            case "com.oplus.screenshot":
                NoteToolkitHooks.install(loadPackageParam);
                break;
            case "com.heytap.mydevices":
                MyDevicesHooks.install(loadPackageParam);
                break;
        }
    }
}
