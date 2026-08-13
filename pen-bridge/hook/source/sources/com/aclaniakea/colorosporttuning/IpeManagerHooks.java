package com.aclaniakea.colorosporttuning;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/* loaded from: classes.dex */
final class IpeManagerHooks {
    private static volatile boolean capsuleBinding = false;
    private static ServiceConnection capsuleConnection = null;
    private static volatile Object capsuleControl = null;
    private static volatile Object coreBleManager = null;
    private static volatile Object coreService = null;
    private static volatile boolean handoffReceiverInstalled = false;
    private static volatile ClassLoader ipeClassLoader = null;
    private static volatile long lastCapsuleAt = 0;
    private static volatile long lastStockGattConnectAt = 0;
    private static volatile long lastStockProfileConnectedAt = 0;
    private static volatile Object pencilPanelCallback = null;
    private static volatile int pendingCapsuleBattery = -1;
    private static volatile int pendingCapsuleCharging = -1;
    private static volatile Object settingsActivity;
    private static volatile Object settingsCallback;
    private static volatile Object settingsFragment;
    private static volatile boolean stockGattConnectPending;
    private static final List<Object> pencilPanelCallbacks = new ArrayList();
    private static volatile String lastStockProfileMac = "";
    private static volatile String lastStockGattConnectMac = "";

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        ipeClassLoader = loadPackageParam.classLoader;
        XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.1
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                IpeManagerHooks.rememberCoreService(methodHookParam.thisObject);
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context != null) {
                    IpeManagerHooks.sync(context);
                    IpeManagerHooks.scheduleOriginalGattConnect(context, 650L);
                }
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.IPeApplication", "onCreate", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.IPeApplication", "onCreate", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.2
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context != null) {
                    IpeManagerHooks.installColorOsHandoffReceiver(context.getApplicationContext());
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.CoreService", "onCreate", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.CoreService", "onStartCommand", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.CoreService", "onStartCommand", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.3
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                Intent intentIntentArg = IpeManagerHooks.intentArg(methodHookParam.args);
                if (context == null || intentIntentArg == null) {
                    return;
                }
                try {
                    if ("com.oplus.ipemanager.ACTION.BROADCAST.OAF_DEVICE_FOUND".equals(intentIntentArg.getAction()) && "pencil_boot_recovery".equals(intentIntentArg.getStringExtra("deviceType"))) {
                        intentIntentArg.setAction("com.oplus.ipemanager.action.PENCIL_BONDED_WHEN_BOOT");
                        HookUtils.log("IPe boot OAF wake rerouted to stock PENCIL_BONDED_WHEN_BOOT");
                        return;
                    }
                    if ("com.oplus.ipemanager.action.CONNECT_PENCIL".equals(intentIntentArg.getAction())) {
                        String requestedMac = intentIntentArg.getStringExtra("device_mac_info");
                        if (isMac(requestedMac)) {
                            Settings.Global.putString(context.getContentResolver(), "ipe_pencil_mac_addr", requestedMac);
                            HookUtils.log("stock CONNECT_PENCIL selected pen MAC=" + requestedMac);
                        }
                        Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_disconnect_requested", 0);
                        Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_user_disconnect_requested", 0);
                        Settings.Global.putInt(context.getContentResolver(), "settings_enable_oppo_pencil", 1);
                        Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_present", 1);
                        HookUtils.log("stock CONNECT_PENCIL cleared disconnect latch");
                    } else if ("com.oplus.ipemanager.action.DISCONNECT_PENCIL".equals(intentIntentArg.getAction())) {
                        String requestedMac2 = intentIntentArg.getStringExtra("device_mac_info");
                        if (isMac(requestedMac2)) {
                            Settings.Global.putString(context.getContentResolver(), "ipe_pencil_mac_addr", requestedMac2);
                        }
                        Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_disconnect_requested", 1);
                        Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_user_disconnect_requested", 1);
                        Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_refresh_active", 0);
                        Settings.Global.putInt(context.getContentResolver(), "settings_enable_oppo_pencil", 0);
                        Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_present", 0);
                        IpeManagerHooks.invokeOriginalGattDisconnect(context, intentIntentArg.getStringExtra("device_mac_info"));
                        HookUtils.log("stock DISCONNECT_PENCIL armed disconnect latch");
                    }
                } catch (Throwable unused) {
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.CoreService", "onBind", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "d.a", "onServiceConnected", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.4
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object objObjectFieldOfType = IpeManagerHooks.objectFieldOfType(methodHookParam.thisObject, "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity");
                if (objObjectFieldOfType != null) {
                    IpeManagerHooks.rememberSettingsActivity(objObjectFieldOfType);
                    IpeManagerHooks.replaySettingsPage(objObjectFieldOfType);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.n0", "setIUIPageCbBinder", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.5
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object objFieldAny = IpeManagerHooks.fieldAny(methodHookParam.thisObject, "f1939c", "c");
                if (objFieldAny == null) {
                    objFieldAny = IpeManagerHooks.fieldByTypeName(methodHookParam.thisObject, "com.oplus.ipemanager.btadsorb.ble.s0");
                }
                if (objFieldAny != null) {
                    Object unused = IpeManagerHooks.coreBleManager = objFieldAny;
                    HookUtils.log("IPe OEM settings Binder registered");
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.n0", "setIPencilUIManagerCbBinder", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.6
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object objFieldAny = IpeManagerHooks.fieldAny(methodHookParam.thisObject, "f1939c", "c");
                if (objFieldAny == null) {
                    objFieldAny = IpeManagerHooks.fieldByTypeName(methodHookParam.thisObject, "com.oplus.ipemanager.btadsorb.ble.s0");
                }
                if (objFieldAny != null) {
                    Object unused = IpeManagerHooks.coreBleManager = objFieldAny;
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity", "onCreate", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.7
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                IpeManagerHooks.rememberSettingsActivity(methodHookParam.thisObject);
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context != null) {
                    IpeManagerHooks.installColorOsHandoffReceiver(context.getApplicationContext());
                }
            }
        });
        XC_MethodHook xC_MethodHook2 = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.8
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                IpeManagerHooks.rememberSettingsActivity(methodHookParam.thisObject);
                IpeManagerHooks.replaySettingsPage(methodHookParam.thisObject);
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity", "onStart", xC_MethodHook2);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity", "onResume", xC_MethodHook2);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.activity.PencilSettingActivity", "onDestroy", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.9
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                IpeManagerHooks.clearSettingsActivity(methodHookParam.thisObject);
            }
        });
        XC_MethodHook xC_MethodHook3 = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.10
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object unused = IpeManagerHooks.settingsFragment = methodHookParam.thisObject;
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.fragment.t0", "onCreate", xC_MethodHook3);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.fragment.t0", "onResume", xC_MethodHook3);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.fragment.t0", "onDestroy", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.11
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (IpeManagerHooks.settingsFragment == methodHookParam.thisObject) {
                    Object unused = IpeManagerHooks.settingsFragment = null;
                }
            }
        });
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.n0", "getBatteryLevel", 0);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.n0", "getConnectState", 1);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.n0", "getConnectedPencilMac", 2);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.f0", "getBatteryLevel", 0);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.f0", "getChargingState", 3);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.f0", "getConnectState", 1);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.f0", "getAlias", 4);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.f0", "getPencilTypeByMac", 5);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.f0", "getPencilName", 4);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.g0", "getPencilConnectState", 1);
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ble.g0", "getBatteryLevel", 0);
        installHardwareGattHooks(loadPackageParam);
        installPencilPanelCallbackBridge(loadPackageParam);
        String[] strArr = {"getPencilFw", "getFirmware", "getFirmwareVersion"};
        for (int i = 0; i < 3; i++) {
            hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ota.common.PencilInfo", strArr[i], 6);
        }
        String[] strArr2 = {"getPencilHw", "getHardware", "getHardwareVersion"};
        for (int i2 = 0; i2 < 3; i2++) {
            hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ota.common.PencilInfo", strArr2[i2], 7);
        }
        String[] strArr3 = {"getPencilNum", "getSerial", "getSerialNumber"};
        for (int i3 = 0; i3 < 3; i3++) {
            hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ota.common.PencilInfo", strArr3[i3], 8);
        }
        hookGetter(loadPackageParam, "com.oplus.ipemanager.btadsorb.ota.common.PencilInfo", "getPencilType", 5);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.d", "getIpeDeviceType", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.12
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object objAdapt = HookUtils.adapt(((Method) methodHookParam.method).getReturnType(), "PENCIL");
                if (objAdapt == null) {
                    return;
                }
                methodHookParam.setResult(objAdapt);
                HookUtils.log("IPe device-card Binder: Lenovo pen -> PENCIL");
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "k3.a", "getIpeDeviceType", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.13
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object result = methodHookParam.getResult();
                Object objAdapt;
                if ((result == null || "OTHER".equalsIgnoreCase(String.valueOf(result))) && (objAdapt = HookUtils.adapt(((Method) methodHookParam.method).getReturnType(), "PENCIL")) != null) {
                    methodHookParam.setResult(objAdapt);
                    HookUtils.log("IPe device-card AIDL proxy: Lenovo pen -> PENCIL");
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "y1.b", "c", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.14
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object objAdapt;
                if (methodHookParam.args == null || methodHookParam.args.length < 3 || !(methodHookParam.args[2] instanceof String) || !"pencil_sp_charging_state".equals(methodHookParam.args[2])) {
                    return;
                }
                Context context = methodHookParam.args[0] instanceof Context ? (Context) methodHookParam.args[0] : HookUtils.context(methodHookParam.thisObject);
                if (context == null || (objAdapt = HookUtils.adapt(((Method) methodHookParam.method).getReturnType(), Integer.valueOf(HookUtils.state(context).charging))) == null) {
                    return;
                }
                methodHookParam.setResult(objAdapt);
            }
        });
        installWhitelist(loadPackageParam);
        installStockProfileStateBridge(loadPackageParam);
        installRiskGuard(loadPackageParam);
        installGestureTextBridge(loadPackageParam);
        installWritingHapticPreference(loadPackageParam);
        installPencilPanelControlBridge(loadPackageParam);
        installMyDevicesCardBatteryBridge(loadPackageParam);
        installMyDevicesStateBridge(loadPackageParam);
        installDeviceCardBatteryListBridge(loadPackageParam);
        HookUtils.log("IPeManager hooks installed");
    }

    private static void installDeviceCardBatteryListBridge(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.sdk.device.DeviceInfo", "getBatteryList", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.49
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    Object deviceInfo = methodHookParam.thisObject;
                    if (deviceInfo == null) {
                        return;
                    }
                    String strName = String.valueOf(HookUtils.call(deviceInfo, "getName")).toLowerCase();
                    if (!strName.contains("lenovo") || !strName.contains("pen")) {
                        return;
                    }
                    Context context = HookUtils.context(deviceInfo);
                    if (context == null) {
                        context = HookUtils.context(null);
                    }
                    if (context == null) {
                        return;
                    }
                    PenState penState = HookUtils.state(context);
                    if (penState == null || penState.battery < 0) {
                        return;
                    }
                    Object objBattery = buildSdkBatteryInfo(penState.battery, penState.charging != 0, loadPackageParam.classLoader);
                    if (objBattery == null) {
                        return;
                    }
                    List<Object> list = new ArrayList<>();
                    list.add(objBattery);
                    methodHookParam.setResult(list);
                    HookUtils.log("device card DeviceInfo battery bridged level=" + penState.battery);
                } catch (Throwable th) {
                    HookUtils.log("device card DeviceInfo battery bridge: " + th);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "x2.b", "f", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.50
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    Context context = HookUtils.context(methodHookParam.thisObject);
                    if (context == null) {
                        context = HookUtils.context(null);
                    }
                    if (context == null) {
                        return;
                    }
                    PenState penState = HookUtils.state(context);
                    if (penState == null || penState.battery < 0) {
                        return;
                    }
                    Object objBattery = buildSdkBatteryInfo(penState.battery, penState.charging != 0, loadPackageParam.classLoader);
                    if (objBattery == null) {
                        return;
                    }
                    List<Object> list = new ArrayList<>();
                    list.add(objBattery);
                    methodHookParam.setResult(list);
                    HookUtils.log("device card battery list bridged level=" + penState.battery + " charging=" + penState.charging);
                } catch (Throwable th) {
                    HookUtils.log("device card battery list bridge: " + th);
                }
            }
        });
    }

    private static Object buildSdkBatteryInfo(int i, boolean z, ClassLoader classLoader) {
        try {
            Class<?> clsInfo = Class.forName("com.oplus.mydevices.sdk.device.BatteryInfo", false, classLoader);
            Class<?> clsType = Class.forName("com.oplus.mydevices.sdk.device.BatteryType", false, classLoader);
            Object single = Enum.valueOf(clsType.asSubclass(Enum.class), "SINGLE");
            Constructor<?> constructor = clsInfo.getConstructor(clsType, Integer.TYPE, Boolean.TYPE);
            return constructor.newInstance(single, Integer.valueOf(i), Boolean.valueOf(z));
        } catch (Throwable unused) {
            return null;
        }
    }

    private static void refreshDeviceCardData(Context context) {
        try {
            BluetoothDevice device = findPenDevice(context);
            if (device == null) {
                return;
            }
            Class<?> clsB = Class.forName("x2.b", false, ipeClassLoader);
            Object deviceInfo = clsB.getMethod("d", Context.class, BluetoothDevice.class, Boolean.TYPE).invoke(null, context, device, Boolean.TRUE);
            if (deviceInfo == null) {
                return;
            }
            Class<?> clsMgr = Class.forName("com.oplus.mydevices.sdk.DeviceInfoManager", false, ipeClassLoader);
            Object manager = clsMgr.getField("INSTANCE").get(null);
            manager.getClass().getMethod("add", deviceInfo.getClass()).invoke(manager, deviceInfo);
            HookUtils.log("device card data refreshed with battery");
        } catch (Throwable th) {
            HookUtils.log("device card refresh failed: " + th);
        }
    }

    /** 从真实蓝牙栈按名字找联想笔设备，避免依赖固定 MAC 记忆。 */
    private static BluetoothDevice findPenDevice(Context context) {
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager == null) {
                return null;
            }
            for (BluetoothDevice d : manager.getConnectedDevices(7)) {
                String name = d.getName();
                if (name != null && name.toLowerCase().contains("lenovo tab pen")) {
                    return d;
                }
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return null;
            }
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String name = d.getName();
                if (name != null && name.toLowerCase().contains("lenovo tab pen")) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void installPencilPanelControlBridge(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.f0", "connect", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.44
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                String strMac = IpeManagerHooks.stringArg(methodHookParam.args);
                if (context == null || strMac == null || strMac.length() == 0) {
                    return;
                }
                context.startService(new Intent("com.oplus.ipemanager.action.CONNECT_PENCIL").putExtra("device_mac_info", strMac).setClassName("com.oplus.ipemanager", "com.oplus.ipemanager.btadsorb.CoreService"));
                HookUtils.log("IPe PencilPanel connect routed to stock CONNECT_PENCIL mac=" + strMac);
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.f0", "disconnect", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.45
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                String strMac = IpeManagerHooks.stringArg(methodHookParam.args);
                if (context == null || strMac == null || strMac.length() == 0) {
                    return;
                }
                context.startService(new Intent("com.oplus.ipemanager.action.DISCONNECT_PENCIL").putExtra("device_mac_info", strMac).setClassName("com.oplus.ipemanager", "com.oplus.ipemanager.btadsorb.CoreService"));
                HookUtils.log("IPe PencilPanel disconnect routed to stock DISCONNECT_PENCIL mac=" + strMac);
            }
        });
        HookUtils.log("IPe PencilPanel control bridge installed");
    }

    private static void installMyDevicesCardBatteryBridge(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.46
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object obj = methodHookParam.thisObject;
                if (obj == null) {
                    return;
                }
                Object objType = HookUtils.call(obj, "getBatteryType");
                if (objType == null) {
                    return;
                }
                String strType = objType.getClass().getSimpleName();
                HookUtils.log("IPe card battery getCharge type=" + strType);
                if (!"SINGLE".equals(strType)) {
                    return;
                }
                Application application;
                try {
                    application = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                } catch (Throwable unused7) {
                    application = null;
                }
                if (application == null) {
                    return;
                }
                int iCharging = Settings.Global.getInt(application.getContentResolver(), "ipe_pencil_charging_state", 0);
                methodHookParam.setResult(Boolean.valueOf(iCharging != 0));
                HookUtils.log("IPe DeviceSpace card charging overridden");
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.domain.entities.device.BatteryInfo", "getCharge", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.pencilPanel.fragment.k1", "b", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.heytap.mydevices.core.bluetooth.b", "f", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.47
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (methodHookParam.args == null || methodHookParam.args.length < 2) {
                    return;
                }
                Object objFirst = methodHookParam.args[0];
                Object objSecond = methodHookParam.args[1];
                if (!(objFirst instanceof Integer) || ((Integer) objFirst).intValue() != 2000 || objSecond == null || !"[I".equals(objSecond.getClass().getName())) {
                    return;
                }
                int[] iArr = (int[]) objSecond;
                if (iArr.length < 4) {
                    return;
                }
                Application application;
                try {
                    application = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                } catch (Throwable unused8) {
                    application = null;
                }
                if (application == null || Settings.Global.getInt(application.getContentResolver(), "ipe_pencil_charging_state", 0) != 0) {
                    return;
                }
                iArr[3] = 0;
                HookUtils.log("IPe card battery charge cleared");
            }
        });
        XC_MethodHook xC_MethodHook2 = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.49
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                HookUtils.log("IPe card battery path hit");
                if (methodHookParam.args == null || methodHookParam.args.length == 0 || methodHookParam.args[0] == null) {
                    return;
                }
                HookUtils.call(methodHookParam.args[0], "getValue");
                HookUtils.log("IPe card battery value=");
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView", "setBatteryInfo", xC_MethodHook2);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData", "getBatteryMain", xC_MethodHook2);
    }

    private static void installHardwareGattHooks(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        OemGattProtocolHooks.install(loadPackageParam);
    }

    private static void installPencilPanelCallbackBridge(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.f0", "registerPencilPanelCallback", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.15
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                IpeManagerHooks.rememberPencilPanelCallbacks(methodHookParam.thisObject);
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.f0", "unregisterPencilPanelCallback", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.16
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                IpeManagerHooks.rememberPencilPanelCallbacks(methodHookParam.thisObject);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void rememberPencilPanelCallbacks(Object obj) {
        Object objFieldAny = fieldAny(obj, "f1895e", "e");
        if (objFieldAny == null) {
            objFieldAny = fieldByTypeName(obj, "com.oplus.ipemanager.btadsorb.ble.s0");
        }
        if (objFieldAny != null || obj == null || !"com.oplus.ipemanager.btadsorb.ble.s0".equals(obj.getClass().getName())) {
            obj = objFieldAny;
        }
        if (obj == null) {
            return;
        }
        Object objField = field(obj, "S0");
        if (objField != null) {
            pencilPanelCallback = objField;
            List<Object> list = pencilPanelCallbacks;
            if (!containsIdentity(list, objField)) {
                list.add(objField);
            }
        }
        Object objField2 = field(obj, "Y0");
        if (objField2 != null) {
            List<Object> list2 = pencilPanelCallbacks;
            if (!containsIdentity(list2, objField2)) {
                list2.add(objField2);
            }
        }
        Object objField3 = field(obj, "Z0");
        if (objField3 instanceof Collection) {
            for (Object obj2 : (Collection) objField3) {
                if (obj2 != null) {
                    List<Object> list3 = pencilPanelCallbacks;
                    if (!containsIdentity(list3, obj2)) {
                        list3.add(obj2);
                    }
                }
            }
        }
        if (objField != null || objField2 != null || (objField3 instanceof Collection)) {
            HookUtils.log("IPe PencilPanel callback registered direct=" + className(objField) + " discovery=" + className(objField2) + " callbacks=" + pencilPanelCallbacks.size());
        }
    }

    private static boolean containsIdentity(List<Object> list, Object obj) {
        if (list != null && obj != null) {
            Iterator<Object> it = list.iterator();
            while (it.hasNext()) {
                if (it.next() == obj) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String className(Object obj) {
        return obj == null ? "null" : obj.getClass().getName();
    }

    static void publishHardwareBattery(Context context, Object obj, int i) throws SecurityException {
        HookUtils.markHardwareBattery(context, i);
        PenState penStateState = HookUtils.state(context);
        BluetoothDevice bluetoothDeviceManagerDevice = managerDevice(obj);
        String strString = HookUtils.string(bluetoothDeviceManagerDevice, "getAddress");
        if (strString.length() == 0) {
            strString = penStateState.address;
        }
        String strString2 = HookUtils.string(bluetoothDeviceManagerDevice, "getName");
        if (strString2.length() == 0) {
            strString2 = penStateState.name;
        }
        PenState penState = new PenState(HookUtils.linkConnected(context) > 0 && !HookUtils.disconnectRequested(context), strString, strString2, i, penStateState.charging, penStateState.type, penStateState.firmware, penStateState.hardware, penStateState.serial, "ipe_ble_gatt", System.currentTimeMillis());
        PenStateStore.write(context, penState);
        sendHardwareUpdate(context, penState, "ipe_ble_gatt", true);
        HookUtils.log("IPe BLE hardware battery sample=" + i + " address=" + strString);
    }

    static void publishHardwareCharging(Context context, Object obj, int i, int i2) throws SecurityException {
        int i3;
        if (HookUtils.physicalDocked(context) == 0) {
            HookUtils.invalidateOemCharging(context);
            HookUtils.log("IPe BLE charge sample ignored while physically undocked raw=" + i2);
            i3 = 0;
        } else {
            HookUtils.markOemCharging(context, i2, i);
            i3 = i;
        }
        PenState penStateState = HookUtils.state(context);
        BluetoothDevice bluetoothDeviceManagerDevice = managerDevice(obj);
        String strString = HookUtils.string(bluetoothDeviceManagerDevice, "getAddress");
        if (strString.length() == 0) {
            strString = penStateState.address;
        }
        String str = strString;
        String strString2 = HookUtils.string(bluetoothDeviceManagerDevice, "getName");
        if (strString2.length() == 0) {
            strString2 = penStateState.name;
        }
        PenState penState = new PenState(HookUtils.linkConnected(context) > 0 && !HookUtils.disconnectRequested(context), str, strString2, penStateState.battery, i3, penStateState.type, penStateState.firmware, penStateState.hardware, penStateState.serial, "ipe_ble_gatt_charge", System.currentTimeMillis());
        PenStateStore.write(context, penState);
        sendHardwareUpdate(context, penState, "ipe_ble_gatt_charge", penState.battery >= 0);
        HookUtils.log("IPe BLE hardware charge sample raw=" + i2 + " charging=" + i3);
    }

    static void publishHardwareDisconnected(Context context, Object obj, String str) throws SecurityException {
        if (context == null) {
            return;
        }
        PenState penStateState = HookUtils.state(context);
        int iHardwareBattery = HookUtils.hardwareBattery(context);
        if (iHardwareBattery < 0) {
            iHardwareBattery = HookUtils.lastValidBattery(context);
        }
        int i = iHardwareBattery;
        BluetoothDevice bluetoothDeviceManagerDevice = managerDevice(obj);
        String strString = HookUtils.string(bluetoothDeviceManagerDevice, "getAddress");
        if (strString.length() == 0) {
            strString = penStateState.address;
        }
        String str2 = strString;
        String strString2 = HookUtils.string(bluetoothDeviceManagerDevice, "getName");
        if (strString2.length() == 0) {
            strString2 = penStateState.name;
        }
        String str3 = strString2;
        HookUtils.setLinkConnected(context, false);
        HookUtils.invalidateHardwareBattery(context);
        HookUtils.invalidateOemCharging(context);
        PenState penState = new PenState(false, str2, str3, i, 0, penStateState.type, penStateState.firmware, penStateState.hardware, penStateState.serial, (str == null || str.length() == 0) ? "ipe_ble_gatt_disconnected" : str, System.currentTimeMillis());
        PenStateStore.write(context, penState);
        sendHardwareUpdate(context, penState, penState.source, false);
        HookUtils.log("IPe GATT disconnected UI snapshot battery=" + i + " address=" + str2);
    }

    static void publishHardwareMetadata(Context context, Object obj, String str, String str2, String str3, String str4, String str5) throws SecurityException {
        if (context == null) {
            return;
        }
        PenState penStateState = HookUtils.state(context);
        BluetoothDevice bluetoothDeviceManagerDevice = managerDevice(obj);
        String strString = HookUtils.string(bluetoothDeviceManagerDevice, "getAddress");
        if (strString.length() == 0) {
            strString = penStateState.address;
        }
        String str6 = strString;
        String strString2 = HookUtils.string(bluetoothDeviceManagerDevice, "getName");
        if (strString2.length() == 0) {
            strString2 = penStateState.name;
        }
        String str7 = strString2;
        String str8 = (str == null || str.length() == 0) ? penStateState.type : str;
        String str9 = (str2 == null || str2.length() == 0) ? penStateState.firmware : str2;
        String str10 = (str3 == null || str3.length() == 0) ? penStateState.hardware : str3;
        String str11 = (str4 == null || str4.length() == 0) ? penStateState.serial : str4;
        PenStateStore.write(context, new PenState(!HookUtils.disconnectRequested(context) && penStateState.connected, str6, str7, penStateState.battery, penStateState.charging, str8, str9, str10, str11, str5, System.currentTimeMillis()));
        HookUtils.log("IPe OEM metadata: type=" + str8 + " firmware=" + str9 + " hardware=" + str10 + " serial=" + str11);
    }

    static void sendHardwareUpdate(Context context, PenState penState, String str, boolean z) {
        Intent intentPutExtra = new Intent("com.oplus.ipemanager.action.BATTERY_NOTIFY").setPackage("com.oplus.ipemanager").putExtra("macAddr", penState.macNoColon()).putExtra("mac_addr", penState.address).putExtra("chargingState", penState.charging).putExtra("charging", penState.charging).putExtra("charging_state", penState.charging).putExtra("present", penState.connected ? "1" : "0").putExtra("connected", penState.connected ? 1 : 0).putExtra("physicalDocked", HookUtils.physicalDocked(context)).putExtra("source", str).putExtra("hardware_battery", z);
        if (penState.battery >= 0) {
            intentPutExtra.putExtra("batteryLevel", penState.battery).putExtra("battery_level", penState.battery);
        }
        try {
            context.sendBroadcast(intentPutExtra);
        } catch (Throwable th) {
            HookUtils.log("IPe BLE update broadcast: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void installColorOsHandoffReceiver(final Context context) {
        if (handoffReceiverInstalled) {
            return;
        }
        try {
            final boolean zEquals = "com.oplus.ipemanager:ble".equals(Application.getProcessName());
            BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.17
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    if ("com.aclaniakea.lenovopenbridge.action.COLOROS_PEN_STATE".equals(intent.getAction())) {
                        if (zEquals) {
                            IpeManagerHooks.handoffColorOsState(context, intent);
                        }
                        IpeManagerHooks.notifySettingsPage(context, intent);
                    } else {
                        if ("com.aclaniakea.lenovopenbridge.action.SHOW_PENCIL_CAPSULE".equals(intent.getAction())) {
                            if (zEquals) {
                                Context context3 = context;
                                IpeManagerHooks.showMagneticCapsule(context3, intent.getIntExtra("battery_level", HookUtils.state(context3).battery), IpeManagerHooks.chargingExtra(intent, HookUtils.state(context).charging));
                                return;
                            }
                            return;
                        }
                        if ("com.aclaniakea.lenovopenbridge.action.DISMISS_PENCIL_CAPSULE".equals(intent.getAction())) {
                            if (zEquals) {
                                IpeManagerHooks.dismissMagneticCapsule();
                            }
                            return;
                        }
                        if ("com.oplus.ipemanager.action.BATTERY_NOTIFY".equals(intent.getAction())) {
                            IpeManagerHooks.notifySettingsPage(context, intent);
                        } else if ("com.aclaniakea.lenovopenbridge.action.OEM_PEN_CONTROL".equals(intent.getAction())) {
                            if (zEquals) {
                                OemGattProtocolHooks.handleControl(context, intent);
                            }
                        }
                    }
                }
            };
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("com.aclaniakea.lenovopenbridge.action.COLOROS_PEN_STATE");
            intentFilter.addAction("com.aclaniakea.lenovopenbridge.action.SHOW_PENCIL_CAPSULE");
            intentFilter.addAction("com.aclaniakea.lenovopenbridge.action.DISMISS_PENCIL_CAPSULE");
            intentFilter.addAction("com.oplus.ipemanager.action.BATTERY_NOTIFY");
            intentFilter.addAction("com.aclaniakea.lenovopenbridge.action.OEM_PEN_CONTROL");
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(broadcastReceiver, intentFilter, 2);
            } else {
                context.registerReceiver(broadcastReceiver, intentFilter);
            }
            handoffReceiverInstalled = true;
            HookUtils.log("IPe ColorOS state/capsule receiver installed process=" + Application.getProcessName());
        } catch (Throwable th) {
            HookUtils.log("IPe state handoff receiver: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    public static void handoffColorOsState(Context context, Intent intent) {
        try {
            int intExtra = intent.hasExtra("physicalDocked") ? intent.getIntExtra("physicalDocked", -1) : -1;
            if (intExtra == 0 || intExtra == 1) {
                HookUtils.setPhysicalDocked(context, intExtra == 1);
            }
            PenState penStateState = HookUtils.state(context);
            String strFirstString = firstString(intent, "macAddr", "address", "device_address");
            if (strFirstString.length() == 0) {
                strFirstString = penStateState.address;
            }
            String strFirstString2 = firstString(intent, "name", "device_name", "penName");
            if (strFirstString2.length() == 0) {
                strFirstString2 = penStateState.name;
            }
            int intExtra2 = (isHardwareBatteryIntent(intent) && intent.hasExtra("batteryLevel")) ? intent.getIntExtra("batteryLevel", -1) : (isHardwareBatteryIntent(intent) && intent.hasExtra("battery_level")) ? intent.getIntExtra("battery_level", -1) : HookUtils.hardwareBattery(context);
            if (intExtra2 < 0 || intExtra2 > 100) {
                intExtra2 = HookUtils.lastValidBattery(context);
            }
            int iChargingExtra = chargingExtra(intent, penStateState.charging);
            int iOemCharging = HookUtils.oemCharging(context);
            if (iOemCharging >= 0) {
                iChargingExtra = iOemCharging;
            }
            int i = (HookUtils.physicalDocked(context) != 0 || HookUtils.oemCharging(context) >= 0) ? iChargingExtra : 0;
            if (isHardwareBatteryIntent(intent) && intExtra2 >= 0 && intExtra2 <= 100) {
                HookUtils.markHardwareBattery(context, intExtra2);
            }
            int intExtra3 = intent.hasExtra("connected") ? intent.getIntExtra("connected", penStateState.connected ? 1 : 0) : penStateState.connectState();
            PenState penState = new PenState(!HookUtils.disconnectRequested(context) && (intExtra3 == 1 || intExtra3 == 2 || intExtra3 == 12 || "1".equals(intent.getStringExtra("present"))) == true && (!"kernel_pen_framework".equals(intent.getStringExtra("source")) || HookUtils.bluetoothConnected(context, strFirstString)), strFirstString, strFirstString2, intExtra2, i, penStateState.type, penStateState.firmware, penStateState.hardware, penStateState.serial, "ipemanager_handoff", System.currentTimeMillis());
            PenStateStore.write(context, penState);
            HookUtils.log("IPe state handoff delivered: battery=" + penState.battery + " charging=" + penState.charging + " connected=" + penState.connected);
        } catch (Throwable th) {
            HookUtils.log("IPe state handoff: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void rememberCoreService(Object obj) {
        if (obj == null || !"com.oplus.ipemanager.btadsorb.CoreService".equals(obj.getClass().getName())) {
            return;
        }
        coreService = obj;
        Object objFieldAny = fieldAny(obj, "f1865b", "b");
        if (objFieldAny == null) {
            objFieldAny = fieldByTypeName(obj, "com.oplus.ipemanager.btadsorb.ble.s0");
        }
        if (objFieldAny != null) {
            coreBleManager = objFieldAny;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void rememberSettingsActivity(Object obj) {
        settingsActivity = obj;
        Object obj2 = settingsCallbackOf(obj);
        if (obj2 != null) {
            settingsCallback = obj2;
        }
        HookUtils.log("IPe settings callback captured=" + (obj2 != null));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void replaySettingsPage(final Object obj) {
        final Context context = HookUtils.context(obj);
        if (context == null) {
            return;
        }
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.18
                @Override // java.lang.Runnable
                public void run() {
                    if (IpeManagerHooks.settingsActivity == obj) {
                        IpeManagerHooks.notifySettingsPage(context, new Intent("com.oplus.ipemanager.action.BATTERY_NOTIFY").putExtra("source", "settings_replay"));
                    }
                }
            }, 180L);
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void clearSettingsActivity(Object obj) {
        if (settingsActivity == obj) {
            settingsActivity = null;
            settingsCallback = null;
            HookUtils.log("IPe settings callback cleared");
        }
    }

    private static Object settingsCallbackOf(Object obj) {
        Object obj2 = null;
        if (obj == null) {
            return null;
        }
        Object objField = field(obj, "f2402j");
        if (hasMethods(objField, "notifyBatteryLevel", "notifyChargingState")) {
            return objField;
        }
        for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
            for (Field field : superclass.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    obj2 = field.get(obj);
                } catch (Throwable unused) {
                }
                if (hasMethods(obj2, "notifyBatteryLevel", "notifyChargingState")) {
                    return obj2;
                }
            }
        }
        return null;
    }

    private static boolean hasMethods(Object obj, String... strArr) throws SecurityException {
        boolean z;
        if (obj == null || strArr == null || strArr.length == 0) {
            return false;
        }
        for (String str : strArr) {
            Method[] methods = obj.getClass().getMethods();
            int length = methods.length;
            int i = 0;
            while (true) {
                if (i >= length) {
                    z = false;
                    break;
                }
                if (str.equals(methods[i].getName())) {
                    z = true;
                    break;
                }
                i++;
            }
            if (!z) {
                for (Class<?> superclass = obj.getClass(); superclass != null && !z; superclass = superclass.getSuperclass()) {
                    Method[] declaredMethods = superclass.getDeclaredMethods();
                    int length2 = declaredMethods.length;
                    int i2 = 0;
                    while (true) {
                        if (i2 >= length2) {
                            break;
                        }
                        if (str.equals(declaredMethods[i2].getName())) {
                            z = true;
                            break;
                        }
                        i2++;
                    }
                }
            }
            if (!z) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:7:0x001e  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static void notifySettingsPage(Context context, Intent intent) {
        try {
            if (intent != null && intent.hasExtra("physicalDocked")) {
                int iDocked = intent.getIntExtra("physicalDocked", -1);
                if (iDocked == 0 || iDocked == 1) {
                    HookUtils.setPhysicalDocked(context, iDocked == 1);
                }
            }
            PenState penState = HookUtils.state(context);
            boolean zHardware = isHardwareBatteryIntent(intent);
            int iBattery = HookUtils.hardwareBattery(context);
            boolean zDisconnected = intent != null && intent.hasExtra("connected") && intent.getIntExtra("connected", 1) == 0;
            if (iBattery < 0 && (HookUtils.disconnectRequested(context) || zDisconnected)) {
                iBattery = HookUtils.lastValidBattery(context);
            }
            if (zHardware && intent != null) {
                if (intent.hasExtra("batteryLevel")) {
                    iBattery = intent.getIntExtra("batteryLevel", -1);
                } else if (intent.hasExtra("battery_level")) {
                    iBattery = intent.getIntExtra("battery_level", -1);
                }
            }
            if (iBattery < 0 || iBattery > 100) {
                iBattery = -1;
            }
            int iCharging = chargingExtra(intent, penState.charging);
            int iOemCharging = HookUtils.oemCharging(context);
            if (iOemCharging >= 0) {
                iCharging = iOemCharging;
            }
            if (HookUtils.physicalDocked(context) == 0 && HookUtils.oemCharging(context) < 0) {
                iCharging = 0;
            }
            if (iBattery >= 0) {
                HookUtils.setIpePreferenceInt(context, "pencil_sp_battery_level", iBattery);
            }
            HookUtils.setIpePreferenceInt(context, "pencil_sp_charging_state", iCharging);
            refreshDeviceCardData(context);
            Object objCallback = settingsCallback;
            Object objCallback2 = settingsCallbackOf(settingsActivity);
            if (objCallback2 != null) {
                settingsCallback = objCallback2;
                objCallback = objCallback2;
            }
            boolean zDirect = false;
            if (objCallback != null) {
                if (iBattery >= 0) {
                    invoke(objCallback, "notifyBatteryLevel", Integer.valueOf(iBattery));
                }
                boolean zChargingNotify = invoke(objCallback, "notifyChargingState", Boolean.valueOf(iCharging != 0));
                zDirect = !zChargingNotify;
            }
            if (zDirect) {
                HookUtils.log("IPe settings direct callback updated: battery=" + iBattery + " charging=" + iCharging);
            }
            Object objFragment = settingsFragment;
            if (objFragment != null && invoke(objFragment, "h")) {
                HookUtils.log("IPe settings fragment refreshed: battery=" + iBattery + " charging=" + iCharging);
                zDirect = true;
            }
            refreshWritingHapticPreference();
            Object objBle = fieldAny(coreService, "f1865b", "b");
            if (objBle == null) {
                objBle = fieldByTypeName(coreService, "com.oplus.ipemanager.btadsorb.ble.s0");
            }
            if (objBle == null) {
                objBle = coreBleManager;
            }
            if (notifyStockHardwareCallbacks(objBle, iBattery, iCharging)) {
                HookUtils.log("IPe OEM UI callbacks updated: battery=" + iBattery + " charging=" + iCharging);
            } else if (!zDirect) {
                HookUtils.log("IPe settings callback unavailable: battery=" + iBattery + " charging=" + iCharging);
            }
        } catch (Throwable th) {
            HookUtils.log("IPe settings Binder update: " + th);
        }
    }

    private static boolean notifyStockHardwareCallbacks(Object obj, int i, int i2) {
        Object objField = field(obj, "S0");
        Object objField2 = field(obj, "Y0");
        boolean zNotifyStockPanel = obj != null ? notifyStockPanel(objField2, i, i2) | notifyStockPageControl(field(obj, "B0"), i, i2) | notifyStockPageControl(fieldAny(obj, "f1982y0", "y0"), i, i2) | notifyStockPageControl(field(obj, "A0"), i, i2) | notifyStockPanel(objField, i, i2) : false;
        synchronized (IpeManagerHooks.class) {
            for (Object obj2 : pencilPanelCallbacks) {
                if (obj2 != null && obj2 != objField && obj2 != objField2) {
                    zNotifyStockPanel |= notifyStockPanel(obj2, i, i2);
                }
            }
        }
        return (zNotifyStockPanel || pencilPanelCallback == null || pencilPanelCallback == objField || pencilPanelCallback == objField2) ? zNotifyStockPanel : notifyStockPanel(pencilPanelCallback, i, i2);
    }

    private static boolean notifyStockPageControl(Object obj, int i, int i2) {
        if (!binderAlive(obj)) {
            return false;
        }
        boolean zInvoke = i >= 0 ? invoke(obj, "notifyBatteryLevel", Integer.valueOf(i)) : false;
        if (i2 >= 0) {
            return invoke(obj, "notifyChargingState", Boolean.valueOf(i2 != 0)) | zInvoke;
        }
        return zInvoke;
    }

    private static boolean notifyStockPanel(Object obj, int i, int i2) {
        if (!binderAlive(obj)) {
            return false;
        }
        boolean zInvoke = i >= 0 ? invoke(obj, "onBatteryLevel", Integer.valueOf(i)) : false;
        if (i2 >= 0) {
            return invoke(obj, "onChargingState", Boolean.valueOf(i2 != 0)) | zInvoke;
        }
        return zInvoke;
    }

    private static boolean binderAlive(Object obj) {
        if (obj == null) {
            return false;
        }
        try {
            Object objInvoke = obj.getClass().getMethod("asBinder", new Class[0]).invoke(obj, new Object[0]);
            if (objInvoke instanceof IBinder) {
                if (!((IBinder) objInvoke).isBinderAlive()) {
                    return false;
                }
            }
        } catch (Throwable unused) {
        }
        return true;
    }

    private static boolean invoke(Object obj, String str, Object... objArr) throws SecurityException {
        if (obj != null && str != null) {
            for (Method method : obj.getClass().getMethods()) {
                if (str.equals(method.getName()) && method.getParameterTypes().length == objArr.length) {
                    try {
                        method.setAccessible(true);
                        method.invoke(obj, objArr);
                        return true;
                    } catch (Throwable unused) {
                        continue;
                    }
                }
            }
            for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
                for (Method method2 : superclass.getDeclaredMethods()) {
                    if (str.equals(method2.getName()) && method2.getParameterTypes().length == objArr.length) {
                        try {
                            method2.setAccessible(true);
                            method2.invoke(obj, objArr);
                            return true;
                        } catch (Throwable unused2) {
                            continue;
                        }
                    }
                }
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Object field(Object obj, String str) {
        if (obj != null && str != null) {
            for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
                try {
                    Field declaredField = superclass.getDeclaredField(str);
                    declaredField.setAccessible(true);
                    return declaredField.get(obj);
                } catch (NoSuchFieldException unused) {
                } catch (Throwable unused2) {
                    return null;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Object fieldAny(Object obj, String... strArr) {
        if (strArr == null) {
            return null;
        }
        for (String str : strArr) {
            Object objField = field(obj, str);
            if (objField != null) {
                return objField;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Object fieldByTypeName(Object obj, String str) {
        Object obj2 = null;
        if (obj != null && str != null) {
            for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
                for (Field field : superclass.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        obj2 = field.get(obj);
                    } catch (Throwable unused) {
                    }
                    if (obj2 != null && str.equals(obj2.getClass().getName())) {
                        return obj2;
                    }
                }
            }
        }
        return null;
    }

    private static BluetoothDevice managerDevice(Object obj) {
        Object obj2 = null;
        if (obj == null) {
            return null;
        }
        for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
            for (Field field : superclass.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    obj2 = field.get(obj);
                } catch (Throwable unused) {
                }
                if (obj2 instanceof BluetoothDevice) {
                    return (BluetoothDevice) obj2;
                }
                continue;
            }
        }
        return null;
    }

    private static String storedPenAddress(Context context) {
        String string;
        if (context == null) {
            return "";
        }
        try {
            string = HookUtils.penAddress(context);
        } catch (Throwable unused) {
            string = "";
        }
        if (!isMac(string)) {
            try {
                string = HookUtils.state(context).address;
            } catch (Throwable unused2) {
            }
        }
        if (string == null) {
            return "";
        }
        String strTrim = string.trim();
        if (strTrim.matches("(?i)[0-9a-f]{12}")) {
            StringBuilder sb = new StringBuilder(17);
            int i = 0;
            while (i < 12) {
                if (sb.length() > 0) {
                    sb.append(':');
                }
                int i2 = i + 2;
                sb.append(strTrim.substring(i, i2));
                i = i2;
            }
            strTrim = sb.toString();
        }
        return isMac(strTrim) ? strTrim : "";
    }

    private static boolean isMac(String str) {
        return (str == null || !str.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") || "00:00:00:00:00:00".equalsIgnoreCase(str)) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void scheduleOriginalGattConnect(final Context context, long j) {
        if (context != null) {
            if (!HookUtils.disconnectRequested(context)) {
                Object objFieldByTypeName = coreBleManager;
                if (objFieldByTypeName == null) {
                    objFieldByTypeName = fieldAny(coreService, "f1865b", "b");
                }
                if (objFieldByTypeName == null) {
                    objFieldByTypeName = fieldByTypeName(coreService, "com.oplus.ipemanager.btadsorb.ble.s0");
                }
                if (objFieldByTypeName == null) {
                    return;
                }
                coreBleManager = objFieldByTypeName;
                final String strStoredPenAddress = storedPenAddress(context);
                if (isMac(strStoredPenAddress)) {
                    long jUptimeMillis = SystemClock.uptimeMillis();
                    if (stockGattConnectPending) {
                        return;
                    }
                    if (!strStoredPenAddress.equalsIgnoreCase(lastStockGattConnectMac) || jUptimeMillis - lastStockGattConnectAt >= 4500) {
                        stockGattConnectPending = true;
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.19
                            @Override // java.lang.Runnable
                            public void run() {
                                synchronized (IpeManagerHooks.class) {
                                    boolean unused = IpeManagerHooks.stockGattConnectPending = false;
                                }
                                IpeManagerHooks.invokeOriginalGattConnect(context, strStoredPenAddress, 2);
                            }
                        }, Math.max(0L, j));
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void invokeOriginalGattConnect(Context context, String str, int i) {
        if (context == null || !isMac(str) || HookUtils.disconnectRequested(context)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (str.equalsIgnoreCase(lastStockGattConnectMac) && now - lastStockGattConnectAt < 1500L) {
            HookUtils.log("IPe duplicate GATT connect suppressed mac=" + str);
            return;
        }
        Object objFieldAny = coreBleManager;
        if (objFieldAny == null || !"com.oplus.ipemanager.btadsorb.ble.s0".equals(objFieldAny.getClass().getName())) {
            objFieldAny = fieldAny(coreService, "f1865b", "b");
            if (objFieldAny == null) {
                objFieldAny = fieldByTypeName(coreService, "com.oplus.ipemanager.btadsorb.ble.s0");
            }
            if (objFieldAny != null) {
                coreBleManager = objFieldAny;
            }
        }
        if (objFieldAny == null) {
            HookUtils.log("IPe stock GATT connect deferred: s0 not ready");
            return;
        }
        try {
            // s0.c(Integer, String) is only the OEM profile/UI callback. It
            // does not open a BluetoothGatt. The actual BLE entry point is
            // the inherited g.b(String), which calls BluetoothDevice
            // connectGatt(). Calling c() here made the UI look connected
            // while no GATT connection was ever started.
            Method method2 = method(objFieldAny, "b", String.class);
            if (method2 != null) {
                method2.setAccessible(true);
                method2.invoke(objFieldAny, str);
                lastStockGattConnectMac = str;
                lastStockGattConnectAt = SystemClock.uptimeMillis();
                HookUtils.log("IPe original BleManager.b GATT connect requested mac=" + str);
                return;
            }
            Object objFieldByTypeName = fieldByTypeName(objFieldAny, "com.oplus.ipemanager.btadsorb.ble.f0");
            Method method3 = objFieldByTypeName == null ? null : method(objFieldByTypeName, "connect", String.class);
            if (method3 != null) {
                method3.setAccessible(true);
                method3.invoke(objFieldByTypeName, str);
                lastStockGattConnectMac = str;
                lastStockGattConnectAt = SystemClock.uptimeMillis();
                HookUtils.log("IPe original f0.connect requested mac=" + str);
                return;
            }
            HookUtils.log("IPe original GATT connect entry not found");
        } catch (Throwable th) {
            HookUtils.log("IPe original GATT connect failed: " + th);
        }
    }

    private static Method method(Object obj, String str, Class<?>... clsArr) {
        if (obj == null) {
            return null;
        }
        for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
            try {
                return superclass.getDeclaredMethod(str, clsArr);
            } catch (NoSuchMethodException unused) {
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Object objectFieldOfType(Object obj, String str) {
        Object obj2 = null;
        if (obj != null && str != null) {
            for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
                for (Field field : superclass.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        obj2 = field.get(obj);
                    } catch (Throwable unused) {
                    }
                    if (obj2 != null && str.equals(obj2.getClass().getName())) {
                        return obj2;
                    }
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Type inference failed for: r8v1, types: [android.content.Intent] */
    /* JADX WARN: Type inference failed for: r8v2 */
    /* JADX WARN: Type inference failed for: r8v3 */
    /* JADX WARN: Type inference failed for: r8v4, types: [int] */
    /* JADX WARN: Type inference failed for: r8v5 */
    /* JADX WARN: Type inference failed for: r8v6 */
    public static int chargingExtra(Intent intent, int i) {
        if (intent == null) {
            return i;
        }
        String[] strArrNumeric = {"chargingState", "charging", "charge_state"};
        for (String strKey : strArrNumeric) {
            if (intent.hasExtra(strKey)) {
                try {
                    Object obj = intent.getExtras() == null ? null : intent.getExtras().get(strKey);
                    if (obj instanceof Number) {
                        return ((Number) obj).intValue();
                    }
                    if (obj instanceof Boolean) {
                        return ((Boolean) obj).booleanValue() ? 1 : 0;
                    }
                    if (obj instanceof String) {
                        return Integer.parseInt(((String) obj).trim());
                    }
                } catch (Throwable unused) {
                }
            }
        }
        String[] strArrString = {"charging_state", "CHARGING_STATE"};
        for (String strKey2 : strArrString) {
            if (intent.hasExtra(strKey2)) {
                try {
                    Object obj2 = intent.getExtras() == null ? null : intent.getExtras().get(strKey2);
                    String lowerCase = obj2 == null ? "" : String.valueOf(obj2).trim().toLowerCase();
                    if ("charging".equals(lowerCase) || "charge".equals(lowerCase) || "wireless charging".equals(lowerCase) || "wireless_charging".equals(lowerCase) || "1".equals(lowerCase)) {
                        return 1;
                    }
                    if ("full".equals(lowerCase) || "not charging".equals(lowerCase) || "not_charging".equals(lowerCase) || "discharging".equals(lowerCase) || "idle".equals(lowerCase) || "none".equals(lowerCase) || "0".equals(lowerCase)) {
                        return 0;
                    }
                } catch (Throwable unused2) {
                }
            }
        }
        return i;
    }

    private static boolean isHardwareBatteryIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String stringExtra = intent.getStringExtra("source");
        if (!"kernel_pen_framework".equals(stringExtra) || intent.getBooleanExtra("hardware_identity_known", false)) {
            return intent.getBooleanExtra("hardware_battery", false) || "kernel_pen_framework".equals(stringExtra) || "ipe_ble_gatt".equals(stringExtra) || "ipe_ble_gatt_charge".equals(stringExtra);
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void showMagneticCapsule(final Context context, int i, int i2) {
        if (context == null) {
            return;
        }
        if (i < 0) {
            try {
                i = HookUtils.batteryForCapsule(context);
            } catch (Throwable th) {
                throw th;
            }
        }
        if (i < 0) {
            HookUtils.log("stock magnetic capsule deferred: battery unknown");
            return;
        }
        long jElapsedRealtime = SystemClock.elapsedRealtime();
        if (jElapsedRealtime - lastCapsuleAt < 1200) {
            return;
        }
        lastCapsuleAt = jElapsedRealtime;
        pendingCapsuleBattery = Math.max(0, Math.min(100, i));
        pendingCapsuleCharging = Math.max(0, i2);
        if (capsuleControl != null) {
            invokeBatteryCapsule(capsuleControl, pendingCapsuleBattery, pendingCapsuleCharging);
            pendingCapsuleBattery = -1;
            pendingCapsuleCharging = -1;
        } else {
            if (capsuleBinding) {
                return;
            }
            capsuleBinding = true;
            capsuleConnection = new ServiceConnection() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.20
                @Override // android.content.ServiceConnection
                public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                    int i3 = IpeManagerHooks.pendingCapsuleBattery;
                    int i4 = IpeManagerHooks.pendingCapsuleCharging;
                    int unused = IpeManagerHooks.pendingCapsuleBattery = -1;
                    int unused2 = IpeManagerHooks.pendingCapsuleCharging = -1;
                    boolean unused3 = IpeManagerHooks.capsuleBinding = false;
                    try {
                        Method declaredMethod = Class.forName("n2.h", false, IpeManagerHooks.ipeClassLoader).getDeclaredMethod("b", IBinder.class);
                        declaredMethod.setAccessible(true);
                        Object unused4 = IpeManagerHooks.capsuleControl = declaredMethod.invoke(null, iBinder);
                        if (i3 < 0) {
                            return;
                        }
                        if (IpeManagerHooks.capsuleControl != null) {
                            IpeManagerHooks.invokeBatteryCapsule(IpeManagerHooks.capsuleControl, i3, i4);
                        }
                    } catch (Throwable th2) {
                        Object unused5 = IpeManagerHooks.capsuleControl = null;
                        HookUtils.log("IPe stock capsule Binder: " + th2);
                    }
                }

                @Override // android.content.ServiceConnection
                public void onServiceDisconnected(ComponentName componentName) {
                    Object unused = IpeManagerHooks.capsuleControl = null;
                    boolean unused2 = IpeManagerHooks.capsuleBinding = false;
                }

                @Override // android.content.ServiceConnection
                public void onBindingDied(ComponentName componentName) {
                    Object unused = IpeManagerHooks.capsuleControl = null;
                    boolean unused2 = IpeManagerHooks.capsuleBinding = false;
                }

                @Override // android.content.ServiceConnection
                public void onNullBinding(ComponentName componentName) {
                    Object unused = IpeManagerHooks.capsuleControl = null;
                    boolean unused2 = IpeManagerHooks.capsuleBinding = false;
                }
            };
            try {
                if (!context.bindService(new Intent().setClassName("com.oplus.ipemanager", "com.oplus.ipemanager.btadsorb.UIService"), capsuleConnection, 1)) {
                    capsuleBinding = false;
                    HookUtils.log("IPe stock capsule UIService bind returned false");
                }
            } catch (Throwable th2) {
                capsuleBinding = false;
                HookUtils.log("IPe stock capsule UIService bind: " + th2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void invokeBatteryCapsule(Object obj, int i, int i2) {
        Object objAdapt;
        if (obj == null) {
            return;
        }
        int iMax = Math.max(0, Math.min(100, i));
        try {
            for (Method method : obj.getClass().getMethods()) {
                if ("showBatteryCapsule".equals(method.getName()) && method.getParameterTypes().length == 1 && (objAdapt = HookUtils.adapt(method.getParameterTypes()[0], Integer.valueOf(iMax))) != null) {
                    method.setAccessible(true);
                    method.invoke(obj, objAdapt);
                    HookUtils.log("stock magnetic capsule shown: battery=" + iMax + " OEM one-arg");
                    return;
                }
            }
            for (Method method2 : obj.getClass().getMethods()) {
                if ("showBatteryCapsule".equals(method2.getName())) {
                    Class<?>[] parameterTypes = method2.getParameterTypes();
                    if (parameterTypes.length == 2) {
                        Object objAdapt2 = HookUtils.adapt(parameterTypes[0], Integer.valueOf(iMax));
                        Object objAdapt3 = HookUtils.adapt(parameterTypes[1], 0);
                        if (objAdapt2 != null && objAdapt3 != null) {
                            method2.setAccessible(true);
                            method2.invoke(obj, objAdapt2, objAdapt3);
                            HookUtils.log("stock magnetic capsule shown: battery=" + iMax + " OEM fallback");
                            return;
                        }
                    } else {
                        continue;
                    }
                }
            }
            obj.getClass().getMethod("showBatteryCapsule", Integer.TYPE).invoke(obj, Integer.valueOf(iMax));
            HookUtils.log("stock magnetic capsule shown: battery=" + iMax + " charging=" + i2);
        } catch (Throwable th) {
            capsuleControl = null;
            HookUtils.log("IPe stock showBatteryCapsule: " + th);
        }
    }

    private static String firstString(Intent intent, String... strArr) {
        for (String str : strArr) {
            String stringExtra = intent.getStringExtra(str);
            if (stringExtra != null && !stringExtra.trim().isEmpty()) {
                return stringExtra.trim();
            }
        }
        return "";
    }

    private static void installGestureTextBridge(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "android.content.res.Resources", "getText", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.21
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (methodHookParam.args == null || methodHookParam.args.length == 0 || !(methodHookParam.args[0] instanceof Integer) || methodHookParam.getResult() == null) {
                    return;
                }
                Resources resources = (Resources) methodHookParam.thisObject;
                int iIntValue = ((Integer) methodHookParam.args[0]).intValue();
                try {
                    String resourceEntryName = resources.getResourceEntryName(iIntValue);
                    if ("com.oplus.ipemanager".equals(resources.getResourcePackageName(iIntValue))) {
                        String strValueOf = String.valueOf(methodHookParam.getResult());
                        String strRewriteGestureText = IpeManagerHooks.rewriteGestureText(resourceEntryName, strValueOf);
                        if (strValueOf.equals(strRewriteGestureText)) {
                            return;
                        }
                        methodHookParam.setResult(strRewriteGestureText);
                    }
                } catch (Throwable unused) {
                }
            }
        });
    }

    private static void installWritingHapticPreference(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.22
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                IpeManagerHooks.configureWritingHapticPreference(loadPackageParam, methodHookParam.thisObject);
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.fragment.t0", "onCreate", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.fragment.t0", "onCreatePreferences", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.fragment.t0", "onResume", xC_MethodHook);
    }

    private static void refreshWritingHapticPreference() {
        Object obj = settingsFragment;
        ClassLoader classLoader = ipeClassLoader;
        if (obj == null || classLoader == null) {
            return;
        }
        configureWritingHapticPreference(classLoader, obj);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void configureWritingHapticPreference(XC_LoadPackage.LoadPackageParam loadPackageParam, Object obj) {
        configureWritingHapticPreference(loadPackageParam == null ? null : loadPackageParam.classLoader, obj);
    }

    private static void configureWritingHapticPreference(ClassLoader classLoader, Object obj) {
        Object objInvoke;
        try {
            final Context context = HookUtils.context(obj);
            if (context != null && (objInvoke = obj.getClass().getMethod("getPreferenceScreen", new Class[0]).invoke(obj, new Object[0])) != null) {
                Class<?> cls = Class.forName("androidx.preference.Preference", false, classLoader);
                Method method = objInvoke.getClass().getMethod("findPreference", CharSequence.class);
                Object objInvoke2 = method.invoke(objInvoke, "global_operation");
                if (objInvoke2 == null) {
                    return;
                }
                Object objInvoke3 = method.invoke(objInvoke, "lenovo_pen_global_writing_haptic");
                if (objInvoke3 == null) {
                    objInvoke3 = method.invoke(objInvoke, "write_sound_effect_lite");
                }
                if (objInvoke3 == null) {
                    objInvoke3 = method.invoke(objInvoke, "write_sound_effect");
                }
                if (objInvoke3 == null) {
                    HookUtils.log("writing haptic preference: no reusable switch");
                    return;
                }
                Object objInvoke4 = cls.getMethod("getParent", new Class[0]).invoke(objInvoke3, new Object[0]);
                if (objInvoke4 != objInvoke2) {
                    if (objInvoke4 != null) {
                        objInvoke4.getClass().getMethod("removePreference", cls).invoke(objInvoke4, objInvoke3);
                    }
                    cls.getMethod("setKey", String.class).invoke(objInvoke3, "lenovo_pen_global_writing_haptic");
                    objInvoke2.getClass().getMethod("addPreference", cls).invoke(objInvoke2, objInvoke3);
                }
                cls.getMethod("setTitle", CharSequence.class).invoke(objInvoke3, "全局书写震动");
                cls.getMethod("setSummary", CharSequence.class).invoke(objInvoke3, "书写时启用手写笔连续触觉反馈");
                cls.getMethod("setPersistent", Boolean.TYPE).invoke(objInvoke3, false);
                PenState penStateState = HookUtils.state(context);
                boolean z = penStateState.connected && !HookUtils.disconnectRequested(context);
                cls.getMethod("setSelectable", Boolean.TYPE).invoke(objInvoke3, true);
                cls.getMethod("setEnabled", Boolean.TYPE).invoke(objInvoke3, Boolean.valueOf(z));
                cls.getMethod("setVisible", Boolean.TYPE).invoke(objInvoke3, true);
                objInvoke3.getClass().getMethod("setIsSupportCardUse", Boolean.TYPE).invoke(objInvoke3, true);
                objInvoke3.getClass().getMethod("setChecked", Boolean.TYPE).invoke(objInvoke3, Boolean.valueOf(Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_global_writing_haptic", 1) != 0));
                Class<?> cls2 = Class.forName("androidx.preference.Preference$OnPreferenceChangeListener", false, classLoader);
                cls.getMethod("setOnPreferenceChangeListener", cls2).invoke(objInvoke3, Proxy.newProxyInstance(classLoader, new Class[]{cls2}, new InvocationHandler() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks$$ExternalSyntheticLambda0
                    @Override // java.lang.reflect.InvocationHandler
                    public final Object invoke(Object obj2, Method method2, Object[] objArr) throws Throwable {
                        return IpeManagerHooks.lambda$configureWritingHapticPreference$0(context, obj2, method2, objArr);
                    }
                }));
                HookUtils.log("global writing haptic switch configured enabled=" + z + " connected=" + penStateState.connected);
            }
        } catch (Throwable th) {
            HookUtils.log("writing haptic preference: " + th);
        }
    }

    static /* synthetic */ Object lambda$configureWritingHapticPreference$0(Context context, Object obj, Method method, Object[] objArr) throws Throwable {
        if ("onPreferenceChange".equals(method.getName()) && objArr != null && objArr.length > 1) {
            if (!HookUtils.state(context).connected || HookUtils.disconnectRequested(context)) {
                HookUtils.log("global writing haptic change ignored while pen disconnected");
            } else {
                boolean zEquals = Boolean.TRUE.equals(objArr[1]);
                Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_global_writing_haptic", zEquals ? 1 : 0);
                context.sendBroadcast(new Intent("com.aclaniakea.lenovopenbridge.haptic.COMMAND").putExtra("enabled", zEquals));
                HookUtils.log("global writing haptic=" + zEquals);
                return true;
            }
        }
        return false;
    }

    private static void installMyDevicesStateBridge(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.mydevices.c", "notifyConnectState", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.23
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context == null || methodHookParam.args == null || methodHookParam.args.length == 0) {
                    return;
                }
                String strValueOf = (methodHookParam.args.length <= 1 || methodHookParam.args[1] == null) ? "" : String.valueOf(methodHookParam.args[1]);
                if ((IpeManagerHooks.samePenAddress(HookUtils.state(context).address, strValueOf) || strValueOf.length() <= 0) && IpeManagerHooks.numberArg(methodHookParam.args[0]) > 0 && HookUtils.disconnectRequested(context)) {
                    methodHookParam.args[0] = HookUtils.adapt(((Method) methodHookParam.method).getParameterTypes()[0], 0);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.activity.e", "notifyConnectState", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.24
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context == null || methodHookParam.args == null || methodHookParam.args.length == 0) {
                    return;
                }
                String strValueOf = (methodHookParam.args.length <= 1 || methodHookParam.args[1] == null) ? "" : String.valueOf(methodHookParam.args[1]);
                if ((IpeManagerHooks.samePenAddress(HookUtils.state(context).address, strValueOf) || strValueOf.length() <= 0) && IpeManagerHooks.numberArg(methodHookParam.args[0]) > 0 && HookUtils.disconnectRequested(context)) {
                    methodHookParam.args[0] = HookUtils.adapt(((Method) methodHookParam.method).getParameterTypes()[0], 0);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.activity.e", "notifyBatteryLevel", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.25
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context == null || methodHookParam.args == null || methodHookParam.args.length == 0) {
                    return;
                }
                int iHardwareBattery = HookUtils.hardwareBattery(context);
                if (iHardwareBattery < 0) {
                    iHardwareBattery = HookUtils.lastValidBattery(context);
                }
                methodHookParam.args[0] = HookUtils.adapt(((Method) methodHookParam.method).getParameterTypes()[0], Integer.valueOf(iHardwareBattery));
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.activity.e", "notifyChargingState", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.26
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context == null || methodHookParam.args == null || methodHookParam.args.length == 0) {
                    return;
                }
                PenState penStateState = HookUtils.state(context);
                methodHookParam.args[0] = HookUtils.adapt(((Method) methodHookParam.method).getParameterTypes()[0], Boolean.valueOf(penStateState.connected && penStateState.charging != 0));
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.setting.activity.e", "notifyStateResult", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.27
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context == null || methodHookParam.args == null || methodHookParam.args.length == 0 || HookUtils.state(context).address.length() <= 0) {
                    return;
                }
                methodHookParam.args[0] = HookUtils.adapt(((Method) methodHookParam.method).getParameterTypes()[0], true);
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "w2.j", "b", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.28
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                Object obj = (methodHookParam.args == null || methodHookParam.args.length <= 0) ? null : methodHookParam.args[0];
                if (context == null || obj == null || !"v2.c".equals(obj.getClass().getName())) {
                    return;
                }
                String strValueOf = String.valueOf(IpeManagerHooks.field(obj, "f5119b"));
                PenState penStateState = HookUtils.state(context);
                if (IpeManagerHooks.samePenAddress(penStateState.address, strValueOf)) {
                    int iLastValidBattery = penStateState.battery;
                    if (iLastValidBattery < 0 && !penStateState.connected) {
                        iLastValidBattery = HookUtils.lastValidBattery(context);
                    }
                    IpeManagerHooks.setField(obj, "f5121d", Integer.valueOf(iLastValidBattery));
                    IpeManagerHooks.setField(obj, "f5122e", Boolean.valueOf(penStateState.connected && penStateState.charging != 0));
                    if (penStateState.connected) {
                        return;
                    }
                    IpeManagerHooks.setField(obj, "f5123f", 0);
                    HookUtils.log("IPe DeviceDetail state gated disconnected mac=" + strValueOf);
                }
            }
        });
        HookUtils.log("IPe MyDevices state bridge installed");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int numberArg(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }
        if (obj instanceof Boolean) {
            return ((Boolean) obj).booleanValue() ? 1 : 0;
        }
        try {
            return Integer.parseInt(String.valueOf(obj));
        } catch (Throwable unused) {
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean samePenAddress(String str, String str2) {
        return str != null && str2 != null && str.length() > 0 && str2.length() > 0 && str.replace(":", "").equalsIgnoreCase(str2.replace(":", ""));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void setField(Object obj, String str, Object obj2) {
        if (obj == null || str == null) {
            return;
        }
        for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
            try {
                Field declaredField = superclass.getDeclaredField(str);
                declaredField.setAccessible(true);
                declaredField.set(obj, HookUtils.adapt(declaredField.getType(), obj2));
                return;
            } catch (NoSuchFieldException unused) {
            } catch (Throwable unused2) {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String rewriteGestureText(String str, String str2) {
        if (str != null && str2 != null) {
            String lowerCase = str.toLowerCase();
            if (lowerCase.contains("single_click")) {
                String strReplace = str2.replace("单击笔身按键", "下滑触控条").replace("单击按键", "下滑触控条");
                return (strReplace.equals(str2) && lowerCase.endsWith("title")) ? "下滑触控条" : strReplace;
            }
            if (lowerCase.contains("long_click") || lowerCase.contains("long_press")) {
                String strReplace2 = str2.replace("长按笔身按键", "上滑触控条").replace("长按按键", "上滑触控条");
                return (strReplace2.equals(str2) && lowerCase.endsWith("title")) ? "上滑触控条" : strReplace2;
            }
            if (lowerCase.contains("double_click")) {
                String strReplace3 = str2.replace("双击笔身前端", "双击触控条").replace("双击笔身按键", "双击触控条").replace("双击笔身", "双击触控条").replace("双击按键", "双击触控条");
                return (strReplace3.equals(str2) && lowerCase.endsWith("title")) ? "双击触控条" : strReplace3;
            }
        }
        return str2;
    }

    private static void hookGetter(XC_LoadPackage.LoadPackageParam loadPackageParam, String str, String str2, final int i) {
        HookUtils.hookAll(loadPackageParam.classLoader, str, str2, new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.29
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                int iConnectState;
                Object objValueOf;
                PenState penStateState = HookUtils.state(HookUtils.context(methodHookParam.thisObject));
                int i2 = i;
                if (i2 == 1 || i2 == 3) {
                    Integer numValueOf = Integer.valueOf(i2 == 1 ? penStateState.connectState() : penStateState.charging);
                    try {
                        Object objAdapt = HookUtils.adapt(((Method) methodHookParam.method).getReturnType(), numValueOf);
                        if (objAdapt != null) {
                            methodHookParam.setResult(objAdapt);
                            return;
                        }
                        return;
                    } catch (Throwable unused) {
                        if (numValueOf != null) {
                            methodHookParam.setResult(numValueOf);
                            return;
                        }
                        return;
                    }
                }
                if (penStateState.connected || i >= 4) {
                    switch (i) {
                        case 0:
                            iConnectState = penStateState.battery;
                            objValueOf = Integer.valueOf(iConnectState);
                            break;
                        case 1:
                            iConnectState = penStateState.connectState();
                            objValueOf = Integer.valueOf(iConnectState);
                            break;
                        case 2:
                            objValueOf = penStateState.address;
                            break;
                        case 3:
                            iConnectState = penStateState.charging;
                            objValueOf = Integer.valueOf(iConnectState);
                            break;
                        case 4:
                            objValueOf = penStateState.name;
                            break;
                        case 5:
                            objValueOf = penStateState.type;
                            break;
                        case 6:
                            objValueOf = penStateState.firmware;
                            break;
                        case 7:
                            objValueOf = penStateState.hardware;
                            break;
                        default:
                            objValueOf = penStateState.serial;
                            break;
                    }
                    try {
                        Object objAdapt2 = HookUtils.adapt(((Method) methodHookParam.method).getReturnType(), objValueOf);
                        if (objAdapt2 != null) {
                            methodHookParam.setResult(objAdapt2);
                        }
                    } catch (Throwable unused2) {
                        if (objValueOf != null) {
                            methodHookParam.setResult(objValueOf);
                        }
                    }
                }
            }
        });
    }

    private static void installWhitelist(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        String[] strArr = {"N", "W", "P"};
        for (int i = 0; i < 3; i++) {
            HookUtils.hookAll(loadPackageParam.classLoader, "h3.t", strArr[i], new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.30
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (IpeManagerHooks.isLenovoName(IpeManagerHooks.stringArg(methodHookParam.args))) {
                        methodHookParam.setResult(true);
                    }
                }
            });
        }
        HookUtils.hookAll(loadPackageParam.classLoader, "h3.t", "n", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.31
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (IpeManagerHooks.isLenovoName(IpeManagerHooks.stringArg(methodHookParam.args))) {
                    methodHookParam.setResult(HookUtils.adapt(((Method) methodHookParam.method).getReturnType(), "PENCIL"));
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "h3.t", "X", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.32
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (IpeManagerHooks.isKnown(IpeManagerHooks.contextArg(methodHookParam.args), IpeManagerHooks.stringArg(methodHookParam.args))) {
                    methodHookParam.setResult(true);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "h3.t", "e", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.33
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (methodHookParam.getResult() != null) {
                    return;
                }
                PenState penStateState = HookUtils.state(IpeManagerHooks.contextArg(methodHookParam.args));
                if (!penStateState.connected || penStateState.address.length() == 0) {
                    return;
                }
                try {
                    methodHookParam.setResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(penStateState.address));
                } catch (Throwable unused) {
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "h3.t", "v", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.34
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (IpeManagerHooks.isKnown(context, IpeManagerHooks.stringArg(methodHookParam.args))) {
                    methodHookParam.setResult(HookUtils.state(context).name);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "h3.t", "j", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.35
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (IpeManagerHooks.isKnown(IpeManagerHooks.contextArg(methodHookParam.args), IpeManagerHooks.stringArg(methodHookParam.args))) {
                    methodHookParam.setResult("Ivy Pencil");
                }
            }
        });
        String[] strArr2 = {"w", "r"};
        for (int i2 = 0; i2 < 2; i2++) {
            HookUtils.hookAll(loadPackageParam.classLoader, "h3.t", strArr2[i2], new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.36
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (IpeManagerHooks.isLenovoName(IpeManagerHooks.stringArg(methodHookParam.args))) {
                        methodHookParam.setResult("Ivy");
                    }
                }
            });
        }
        HookUtils.hookAll(loadPackageParam.classLoader, "h3.t", "x", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.37
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                String strStringArg = IpeManagerHooks.stringArg(methodHookParam.args);
                if (IpeManagerHooks.isLenovoName(strStringArg) || IpeManagerHooks.isKnown(context, strStringArg)) {
                    methodHookParam.setResult(HookUtils.adapt(((Method) methodHookParam.method).getReturnType(), "SECOND_GENERATION_PENCIL_LITE"));
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "h3.h", "d", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.38
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Object result = methodHookParam.getResult();
                if (result instanceof Collection) {
                    try {
                        ((Collection) result).addAll(Arrays.asList("Lenovo Precision Pen 3", "Lenovo Tab Pen Plus", "Lenovo Tab Pen Pro", "Lenovo Tab Pen Pro 2", "PICASSO"));
                    } catch (Throwable unused) {
                    }
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "h3.h", "e", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.39
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (IpeManagerHooks.isLenovoName(IpeManagerHooks.stringArg(methodHookParam.args))) {
                    methodHookParam.setResult("Ivy");
                }
            }
        });
    }

    private static void installStockProfileStateBridge(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.s0", "V", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.40
            /* JADX WARN: Removed duplicated region for block: B:22:0x003f  */
            /* JADX WARN: Removed duplicated region for block: B:31:0x0059  */
            /*
                Code decompiled incorrectly, please refer to instructions dump.
                To view partially-correct add '--show-bad-code' argument
            */
            protected void beforeHookedMethod(de.robv.android.xposed.XC_MethodHook.MethodHookParam methodHookParam) {
                /*
                    r8 = this;
                    java.lang.Object r8 = r9.thisObject
                    android.content.Context r8 = com.aclaniakea.colorosporttuning.HookUtils.context(r8)
                    java.lang.Object[] r0 = r9.args
                    android.content.Intent r0 = com.aclaniakea.colorosporttuning.IpeManagerHooks.access$300(r0)
                    if (r8 == 0) goto Le3
                    if (r0 != 0) goto L12
                    goto Le3
                L12:
                    java.lang.String r1 = "android.bluetooth.profile.extra.STATE"
                    r2 = -2147483648(0xffffffff80000000, float:-0.0)
                    int r1 = r0.getIntExtra(r1, r2)
                    r2 = 2
                    if (r1 == 0) goto L21
                    if (r1 == r2) goto L21
                    goto Le3
                L21:
                    r3 = 0
                    java.lang.String r4 = "android.bluetooth.device.extra.DEVICE"
                    android.os.Parcelable r0 = r0.getParcelableExtra(r4)     // Catch: java.lang.Throwable -> L2f
                    boolean r4 = r0 instanceof android.bluetooth.BluetoothDevice     // Catch: java.lang.Throwable -> L2f
                    if (r4 == 0) goto L2f
                    android.bluetooth.BluetoothDevice r0 = (android.bluetooth.BluetoothDevice) r0     // Catch: java.lang.Throwable -> L2f
                    goto L30
                L2f:
                    r0 = r3
                L30:
                    java.lang.String r4 = ""
                    if (r0 == 0) goto L3f
                    java.lang.String r5 = r0.getAddress()     // Catch: java.lang.Throwable -> L3f
                    if (r5 == 0) goto L3f
                    java.lang.String r5 = r0.getAddress()     // Catch: java.lang.Throwable -> L3f
                    goto L40
                L3f:
                    r5 = r4
                L40:
                    com.aclaniakea.colorosporttuning.PenState r6 = com.aclaniakea.colorosporttuning.HookUtils.state(r8)
                    int r7 = r5.length()
                    if (r7 != 0) goto L4c
                    java.lang.String r5 = r6.address
                L4c:
                    if (r0 == 0) goto L59
                    java.lang.String r6 = r0.getName()     // Catch: java.lang.Throwable -> L59
                    if (r6 == 0) goto L59
                    java.lang.String r0 = r0.getName()     // Catch: java.lang.Throwable -> L59
                    goto L5a
                L59:
                    r0 = r4
                L5a:
                    int r6 = r5.length()
                    if (r6 == 0) goto Le3
                    boolean r6 = com.aclaniakea.colorosporttuning.IpeManagerHooks.access$1300(r8, r5)
                    if (r6 != 0) goto L6d
                    boolean r0 = com.aclaniakea.colorosporttuning.IpeManagerHooks.access$3400(r0)
                    if (r0 != 0) goto L6d
                    goto Le3
                L6d:
                    r0 = 0
                    if (r1 != r2) goto Lb6
                    boolean r1 = com.aclaniakea.colorosporttuning.HookUtils.disconnectRequested(r8)
                    if (r1 == 0) goto L95
                    com.aclaniakea.colorosporttuning.HookUtils.setLinkConnected(r8, r0)
                    java.lang.StringBuilder r8 = new java.lang.StringBuilder
                    java.lang.String r0 = "IPe stock s0.V ignored late CONNECTED mac="
                    r8.<init>(r0)
                    java.lang.StringBuilder r8 = r8.append(r5)
                    java.lang.String r0 = " while disconnect requested"
                    java.lang.StringBuilder r8 = r8.append(r0)
                    java.lang.String r8 = r8.toString()
                    com.aclaniakea.colorosporttuning.HookUtils.log(r8)
                    r9.setResult(r3)
                    return
                L95:
                    r9 = 1
                    com.aclaniakea.colorosporttuning.HookUtils.setLinkConnected(r8, r9)
                    com.aclaniakea.colorosporttuning.IpeManagerHooks.access$3602(r5)
                    long r8 = android.os.SystemClock.elapsedRealtime()
                    com.aclaniakea.colorosporttuning.IpeManagerHooks.access$3702(r8)
                    java.lang.StringBuilder r8 = new java.lang.StringBuilder
                    java.lang.String r9 = "IPe stock s0.V profile CONNECTED mac="
                    r8.<init>(r9)
                    java.lang.StringBuilder r8 = r8.append(r5)
                    java.lang.String r8 = r8.toString()
                    com.aclaniakea.colorosporttuning.HookUtils.log(r8)
                    goto Le3
                Lb6:
                    com.aclaniakea.colorosporttuning.HookUtils.setLinkConnected(r8, r0)
                    java.lang.String r9 = com.aclaniakea.colorosporttuning.IpeManagerHooks.access$3600()
                    boolean r9 = com.aclaniakea.colorosporttuning.IpeManagerHooks.access$3000(r9, r5)
                    if (r9 == 0) goto Lcb
                    com.aclaniakea.colorosporttuning.IpeManagerHooks.access$3602(r4)
                    r0 = 0
                    com.aclaniakea.colorosporttuning.IpeManagerHooks.access$3702(r0)
                Lcb:
                    com.aclaniakea.colorosporttuning.HookUtils.invalidateHardwareBattery(r8)
                    com.aclaniakea.colorosporttuning.HookUtils.invalidateOemCharging(r8)
                    java.lang.StringBuilder r8 = new java.lang.StringBuilder
                    java.lang.String r9 = "IPe stock s0.V profile DISCONNECTED mac="
                    r8.<init>(r9)
                    java.lang.StringBuilder r8 = r8.append(r5)
                    java.lang.String r8 = r8.toString()
                    com.aclaniakea.colorosporttuning.HookUtils.log(r8)
                Le3:
                    return
                */
                Context context2 = HookUtils.context(methodHookParam.thisObject);
                Intent intent2 = IpeManagerHooks.intentArg(methodHookParam.args);
                if (context2 == null || intent2 == null) {
                    return;
                }
                int iState = intent2.getIntExtra("android.bluetooth.profile.extra.STATE", Integer.MIN_VALUE);
                if (iState != 0 && iState != 2) {
                    return;
                }
                BluetoothDevice bluetoothDevice2 = null;
                try {
                    android.os.Parcelable parcelable = intent2.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                    if (parcelable instanceof BluetoothDevice) {
                        bluetoothDevice2 = (BluetoothDevice) parcelable;
                    }
                } catch (Throwable unused4) {
                }
                String strAddress = "";
                if (bluetoothDevice2 != null) {
                    try {
                        String strAddress2 = bluetoothDevice2.getAddress();
                        if (strAddress2 != null) {
                            strAddress = bluetoothDevice2.getAddress();
                        }
                    } catch (Throwable unused5) {
                    }
                }
                PenState penState2 = HookUtils.state(context2);
                if (strAddress.length() == 0) {
                    strAddress = penState2.address;
                }
                String strName = "";
                if (bluetoothDevice2 != null) {
                    try {
                        String strName2 = bluetoothDevice2.getName();
                        if (strName2 != null) {
                            strName = bluetoothDevice2.getName();
                        }
                    } catch (Throwable unused6) {
                    }
                }
                if (strAddress.length() == 0 || (!IpeManagerHooks.isKnown(context2, strAddress) && !IpeManagerHooks.isLenovoName(strName))) {
                    return;
                }
                if (iState == 2) {
                    HookUtils.setLinkConnected(context2, true);
                    IpeManagerHooks.lastStockProfileMac = strAddress;
                    IpeManagerHooks.lastStockProfileConnectedAt = SystemClock.elapsedRealtime();
                    HookUtils.log("IPe stock s0.V profile CONNECTED mac=" + strAddress);
                    return;
                }
                HookUtils.setLinkConnected(context2, false);
                if (IpeManagerHooks.samePenAddress(IpeManagerHooks.lastStockProfileMac, strAddress)) {
                    IpeManagerHooks.lastStockProfileMac = "";
                    IpeManagerHooks.lastStockProfileConnectedAt = 0L;
                }
                HookUtils.invalidateHardwareBattery(context2);
                HookUtils.invalidateOemCharging(context2);
                HookUtils.log("IPe stock s0.V profile DISCONNECTED mac=" + strAddress);
            }

            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws SecurityException {
                Context context = HookUtils.context(methodHookParam.thisObject);
                Intent intentIntentArg = IpeManagerHooks.intentArg(methodHookParam.args);
                if (context == null || intentIntentArg == null || intentIntentArg.getIntExtra("android.bluetooth.profile.extra.STATE", -1) != 0) {
                    return;
                }
                IpeManagerHooks.publishHardwareDisconnected(context, methodHookParam.thisObject, "stock_profile_disconnected");
            }
        });
    }

    private static void installRiskGuard(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        installDisconnectGattGuard(loadPackageParam);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.g", "z", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.41
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                String strStringArg = IpeManagerHooks.stringArg(methodHookParam.args);
                if (context == null || strStringArg.length() == 0) {
                    return;
                }
                PenState penStateState = HookUtils.state(context);
                if (penStateState.address.length() <= 0 || !penStateState.address.replace(":", "").equalsIgnoreCase(strStringArg.replace(":", ""))) {
                    return;
                }
                PenBridgeReceiver.publishDisconnected(context, strStringArg);
            }
        });
        // This ROM has no TouchNode DFX node 32. OEM s0.r0() force-unwraps
        // its null result and kills the entire :ble process. It is only a DFX
        // marker write, so skipping it cannot affect GATT or HID state.
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.s0", "r0", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.43
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                methodHookParam.setResult((Object) null);
                HookUtils.log("IPe stock s0.r0 DFX write skipped: TouchNode 32 is unavailable");
            }
        });
    }

    /** 原厂 dismissCapsule(getShowingCapsule())，走 CapsuleImpl.removeCapsule 的关闭动画。 */
    public static void dismissMagneticCapsule() {
        Object obj = capsuleControl;
        if (obj == null) {
            return;
        }
        try {
            String strShowing = "";
            for (Method method : obj.getClass().getMethods()) {
                if ("getShowingCapsule".equals(method.getName()) && method.getParameterCount() == 0) {
                    Object result = method.invoke(obj);
                    if (result instanceof String) {
                        strShowing = (String) result;
                    }
                    break;
                }
            }
            if (strShowing == null || strShowing.isEmpty()) {
                return;
            }
            for (Method method : obj.getClass().getMethods()) {
                if ("dismissCapsule".equals(method.getName()) && method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class) {
                    method.invoke(obj, strShowing);
                    HookUtils.log("stock magnetic capsule dismissed: " + strShowing);
                    return;
                }
            }
        } catch (Throwable th) {
            HookUtils.log("IPe stock dismissBatteryCapsule: " + th);
        }
    }

    private static void installDisconnectGattGuard(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        // The stock s0 auto-reconnects immediately after a Settings-page
        // disconnect, so the disconnect would never stick. While the explicit
        // user disconnect latch is armed, block the stock GATT connect entry.
        // A user reconnect always sends CONNECT_PENCIL first, which clears the
        // latch in onStartCommand, so this guard never blocks a user retry.
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.g", "b", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.42
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                String strStringArg = IpeManagerHooks.stringArg(methodHookParam.args);
                if (context != null && HookUtils.disconnectRequested(context) && IpeManagerHooks.isKnown(context, strStringArg)) {
                    methodHookParam.setResult((Object) null);
                    HookUtils.log("IPe stock g.b connect blocked by Settings disconnect latch mac=" + strStringArg);
                }
            }
        });
        // This ROM has no TouchNode DFX node 32. OEM s0.r0() force-unwraps
        // its null result and kills the entire :ble process. It is only a DFX
        // marker write, so skipping it cannot affect GATT or HID state.
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.ipemanager.btadsorb.ble.s0", "r0", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.IpeManagerHooks.43
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                methodHookParam.setResult((Object) null);
                HookUtils.log("IPe stock s0.r0 DFX write skipped: TouchNode 32 is unavailable");
            }
        });
    }

    private static void invokeOriginalGattDisconnect(Context context, String str) {
        if (context == null) {
            return;
        }
        lastStockGattConnectMac = "";
        lastStockGattConnectAt = 0L;
        Object objFieldAny = coreBleManager;
        if (objFieldAny == null || !"com.oplus.ipemanager.btadsorb.ble.s0".equals(objFieldAny.getClass().getName())) {
            objFieldAny = fieldAny(coreService, "f1865b", "b");
            if (objFieldAny == null) {
                objFieldAny = fieldByTypeName(coreService, "com.oplus.ipemanager.btadsorb.ble.s0");
            }
            if (objFieldAny != null) {
                coreBleManager = objFieldAny;
            }
        }
        try {
            if (objFieldAny != null) {
                Method method = method(objFieldAny, "a");
                if (method != null) {
                    method.setAccessible(true);
                    method.invoke(objFieldAny);
                    HookUtils.log("IPe original BleManager.a GATT disconnect requested mac=" + (str == null ? "" : str));
                } else {
                    HookUtils.log("IPe original GATT disconnect entry not found");
                }
            } else {
                HookUtils.log("IPe stock GATT disconnect deferred: s0 not ready");
            }
        } catch (Throwable th) {
            HookUtils.log("IPe original GATT disconnect failed: " + th);
        }
        PenHapticGatt.disconnect();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Intent intentArg(Object[] objArr) {
        if (objArr == null) {
            return null;
        }
        for (Object obj : objArr) {
            if (obj instanceof Intent) {
                return (Intent) obj;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String stringArg(Object[] objArr) {
        if (objArr == null) {
            return "";
        }
        for (Object obj : objArr) {
            if (obj instanceof String) {
                return (String) obj;
            }
        }
        return "";
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Context contextArg(Object[] objArr) {
        if (objArr == null) {
            return null;
        }
        for (Object obj : objArr) {
            if (obj instanceof Context) {
                return (Context) obj;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isKnown(Context context, String str) {
        if (str == null) {
            return false;
        }
        if (context == null) {
            context = HookUtils.context(null);
        }
        PenState penStateState = HookUtils.state(context);
        return samePenAddress(penStateState.address, str)
                || samePenAddress(lastStockProfileMac, str)
                || samePenAddress(lastStockGattConnectMac, str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isLenovoName(String str) {
        String lowerCase = str == null ? "" : str.toLowerCase();
        for (String str2 : PenBridgeConstants.LENOVO_NAMES) {
            if (lowerCase.contains(str2)) {
                return true;
            }
        }
        return false;
    }

    static void sync(Context context) {
        PenState penStateState = HookUtils.state(context);
        try {
            for (String str : PenBridgeConstants.CONNECT_KEYS) {
                Settings.Global.putInt(context.getContentResolver(), str, penStateState.connectState());
            }
            int i = penStateState.connected ? 1 : 0;
            Settings.Global.putInt(context.getContentResolver(), "settings_enable_oppo_pencil", i);
            Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_present", i);
            Settings.Global.putString(context.getContentResolver(), "ipe_pencil_mac_addr", penStateState.address);
            if (penStateState.battery >= 0 && penStateState.battery <= 100) {
                Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_battery_level", penStateState.battery);
            }
            Settings.Global.putString(context.getContentResolver(), "ipe_pencil_bt_device_name", penStateState.name);
            put(context, "integer/local_config/settings_enable_oppo_pencil/" + i);
            put(context, "integer/local_config/ipe_pencil_present/" + i);
            HookUtils.setIpePreferenceInt(context, "pencil_sp_charging_state", penStateState.charging);
            if (penStateState.battery >= 0 && penStateState.battery <= 100) {
                HookUtils.setIpePreferenceInt(context, "pencil_sp_battery_level", penStateState.battery);
            }
            put(context, "string/local_config/ipe_pencil_mac_addr/" + Uri.encode(penStateState.address));
        } catch (Throwable th) {
            HookUtils.log("IPe sync: " + th);
        }
    }

    private static void put(Context context, String str) {
        try {
            context.getContentResolver().insert(Uri.parse("content://com.oplus.ipemanager.provider/" + str), null);
        } catch (Throwable unused) {
        }
    }

    private IpeManagerHooks() {
    }
}
