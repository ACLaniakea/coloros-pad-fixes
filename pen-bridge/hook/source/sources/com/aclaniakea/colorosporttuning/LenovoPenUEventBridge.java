package com.aclaniakea.colorosporttuning;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.os.UserHandle;

/* loaded from: classes.dex */
final class LenovoPenUEventBridge extends UEventObserver {
    private static LenovoPenUEventBridge instance = null;
    private static volatile long lastBootOafAt = 0;
    private static volatile String lastBootOafMac = "";
    private static volatile long lastOppoWakeAt = 0;
    private static volatile String lastOppoWakeMac = "";
    private static volatile long lastStateDispatchAt = 0;
    private static volatile String lastStateSignature = "";
    private final Context context;

    private static boolean validBattery(int i) {
        return i >= 0 && i <= 100;
    }

    private LenovoPenUEventBridge(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext != null ? applicationContext : context;
    }

    static synchronized void start(Context context) {
        if (instance != null) {
            return;
        }
        LenovoPenUEventBridge lenovoPenUEventBridge = new LenovoPenUEventBridge(context);
        lenovoPenUEventBridge.startObserving("UEVENT_TO=PEN_FRAMEWORK");
        instance = lenovoPenUEventBridge;
        HookUtils.log("Lenovo PEN_FRAMEWORK uevent bridge started");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void wakeOemForCurrentPen(Context context) {
        // Root starts the real vendor CoreService action after boot. Do not
        // send a synthetic OAF_DEVICE_FOUND event from system_server.
        HookUtils.log("IPe OEM synthetic boot wake suppressed; Root owns boot connect");
    }

    /* JADX WARN: Removed duplicated region for block: B:12:0x004e  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void onUEvent(UEventObserver.UEvent uEvent) {
        if (uEvent == null || !"PEN_FRAMEWORK".equals(uEvent.get("UEVENT_TO", ""))) {
            return;
        }
        String strInfo = clean(uEvent.get("INFO", ""));
        String strMac = clean(uEvent.get("MAC", ""));
        int iTouch = parseInt(uEvent.get("TOUCH_INFORMATION", ""), -1);
        int iInfoBattery = -1;
        if (!strInfo.isEmpty()) {
            String[] strSplit = strInfo.split(";");
            if (strSplit.length > 1) {
                iInfoBattery = parseInt(strSplit[1], -1);
            }
        }
        int iLevel = parseInt(uEvent.get("LEVEL", ""), -1);
        String strRawCharging = clean(uEvent.get("CHARGING_STATE", ""));
        int iCharging = HookUtils.effectiveCharging(this.context, parseChargingState(strRawCharging));
        String strAttached = clean(uEvent.get("ATTACHED", ""));
        boolean zMacValid = strMac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") && !"00:00:00:00:00:00".equals(strMac);
        int iBattery = -1;
        if (zMacValid) {
            iBattery = validBattery(iLevel) ? iLevel : iInfoBattery;
        }
        boolean zConnected = false;
        if (zMacValid) {
            zConnected = HookUtils.linkConnected(this.context) > 0 || HookUtils.bluetoothConnected(this.context, strMac);
        }
        int iPhysicalDocked = HookUtils.physicalDocked(this.context);
        String stateSignature = strMac + '|' + iTouch + '|' + iBattery + '|'
                + iCharging + '|' + strAttached + '|' + iPhysicalDocked + '|'
                + (zConnected ? 1 : 0);
        long now = SystemClock.uptimeMillis();
        if (stateSignature.equals(lastStateSignature) && now - lastStateDispatchAt < 30000L) {
            return;
        }
        lastStateSignature = stateSignature;
        lastStateDispatchAt = now;
        HookUtils.log("PEN_FRAMEWORK uevent touch=" + iTouch + " battery=" + iBattery + " level=" + iLevel + " charging=" + iCharging + " rawCharging=" + strRawCharging + " attached=" + strAttached + " physicalDocked=" + iPhysicalDocked + " mac=" + (zMacValid ? strMac : "unknown") + " info=" + strInfo);
        if (!zMacValid) {
            HookUtils.log("PEN_FRAMEWORK placeholder ignored: no hardware MAC");
            return;
        }
        Intent intent = new Intent("lenovo.intent.action.PEN_BT_CHANGED");
        intent.setPackage("com.aclaniakea.lenovopenbridge");
        intent.putExtra("connected", zConnected ? 1 : 0);
        intent.putExtra("connectState", zConnected ? 2 : 0);
        intent.putExtra("name", "Lenovo Tab Pen Pro");
        intent.putExtra("hardware_identity_known", true);
        intent.putExtra("source", "kernel_pen_framework");
        intent.putExtra("macAddr", strMac);
        if (iPhysicalDocked >= 0) {
            intent.putExtra("physicalDocked", iPhysicalDocked);
        }
        addChargingExtras(intent, iCharging, strRawCharging, strAttached);
        send(intent);
        if (validBattery(iBattery) || iCharging >= 0) {
            Intent intent2 = new Intent("lenovo.intent.action.PEN_BATTERY_CHANGED");
            intent2.setPackage("com.aclaniakea.lenovopenbridge");
            intent2.putExtra("name", "Lenovo Tab Pen Pro");
            intent2.putExtra("connected", zConnected ? 1 : 0);
            intent2.putExtra("hardware_identity_known", true);
            intent2.putExtra("source", "kernel_pen_framework");
            if (validBattery(iBattery)) {
                intent2.putExtra("batteryLevel", iBattery);
                intent2.putExtra("hardware_battery", true);
            }
            intent2.putExtra("macAddr", strMac);
            if (iPhysicalDocked >= 0) {
                intent2.putExtra("physicalDocked", iPhysicalDocked);
            }
            addChargingExtras(intent2, iCharging, strRawCharging, strAttached);
            send(intent2);
        }
        if (zMacValid) {
            SystemStylusHooks.onKernelPenAvailable(this.context);
        }
    }

    private void send(Intent intent) {
        try {
            PenBridgeReceiver.dispatch(this.context, intent);
        } catch (Throwable th) {
            HookUtils.log("PEN_FRAMEWORK dispatch failed: " + th);
        }
        if (HookUtils.disconnectRequested(this.context)) {
            HookUtils.log("IPe OEM wake/handoff suppressed after settings disconnect");
            return;
        }
        // PEN_BT_CHANGED is a framework state notification only. The old
        // bridge converted it into a fake ACL_CONNECTED broadcast, which
        // could start a second OEM GATT session after a real disconnect.
        try {
            this.context.sendBroadcastAsUser(new Intent("com.aclaniakea.lenovopenbridge.action.COLOROS_PEN_STATE").setPackage("com.oplus.ipemanager").putExtras(intent), UserHandle.getUserHandleForUid(0));
        } catch (Throwable th2) {
            HookUtils.log("IPe state handoff failed: " + th2);
        }
    }

    private void wakeOemBluetoothReceiver(String str) {
        try {
            BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice remoteDevice = defaultAdapter == null ? null : defaultAdapter.getRemoteDevice(str);
            if (remoteDevice == null) {
                HookUtils.log("IPe OEM ACL wake skipped: no BluetoothDevice for " + str);
            } else {
                this.context.sendBroadcastAsUser(new Intent("android.bluetooth.device.action.ACL_CONNECTED").setComponent(new ComponentName("com.oplus.ipemanager", "com.oplus.ipemanager.btadsorb.ble.BluetoothStatusReceiver")).addFlags(268435488).putExtra("android.bluetooth.device.extra.DEVICE", remoteDevice), UserHandle.getUserHandleForUid(0));
                HookUtils.log("IPe OEM BluetoothStatusReceiver ACL wake requested mac=" + str);
            }
        } catch (Throwable th) {
            HookUtils.log("IPe OEM ACL wake failed: " + th);
        }
    }

    private void wakeOemBootReceiver(String str) {
        try {
            long jUptimeMillis = SystemClock.uptimeMillis();
            if (!str.equalsIgnoreCase(lastBootOafMac) || jUptimeMillis - lastBootOafAt >= 8000) {
                lastBootOafMac = str;
                lastBootOafAt = jUptimeMillis;
                this.context.sendBroadcastAsUser(new Intent("com.oplus.ipemanager.ACTION.BROADCAST.OAF_DEVICE_FOUND").setComponent(new ComponentName("com.oplus.ipemanager", "com.oplus.ipemanager.btadsorb.ble.BluetoothStatusReceiver")).addFlags(268435488).putExtra("macAddr", str).putExtra("deviceType", "pencil_boot_recovery").putExtra("single_protocol", true), UserHandle.getUserHandleForUid(0));
                HookUtils.log("IPe OEM boot OAF wake requested mac=" + str);
            }
        } catch (Throwable th) {
            HookUtils.log("IPe OEM boot OAF wake failed: " + th);
        }
    }

    private static void addChargingExtras(Intent intent, int i, String str, String str2) {
        if (i >= 0) {
            intent.putExtra("chargingState", i).putExtra("charging", i).putExtra("charge_state", i);
        }
        if (!str.isEmpty()) {
            intent.putExtra("charging_state", str);
        }
        if (str2.isEmpty()) {
            return;
        }
        intent.putExtra("attached", str2);
    }

    private static int parseChargingState(String str) {
        String lowerCase = clean(str).toLowerCase();
        if (lowerCase.isEmpty()) {
            return -1;
        }
        if ("1".equals(lowerCase) || "charging".equals(lowerCase) || "charge".equals(lowerCase) || "wireless charging".equals(lowerCase) || "wireless_charging".equals(lowerCase)) {
            return 1;
        }
        if ("0".equals(lowerCase) || "full".equals(lowerCase) || "not charging".equals(lowerCase) || "not_charging".equals(lowerCase) || "discharging".equals(lowerCase) || "idle".equals(lowerCase) || "none".equals(lowerCase)) {
            return 0;
        }
        int i = parseInt(lowerCase, -1);
        if (i == 0 || i == 1) {
            return i;
        }
        return -1;
    }

    private static String clean(String str) {
        return str == null ? "" : str.trim();
    }

    private static int parseInt(String str, int i) {
        try {
            String strClean = clean(str);
            if (!strClean.startsWith("0x") && !strClean.startsWith("0X")) {
                return Integer.parseInt(strClean);
            }
            return Integer.parseInt(strClean.substring(2), 16);
        } catch (Throwable unused) {
            return i;
        }
    }
}
