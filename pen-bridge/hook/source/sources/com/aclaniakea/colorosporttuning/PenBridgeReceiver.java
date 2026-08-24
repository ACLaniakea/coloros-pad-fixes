package com.aclaniakea.colorosporttuning;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;

/* loaded from: classes.dex */
public final class PenBridgeReceiver extends BroadcastReceiver {
    static final String ACTION_HAPTIC_TRANSPORT =
            "com.aclaniakea.lenovopenbridge.action.HAPTIC_TRANSPORT";
    private static volatile long lastBondedAt = 0;
    private static volatile String lastBondedMac = "";

    static void dispatch(Context context, Intent intent) {
        new PenBridgeReceiver().onReceive(context, intent);
    }

    static void publishPhysicalEdge(Context context, boolean z) {
        if (context == null || !DeviceGate.supported()) {
            return;
        }
        int iPhysicalDocked = HookUtils.physicalDocked(context);
        HookUtils.setPhysicalDocked(context, z);
        PenState penState = PenStateStore.read(context);
        PenState penState2 = new PenState(penState.connected, penState.address, penState.name, penState.battery, 0, penState.type, penState.firmware, penState.hardware, penState.serial, "hardware_hall", System.currentTimeMillis());
        PenStateStore.write(context, penState2);
        broadcastColorOs(context, penState2, "hardware_hall", false);
        HookUtils.log("unified Hall edge docked=" + z + " battery=" + penState2.battery + " charging=" + penState2.charging);
    }

    static void publishCurrentHardwareState(Context context, String str) {
        if (context == null || !DeviceGate.supported()) {
            return;
        }
        PenState penState = PenStateStore.read(context);
        PenStateStore.write(context, penState);
        if (str == null || str.length() == 0) {
            str = "hardware_snapshot";
        }
        broadcastColorOs(context, penState, str, HookUtils.hardwareBattery(context) >= 0);
    }

