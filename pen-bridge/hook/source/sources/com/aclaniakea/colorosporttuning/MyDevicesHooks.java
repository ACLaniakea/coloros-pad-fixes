package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
final class MyDevicesHooks {
    private static final String MODEL = "OPN2403";
    private static final String PANEL = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL";

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "com.heytap.mydevices.core.config.DeviceAppConfigManager", "c", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.MyDevicesHooks.1
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (methodHookParam.args.length <= 0 || !"com.oplus.ipemanager".equals(methodHookParam.args[0])) {
                    return;
                }
                methodHookParam.setResult(true);
            }
        });
        hookPanelAction(loadPackageParam, "aa.hc");
        hookPanelAction(loadPackageParam, "aa.C1460hc");
        hookClickEntity(loadPackageParam, "aa.tP");
        hookClickEntity(loadPackageParam, "aa.AbstractC2425tP");
        XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.MyDevicesHooks.2
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (MyDevicesHooks.isPen(methodHookParam.thisObject, HookUtils.context(methodHookParam.thisObject))) {
                    methodHookParam.setResult(MyDevicesHooks.MODEL);
                }
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.domain.entities.device.DeviceInfo", "getDeviceModelId", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.domain.entities.device.DeviceInfo", "getDeviceIconId", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.bluetooth.BlueToothDetailActivity", "H0", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.MyDevicesHooks.3
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (methodHookParam.thisObject instanceof Activity) {
                    Activity activity = (Activity) methodHookParam.thisObject;
                    PenState penStateState = HookUtils.state(activity);
                    if (penStateState.connected || (penStateState.address.length() > 0 && HookUtils.bluetoothConnected(activity, penStateState.address) && !HookUtils.disconnectRequested(activity))) {
                        activity.getIntent().setAction(MyDevicesHooks.PANEL).putExtra("device_mac_info", penStateState.address).putExtra("device_title", penStateState.name.length() == 0 ? "Lenovo Tab Pen" : penStateState.name).putExtra("device_type", "pencil").putExtra("model_id", MyDevicesHooks.MODEL);
                    }
                }
            }
        });
        HookUtils.log("MyDevices hooks installed");
    }

    private static void hookPanelAction(XC_LoadPackage.LoadPackageParam loadPackageParam, String str) {
        HookUtils.hookAll(loadPackageParam.classLoader, str, "g", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.MyDevicesHooks.4
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (methodHookParam.args.length <= 0 || !MyDevicesHooks.isPen(methodHookParam.args[0], HookUtils.context(methodHookParam.thisObject))) {
                    return;
                }
                methodHookParam.setResult(MyDevicesHooks.PANEL);
            }
        });
    }

    private static void hookClickEntity(XC_LoadPackage.LoadPackageParam loadPackageParam, String str) {
        HookUtils.hookAll(loadPackageParam.classLoader, str, "d", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.MyDevicesHooks.5
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws SecurityException {
                if (methodHookParam.args.length == 0 || !MyDevicesHooks.isPen(methodHookParam.args[0], HookUtils.context(methodHookParam.thisObject))) {
                    return;
                }
                Object result = methodHookParam.getResult();
                Object obj = methodHookParam.args[0];
                if (result == null) {
                    return;
                }
                String strString = HookUtils.string(obj, "getMyDeviceId");
                String strString2 = HookUtils.string(obj, "getDeviceName");
                String strString3 = HookUtils.string(obj, "getMacAddress");
                PenState penStateState = HookUtils.state(HookUtils.context(methodHookParam.thisObject));
                if (strString2.length() == 0) {
                    strString2 = penStateState.name.length() == 0 ? "Lenovo Tab Pen" : penStateState.name;
                }
                if (strString3.length() == 0) {
                    strString3 = penStateState.address;
                }
                HookUtils.call(result, "setAction", MyDevicesHooks.PANEL);
                HookUtils.call(result, "setPackageName", "com.oplus.ipemanager");
                HookUtils.call(result, "setParams", "device_id", strString);
                HookUtils.call(result, "setParams", "model_id", MyDevicesHooks.MODEL);
                HookUtils.call(result, "setParams", "device_title", strString2);
                HookUtils.call(result, "setParams", "device_mac_info", strString3);
                HookUtils.call(result, "setParams", "device_type", "pencil");
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isPen(Object obj, Context context) {
        if (obj == null) {
            return false;
        }
        String lowerCase = (HookUtils.string(obj, "getDeviceName") + " " + HookUtils.string(obj, "getName") + " " + HookUtils.string(obj, "getMacAddress") + " " + HookUtils.string(obj, "getDeviceProductId") + " " + HookUtils.string(obj, "getDeviceAppPackage")).toLowerCase();
        if ((lowerCase.contains("lenovo") && (lowerCase.contains("pen") || lowerCase.contains("stylus"))) || lowerCase.contains("opn2403") || lowerCase.contains("ivy pencil")) {
            return true;
        }
        PenState penStateState = HookUtils.state(context);
        return penStateState.connected && penStateState.address.length() > 0 && lowerCase.replace(":", "").contains(penStateState.macNoColon().toLowerCase());
    }

    private MyDevicesHooks() {
    }
}
