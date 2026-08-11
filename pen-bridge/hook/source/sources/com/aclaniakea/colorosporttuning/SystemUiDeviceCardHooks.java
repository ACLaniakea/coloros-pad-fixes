package com.aclaniakea.colorosporttuning;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes.dex */
/** Feeds the real Lenovo pen battery into the ColorOS control-center device card (SystemUI). */
final class SystemUiDeviceCardHooks {
    private static ClassLoader appLoader;

    static void install(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        appLoader = loadPackageParam.classLoader;
        HookUtils.hookAll(appLoader, "com.oplus.deviceplugin.sdk.entity.PanelDevice", "getBatteryList", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.SystemUiDeviceCardHooks.1
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    Object panel = methodHookParam.thisObject;
                    if (panel == null) {
                        return;
                    }
                    String strName = String.valueOf(HookUtils.call(panel, "getName")).toLowerCase();
                    if (!strName.contains("lenovo") || !strName.contains("pen")) {
                        return;
                    }
                    Context context = HookUtils.context(panel);
                    if (context == null) {
                        return;
                    }
                    PenState penState = HookUtils.state(context);
                    if (penState == null || penState.battery < 0) {
                        return;
                    }
                    List<Object> list = new ArrayList<>();
                    list.add(buildBatteryInfo(penState.battery, penState.charging != 0));
                    methodHookParam.setResult(list);
                    HookUtils.log("SystemUI pen card battery injected level=" + penState.battery + " charging=" + penState.charging);
                } catch (Throwable th) {
                    HookUtils.log("SystemUI pen card battery: " + th);
                }
            }
        });
        HookUtils.log("SystemUI device card battery hooks installed");
    }

    private static Object buildBatteryInfo(int i, boolean z) throws Exception {
        Class<?> clsInfo = Class.forName("com.oplus.deviceplugin.sdk.entity.BatteryInfo", false, appLoader);
        Class<?> clsType = Class.forName("com.oplus.deviceplugin.sdk.entity.BatteryType", false, appLoader);
        Object single = Enum.valueOf(clsType.asSubclass(Enum.class), "SINGLE");
        Constructor<?> constructor = clsInfo.getConstructor(clsType, Integer.TYPE, Boolean.TYPE);
        return constructor.newInstance(single, Integer.valueOf(i), Boolean.valueOf(z));
    }

    private SystemUiDeviceCardHooks() {
    }
}