    static void publishDisconnected(Context context, String str) {
        if (context == null || !DeviceGate.supported()) {
            return;
        }
        PenState penState = PenStateStore.read(context);
        String strTrim = (str == null || str.trim().isEmpty()) ? penState.address : str.trim();
        if (penState.address.length() <= 0 || strTrim.length() <= 0 || sameMac(penState.address, strTrim)) {
            if (strTrim.length() == 0) {
                strTrim = penState.address;
            }
            String str2 = strTrim;
            PenHapticGatt.disconnect();
            HookUtils.invalidateHardwareBattery(context);
            try {
                HookUtils.setLinkConnected(context, false);
            } catch (Throwable unused) {
            }
            // A disconnected card must not carry the previous session's
            // battery.  ColorOS caches BATTERY_NOTIFY independently from the
            // connection state; forwarding 100 here leaves the lock/control
            // center widget at 100% until another real battery sample arrives.
            PenState penState2 = new PenState(false, str2, penState.name, -1, 0, penState.type, penState.firmware, penState.hardware, penState.serial, "settings_disconnect", System.currentTimeMillis());
            PenStateStore.write(context, penState2);
            broadcastColorOs(context, penState2, "settings_disconnect", false);
            HookUtils.log("Lenovo pen disconnected through stock settings: " + str2);
        }
    }

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        BluetoothDevice bluetoothDevice;
        int i;
        int i2;
        InputDevice inputDeviceFindLivePen;
        if (!DeviceGate.supported() || intent == null) {
            return;
        }
        if (ACTION_HAPTIC_TRANSPORT.equals(intent.getAction())) {
            handleHapticTransport(context, intent);
            return;
        }
        PenState penState = PenStateStore.read(context);
        String strValueOf = String.valueOf(intent.getAction());
        intent.getExtras();
        String strFirst = first(intent, "source");
        try {
            bluetoothDevice = (BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
        } catch (Throwable unused) {
            bluetoothDevice = null;
        }
        String strFirst2 = first(intent, "macAddr", "address", "device_address");
        if (strFirst2.length() == 0 && bluetoothDevice != null) {
            try {
                strFirst2 = bluetoothDevice.getAddress();
            } catch (Throwable unused2) {
            }
        }
        String strFirst3 = first(intent, "name", "device_name", "penName");
        if (strFirst3.length() == 0 && bluetoothDevice != null) {
            try {
                strFirst3 = bluetoothDevice.getName();
            } catch (Throwable unused3) {
            }
        }
        if (strFirst2.length() == 0) {
            strFirst2 = HookUtils.penAddress(context);
        }
        if (strFirst3.length() == 0) {
            strFirst3 = penState.name;
        }
        boolean z = intent.getBooleanExtra("hardware_battery", false) || hardwareSource(strFirst);
        int iIntExtra = intExtra(intent, -1, "batteryLevel", "battery_level", "battery", "level", "android.bluetooth.device.extra.BATTERY_LEVEL");
        if (!z) {
            iIntExtra = -1;
        }
        if (z && iIntExtra >= 0 && iIntExtra <= 100) {
            HookUtils.markHardwareBattery(context, iIntExtra);
        }
        int iChargingExtra = chargingExtra(intent, penState.charging);
        int iIntExtra2 = intExtra(intent, Integer.MIN_VALUE, "physicalDocked", "physical_docked");
        if (iIntExtra2 != Integer.MIN_VALUE) {
            HookUtils.setPhysicalDocked(context, iIntExtra2 != 0);
        }
        int iOemCharging = HookUtils.oemCharging(context);
        // Hardware Hall/CPS updates are newer than the OEM provider cache.
        // For non-hardware events the OEM byte remains the preferred source.
        if (z && (iChargingExtra == 0 || iChargingExtra == 1)) {
            HookUtils.markOemCharging(context, iChargingExtra, iChargingExtra);
            iOemCharging = iChargingExtra;
        }
        if (!z && iOemCharging >= 0) {
            iChargingExtra = iOemCharging;
        }
        if (HookUtils.physicalDocked(context) == 0) {
            iChargingExtra = 0;
        }
        boolean isPenEvent = isLenovo(strFirst3) || strValueOf.contains("PEN_") || strValueOf.contains("INPUT_DEVICE") || !strValueOf.startsWith("android.bluetooth") || sameMac(strFirst2, penState.address);
        if (isPenEvent) {
            // A real connection to another pen is a valid address switch.
            // Pairing alone is not: BOND_STATE_CHANGED must not wake or
            // unlock the Bluetooth path.
            if (strValueOf.contains("ACL_CONNECTED") && !sameMac(strFirst2, penState.address)) {
                try {
                    Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_disconnect_requested", 0);
                } catch (Throwable unused5) {
                }
            }
            boolean z2 = penState.connected;
            boolean zContains = strValueOf.contains("ACL_DISCONNECTED");
            boolean zContains2 = strValueOf.contains("ACL_CONNECTED");
            if (zContains) {
                HookUtils.setLinkConnected(context, false);
                HookUtils.invalidateHardwareBattery(context);
                HookUtils.invalidateOemCharging(context);
                z2 = false;
                i = 0;
                i2 = -1;
            } else {
                if (zContains2) {
                    // ACL/GATT comes up before HOGP and can survive after HID
                    // has failed. Only publish connected once HID Host is up.
                    z2 = HookUtils.bluetoothConnected(context, strFirst2);
                    HookUtils.setLinkConnected(context, z2);
                } else {
                    if (!strValueOf.contains("VERSION") && !strValueOf.contains("_SN")) {
                        int iIntExtra3 = intExtra(intent, Integer.MIN_VALUE, "connected", "state", "connectState", "status");
                        if (iIntExtra3 != Integer.MIN_VALUE) {
                            z2 = iIntExtra3 == 1 || iIntExtra3 == 2 || iIntExtra3 == 12;
                            if ("kernel_pen_framework".equals(strFirst) && z2 && !HookUtils.bluetoothConnected(context, strFirst2)) {
                                z2 = false;
                            }
                        }
                    }
                    i = iChargingExtra;
                    i2 = iIntExtra;
                }
                i = iChargingExtra;
                i2 = iIntExtra;
            }
            if (strValueOf.contains("BATTERY") && bluetoothDevice != null && sameMac(strFirst2, penState.address) && HookUtils.bluetoothConnected(context, strFirst2)) {
                z2 = true;
            }
            if (strValueOf.contains("BOOT_COMPLETED") || strValueOf.contains("MY_PACKAGE_REPLACED") || strValueOf.contains("REMOUNT_CONTROL") || strValueOf.contains("OPEN_BLUETOOTH")) {
                inputDeviceFindLivePen = findLivePen(context);
                if (inputDeviceFindLivePen != null) {
                    if (strFirst3.length() == 0) {
                        strFirst3 = inputDeviceFindLivePen.getName();
                    }
                    try {
                        String strValueOf2 = String.valueOf(inputDeviceFindLivePen.getClass().getMethod("getBluetoothAddress", new Class[0]).invoke(inputDeviceFindLivePen, new Object[0]));
                        if (strValueOf2 != null && !"null".equals(strValueOf2) && !strValueOf2.isEmpty()) {
                            strFirst2 = strValueOf2;
                        }
                    } catch (Throwable unused4) {
                    }
                    z2 = true;
                } else {
                    BluetoothDevice bondedPen = findBondedPen(context);
                    if (bondedPen != null) {
                        strFirst2 = bondedPen.getAddress();
                        String bondedName = bondedPen.getName();
                        if (bondedName != null && !bondedName.trim().isEmpty()) {
                            strFirst3 = bondedName;
                        }
                        // Bonding identifies the pen but does not prove a live
                        // ACL/HOGP link.  Treating every bonded pen as connected
                        // resurrected stale battery state at boot and after a
                        // Bluetooth restart.
                        z2 = HookUtils.bluetoothConnected(context, strFirst2);
                    }
                }
            }
            String str = strFirst2;
            String str2 = strFirst3;
            boolean z3 = (!HookUtils.disconnectRequested(context) || "com.aclaniakea.lenovopenbridge.action.RECONNECT_PEN".equals(strValueOf)) ? z2 : false;
            String strFirst4 = first(intent, "version", "firmware", "fwVersion");
            if (strFirst4.length() == 0) {
                strFirst4 = penState.firmware;
            }
            String str3 = strFirst4;
            String strFirst5 = first(intent, "sn", "serial", "serialNumber");
            if (strFirst5.length() == 0) {
                strFirst5 = penState.serial;
            }
            int resolvedBattery = z3 ? (i2 < 0 ? penState.battery : i2) : -1;
            PenState penState2 = new PenState(z3, str, str2, resolvedBattery, z3 ? i : 0, penState.type, str3, penState.hardware.length() == 0 ? "Lenovo Tab Pen" : penState.hardware, strFirst5, strFirst.length() == 0 ? strValueOf : strFirst, System.currentTimeMillis());
            PenStateStore.write(context, penState2);
            if (strFirst.length() != 0) {
                strValueOf = strFirst;
            }
            broadcastColorOs(context, penState2, strValueOf, z && i2 >= 0);
            if (z3 && !penState.connected) {
                PenHapticGatt.connected(context, str);
            } else {
                if (z3 || !penState.connected) {
                    return;
                }
                PenHapticGatt.disconnect();
            }
        }
    }

    static void broadcastColorOs(Context context, PenState penState, String str) {
        broadcastColorOs(context, penState, str, HookUtils.hardwareBattery(context) >= 0);
    }

    /* JADX WARN: Removed duplicated region for block: B:31:0x00d9  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    static void broadcastColorOs(Context context, PenState penState, String str, boolean z) {
        String strMacNoColon = penState.macNoColon();
        int iPhysicalDocked = HookUtils.physicalDocked(context);
        boolean zPresent = iPhysicalDocked == 1 && penState.charging != 0;
        int iPhysicalDocked2 = HookUtils.physicalDocked(context);
        boolean zHardwareBattery = penState.battery >= 0 && z;
        Intent intent = new Intent("com.oplus.ipemanager.action.PENCIL_STATUS_CHANGE");
        intent.putExtra("connect_state", penState.connectState());
        intent.putExtra("battery_level", penState.battery);
        intent.putExtra("mac_addr", penState.address);
        intent.putExtra("present", zPresent ? "1" : "0");
        intent.putExtra("macAddr", strMacNoColon);
        intent.putExtra("pencilId", "Ivy");
        intent.putExtra("chargingState", penState.charging);
        intent.putExtra("charging", penState.charging);
        intent.putExtra("charging_state", penState.charging);
        intent.putExtra("connected", penState.connected ? 1 : 0);
        intent.putExtra("physicalDocked", iPhysicalDocked2);
        intent.putExtra("source", str);
        intent.putExtra("hardware_battery", zHardwareBattery);
        if (penState.connected && !HookUtils.disconnectRequested(context) && zHardwareBattery) {
            long jUptimeMillis = SystemClock.uptimeMillis();
            if (!strMacNoColon.equalsIgnoreCase(lastBondedMac) || jUptimeMillis - lastBondedAt > 3000L) {
                lastBondedMac = strMacNoColon;
                lastBondedAt = jUptimeMillis;
                try {
                    Intent intent2 = new Intent("com.oplus.ipemanager.action.PENCIL_BONDED_WHEN_BOOT");
                    intent2.putExtra("macAddr", strMacNoColon);
                    intent2.putExtra("pencilId", "Ivy");
                    startColorOsService(context, intent2);
                } catch (Throwable unused) {
                }
            }
        }
        if (penState.connected || penState.address.length() > 0) {
            Intent intent3 = new Intent("com.oplus.ipemanager.action.BATTERY_NOTIFY");
            intent3.putExtra("macAddr", strMacNoColon);
            intent3.putExtra("mac_addr", penState.address);
            intent3.putExtra("batteryLevel", penState.battery);
            intent3.putExtra("battery_level", penState.battery);
            intent3.putExtra("chargingState", penState.charging);
            intent3.putExtra("charging", penState.charging);
            intent3.putExtra("charging_state", penState.charging);
            intent3.putExtra("present", penState.connected ? "1" : "0");
            intent3.putExtra("connected", penState.connected ? 1 : 0);
            intent3.putExtra("physicalDocked", iPhysicalDocked2);
            intent3.putExtra("source", str);
            intent3.putExtra("hardware_battery", zHardwareBattery);
            intent3.putExtra("hardware_identity_known", penState.address != null && penState.address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"));
            try {
                context.sendBroadcast(new Intent(intent3).setPackage("com.oplus.ipemanager"));
            } catch (Throwable unused2) {
            }
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_charging_state", penState.charging);
        } catch (Throwable unused3) {
        }
        HookUtils.setIpePreferenceInt(context, "pencil_sp_charging_state", penState.charging);
        HookUtils.setIpePreferenceInt(context, "pencil_sp_battery_level", penState.battery);
    }

    /**
     * Keep BluetoothGatt ownership in the bridge APK process.  Input events
     * are observed inside system_server, but opening/writing a vendor GATT
     * session from that critical process can turn a Bluetooth/vendor failure
     * into a full Android userspace restart.
     */
    static void handleHapticTransport(Context context, Intent intent) {
        String op = String.valueOf(intent.getStringExtra("op"));
        String address = String.valueOf(intent.getStringExtra("address"));
        if ("null".equals(address) || address.trim().isEmpty()) {
            address = HookUtils.penAddress(context);
        }
        try {
            if ("start".equals(op)) {
                PenHapticGatt.startWriting(context, address,
                        intent.getIntExtra("toolType", 2));
            } else if ("stop".equals(op)) {
                PenHapticGatt.stopWriting();
            } else if ("pulse".equals(op)) {
                PenHapticGatt.pulse(context, address);
            } else if ("enable".equals(op)) {
                PenHapticGatt.setWritingEnabled(context, address,
                        intent.getBooleanExtra("enabled", true));
            } else if ("disconnect".equals(op)) {
                PenHapticGatt.disconnect();
            }
        } catch (Throwable th) {
            HookUtils.log("isolated haptic transport " + op + ": " + th);
        }
    }

    private static void startColorOsService(Context context, Intent intent) {
        try {
            if ("com.oplus.ipemanager".equals(context.getPackageName())) {
                context.startService(new Intent(intent).setComponent(new ComponentName("com.oplus.ipemanager", "com.oplus.ipemanager.btadsorb.CoreService")));
            }
        } catch (Throwable th) {
            HookUtils.log("Oplus CoreService dispatch: " + th);
        }
    }

    private static String first(Intent intent, String... strArr) {
        for (String str : strArr) {
            String stringExtra = intent.getStringExtra(str);
            if (stringExtra != null && !stringExtra.trim().isEmpty()) {
                return stringExtra.trim();
            }
        }
        return "";
    }

    private static boolean hardwareSource(String str) {
        return "kernel_pen_framework".equals(str) || "ipe_ble_gatt".equals(str) || "ipe_ble_gatt_charge".equals(str) || "hardware_snapshot".equals(str);
    }

    private static int intExtra(Intent intent, int i, String... strArr) {
        for (String str : strArr) {
            if (intent.hasExtra(str)) {
                try {
                    Object obj = intent.getExtras() == null ? null : intent.getExtras().get(str);
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
                    continue;
                }
            }
        }
        return i;
    }

    private static int chargingExtra(Intent intent, int i) {
        int iIntExtra = intExtra(intent, Integer.MIN_VALUE, "chargingState", "charging", "charge_state");
        if (iIntExtra != Integer.MIN_VALUE) {
            return iIntExtra;
        }
        String[] strArr = {"charging_state", "CHARGING_STATE"};
        for (int i2 = 0; i2 < 2; i2++) {
            String str = strArr[i2];
            if (intent.hasExtra(str)) {
                try {
                    Object obj = intent.getExtras() == null ? null : intent.getExtras().get(str);
                    String lowerCase = obj == null ? "" : String.valueOf(obj).trim().toLowerCase();
                    if (!"charging".equals(lowerCase) && !"charge".equals(lowerCase) && !"wireless charging".equals(lowerCase) && !"wireless_charging".equals(lowerCase) && !"1".equals(lowerCase)) {
                        if ("full".equals(lowerCase) || "not charging".equals(lowerCase) || "not_charging".equals(lowerCase) || "discharging".equals(lowerCase) || "idle".equals(lowerCase) || "none".equals(lowerCase) || "0".equals(lowerCase)) {
                            return 0;
                        }
                    }
                    return 1;
                } catch (Throwable unused) {
                    continue;
                }
            }
        }
        return i;
    }

    private static boolean same(String str, String str2) {
        return (str == null || str2 == null || !str.equalsIgnoreCase(str2)) ? false : true;
    }

    private static boolean sameMac(String str, String str2) {
        return (str == null || str2 == null || !str.replace(":", "").equalsIgnoreCase(str2.replace(":", ""))) ? false : true;
    }

    private static boolean isLenovo(String str) {
        String lowerCase = str == null ? "" : str.toLowerCase();
        for (String str2 : PenBridgeConstants.LENOVO_NAMES) {
            if (lowerCase.contains(str2)) {
                return true;
            }
        }
        return lowerCase.contains("pen") || lowerCase.contains("stylus") || lowerCase.contains("pencil");
    }

    private static InputDevice findLivePen(Context context) {
        try {
            InputManager inputManager = (InputManager) context.getSystemService("input");
            for (int i : inputManager.getInputDeviceIds()) {
                InputDevice inputDevice = inputManager.getInputDevice(i);
                if (inputDevice != null && (isLenovo(inputDevice.getName()) || ("NVTCapacitivePen".equalsIgnoreCase(inputDevice.getName()) && (inputDevice.getSources() & 16386) != 0))) {
                    return inputDevice;
                }
            }
            return null;
        } catch (Throwable unused) {
            return null;
        }
    }

    private static BluetoothDevice findBondedPen(Context context) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return null;
            }
            String preferred = HookUtils.penAddress(context);
            BluetoothDevice fallback = null;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (device == null || !isLenovo(device.getName())) {
                    continue;
                }
                if (sameMac(device.getAddress(), preferred)) {
                    return device;
                }
                if (fallback == null) {
                    fallback = device;
                }
            }
            return fallback;
        } catch (Throwable unused) {
            return null;
        }
    }
}
