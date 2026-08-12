package com.aclaniakea.colorosporttuning;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/* loaded from: classes.dex */
final class HookUtils {
    static int hookAll(ClassLoader classLoader, String str, String str2, XC_MethodHook xC_MethodHook) {
        try {
            int i = 0;
            for (Method method : Class.forName(str, false, classLoader).getDeclaredMethods()) {
                if (method.getName().equals(str2)) {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, xC_MethodHook);
                    i++;
                }
            }
            return i;
        } catch (Throwable th) {
            log("skip " + str + "#" + str2 + ": " + th);
            return 0;
        }
    }

    static Context context(Object obj) {
        Object obj2 = null;
        if (obj instanceof Context) {
            return (Context) obj;
        }
        String[] strArr = {"mContext", "mSystemContext", "mBase"};
        for (int i = 0; i < 3; i++) {
            try {
                Field declaredField = obj.getClass().getDeclaredField(strArr[i]);
                declaredField.setAccessible(true);
                obj2 = declaredField.get(obj);
            } catch (Throwable unused) {
            }
            if (obj2 instanceof Context) {
                return (Context) obj2;
            }
            continue;
        }
        try {
            Object objInvoke = Class.forName("android.app.ActivityThread").getMethod("currentApplication", new Class[0]).invoke(null, new Object[0]);
            if (objInvoke instanceof Context) {
                return (Context) objInvoke;
            }
            return null;
        } catch (Throwable unused2) {
            return null;
        }
    }

    static int physicalDocked(Context context) {
        if (context == null) {
            return -1;
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_physical_docked", -1);
        } catch (Throwable unused) {
            return -1;
        }
    }

    static boolean disconnectRequested(Context context) {
        if (context == null) {
            return false;
        }
        // Magnetic docking controls charging and the capsule only. A pen can
        // keep its wireless GATT link while it is being used away from the
        // tablet, so only the explicit settings-page latch blocks a connect.
        return Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_disconnect_requested", 0) == 1;
    }

    static int linkConnected(Context context) {
        if (context == null) {
            return 0;
        }
        return bluetoothConnected(context, Settings.Global.getString(context.getContentResolver(), "ipe_pencil_mac_addr")) ? 1 : 0;
    }

    static void setLinkConnected(Context context, boolean z) {
        if (context == null) {
            return;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_link_connected", z ? 1 : 0);
        } catch (Throwable unused) {
        }
    }

    static void setPhysicalDocked(Context context, boolean z) {
        if (context == null) {
            return;
        }
        try {
            int iPhysicalDocked = physicalDocked(context);
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_physical_docked", z ? 1 : 0);
            if (iPhysicalDocked != (z ? 1 : 0)) {
                invalidateOemCharging(context);
                Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_charging_state", 0);
                setIpePreferenceInt(context, "pencil_sp_charging_state", 0);
            }
        } catch (Throwable unused) {
        }
    }

    static void markOemCharging(Context context, int i, int i2) {
        if (context == null) {
            return;
        }
        try {
            int i3 = 1;
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_charge_valid", 1);
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_charge_raw", i & 255);
            ContentResolver contentResolver = context.getContentResolver();
            if (i2 == 0) {
                i3 = 0;
            }
            Settings.Global.putInt(contentResolver, "lenovo_pen_oem_charge_state", i3);
        } catch (Throwable unused) {
        }
    }

    static int oemCharging(Context context) {
        if (context == null) {
            return -1;
        }
        try {
            if (Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_oem_charge_valid", 0) != 1) {
                return -1;
            }
            int i = Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_oem_charge_state", -1);
            if (i == 0 || i == 1) {
                return i;
            }
            return -1;
        } catch (Throwable unused) {
            return -1;
        }
    }

    static void invalidateOemCharging(Context context) {
        if (context == null) {
            return;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_charge_valid", 0);
            Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_charging_state", 0);
            setIpePreferenceInt(context, "pencil_sp_charging_state", 0);
        } catch (Throwable unused) {
        }
    }

    static int hardwareBattery(Context context) {
        if (context == null) {
            return -1;
        }
        if (Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_hardware_battery_valid", 0) != 1 || Settings.Global.getLong(context.getContentResolver(), "lenovo_pen_hardware_battery_last_at", 0L) <= 0) {
            return -1;
        }
        int i = Settings.Global.getInt(context.getContentResolver(), "ipe_pencil_battery_level", -1);
        if (i < 0 || i > 100) {
            return -1;
        }
        return i;
    }

    static int lastValidBattery(Context context) {
        if (context == null) {
            return -1;
        }
        if (Settings.Global.getLong(context.getContentResolver(), "lenovo_pen_hardware_battery_last_at", 0L) <= 0) {
            return -1;
        }
        int i = Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_last_valid_battery", -1);
        if (i < 0 || i > 100) {
            return -1;
        }
        return i;
    }

    static void markHardwareBattery(Context context, int i) {
        if (context == null || i < 0 || i > 100) {
            return;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_hardware_battery_valid", 1);
            Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_battery_level", i);
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_last_valid_battery", i);
            Settings.Global.putLong(context.getContentResolver(), "lenovo_pen_hardware_battery_last_at", System.currentTimeMillis());
        } catch (Throwable unused) {
        }
    }

    static void invalidateHardwareBattery(Context context) {
        if (context == null) {
            return;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_hardware_battery_valid", 0);
            // Keep the last valid level visible while the next GATT/CPS
            // sample is pending; an invalid sentinel is rendered as 0% by
            // this OEM settings page.
        } catch (Throwable unused) {
        }
    }

    static int effectiveCharging(Context context, int i) {
        int iOemCharging = oemCharging(context);
        if (iOemCharging >= 0) {
            return iOemCharging;
        }
        if (i < 0) {
            return -1;
        }
        return (i == 0 || physicalDocked(context) == 0) ? 0 : 1;
    }

    static int wirelessPenPresent(Context context) {
        return (physicalDocked(context) != 1 || state(context).charging == 0) ? 0 : 1;
    }

    static void setIpePreferenceInt(Context context, String str, int i) {
        if (context == null || str == null || str.length() == 0) {
            return;
        }
        try {
            ContentValues contentValues = new ContentValues();
            contentValues.put(str, Integer.valueOf(i));
            context.getContentResolver().insert(Uri.parse("content://com.oplus.ipemanager.provider/integer/local_config/" + str + "/" + i), contentValues);
        } catch (Throwable unused) {
        }
    }

    static void setIpePreferenceString(Context context, String str, String strValue) {
        if (context == null || str == null || str.length() == 0 || strValue == null) {
            return;
        }
        try {
            ContentValues contentValues = new ContentValues();
            contentValues.put(str, strValue);
            context.getContentResolver().insert(Uri.parse("content://com.oplus.ipemanager.provider/string/local_config/" + str + "/" + strValue), contentValues);
        } catch (Throwable unused) {
        }
    }

    static PenState state(Context context) {
        if (context == null) {
            return new PenState(false, "", "", -1, 0, "SECOND_GENERATION_PENCIL_LITE", "1.0.0", "Lenovo Tab Pen", "LENOVO-PEN", "fallback", 0L);
        }
        String string = penAddress(context);
        boolean z = linkConnected(context) > 0;
        boolean z2 = z;
        String string2 = Settings.Global.getString(context.getContentResolver(), "lenovo_pen_serial");
        if (string2 == null || string2.length() == 0) {
            string2 = "LENOVO-" + (string == null ? "PEN" : string.replace(":", ""));
        }
        String str = string2;
        int iHardwareBattery = hardwareBattery(context);
        if (iHardwareBattery < 0) {
            int iLastValidBattery = lastValidBattery(context);
            if (iLastValidBattery >= 0) {
                iHardwareBattery = iLastValidBattery;
            }
        }
        return new PenState(z2, string, Settings.Global.getString(context.getContentResolver(), "ipe_pencil_bt_device_name"), iHardwareBattery, effectiveCharging(context, Settings.Global.getInt(context.getContentResolver(), "ipe_pencil_charging_state", 0)), value(context, "lenovo_pen_type", "SECOND_GENERATION_PENCIL_LITE"), value(context, "lenovo_pen_firmware", "1.0.0"), value(context, "lenovo_pen_hardware", "Lenovo Tab Pen"), str, "global+hardware", 0L);
    }

    static int batteryForCapsule(Context context) {
        if (context == null) {
            return -1;
        }
        int iHardwareBattery = hardwareBattery(context);
        return iHardwareBattery >= 0 ? iHardwareBattery : lastValidBattery(context);
    }

    static boolean bluetoothConnected(Context context, String str) {
        if (str != null && str.length() != 0) {
            try {
                BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
                if (defaultAdapter == null) {
                    return false;
                }
                BluetoothDevice remoteDevice = defaultAdapter.getRemoteDevice(str);
                BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService("bluetooth");
                if (bluetoothManager != null) {
                    if (bluetoothManager.getConnectionState(remoteDevice, 7) == 2) {
                        return true;
                    }
                    if (bluetoothManager.getConnectionState(remoteDevice, 4) == 2) {
                        return true;
                    }
                }
            } catch (Throwable unused) {
            }
        }
        return false;
    }

    /** Resolve the current bonded Lenovo pen without requiring a fixed MAC. */
    static String penAddress(Context context) {
        if (context == null) {
            return "";
        }
        String stored = Settings.Global.getString(context.getContentResolver(), "ipe_pencil_mac_addr");
        String normalized = normalizeMac(stored);
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                BluetoothDevice fallback = null;
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    if (device == null || !isPenName(device.getName())) {
                        continue;
                    }
                    String address = normalizeMac(device.getAddress());
                    if (address.length() == 0) {
                        continue;
                    }
                    if (address.equalsIgnoreCase(normalized)) {
                        return address;
                    }
                    if (fallback == null) {
                        fallback = device;
                    }
                }
                if (fallback != null) {
                    String address = normalizeMac(fallback.getAddress());
                    Settings.Global.putString(context.getContentResolver(), "ipe_pencil_mac_addr", address);
                    return address;
                }
            }
        } catch (Throwable th) {
            log("bonded pen address lookup: " + th);
        }
        return normalized;
    }

    private static String normalizeMac(String str) {
        if (str == null) {
            return "";
        }
        String compact = str.trim().replace(":", "").toUpperCase();
        if (!compact.matches("[0-9A-F]{12}")) {
            return "";
        }
        StringBuilder builder = new StringBuilder(17);
        for (int i = 0; i < compact.length(); i += 2) {
            if (builder.length() > 0) {
                builder.append(':');
            }
            builder.append(compact.substring(i, i + 2));
        }
        return builder.toString();
    }

    private static boolean isPenName(String str) {
        String lower = str == null ? "" : str.toLowerCase();
        for (String name : PenBridgeConstants.LENOVO_NAMES) {
            if (lower.contains(name)) {
                return true;
            }
        }
        return lower.contains("pen") || lower.contains("stylus") || lower.contains("pencil");
    }

    private static String value(Context context, String str, String str2) {
        String string = Settings.Global.getString(context.getContentResolver(), str);
        return (string == null || string.length() == 0) ? str2 : string;
    }

    static Object adapt(Class<?> cls, Object obj) {
        if (obj == null) {
            return null;
        }
        if (!cls.isInstance(obj)) {
            boolean z = false;
            if (cls == Boolean.TYPE || cls == Boolean.class) {
                if (!(obj instanceof Number)) {
                    z = Boolean.parseBoolean(String.valueOf(obj));
                } else if (((Number) obj).intValue() != 0) {
                    z = true;
                }
                return Boolean.valueOf(z);
            }
            if (cls == Integer.TYPE || cls == Integer.class) {
                return Integer.valueOf(obj instanceof Number ? ((Number) obj).intValue() : Integer.parseInt(String.valueOf(obj)));
            }
            if (cls == Long.TYPE || cls == Long.class) {
                return Long.valueOf(obj instanceof Number ? ((Number) obj).longValue() : Long.parseLong(String.valueOf(obj)));
            }
            if (cls == String.class) {
                return String.valueOf(obj);
            }
            if (cls.isEnum()) {
                Object[] enumConstants = cls.getEnumConstants();
                String strValueOf = String.valueOf(obj);
                for (Object obj2 : enumConstants) {
                    if (String.valueOf(obj2).equalsIgnoreCase(strValueOf)) {
                        return obj2;
                    }
                }
                if (enumConstants == null || enumConstants.length <= 0) {
                    return null;
                }
                return enumConstants[Math.min(1, enumConstants.length - 1)];
            }
        }
        return obj;
    }

    static Object call(Object obj, String str, Object... objArr) throws SecurityException {
        if (obj != null && str != null) {
            for (Method method : obj.getClass().getMethods()) {
                if (method.getName().equals(str) && method.getParameterTypes().length == objArr.length) {
                    try {
                        method.setAccessible(true);
                        return method.invoke(obj, objArr);
                    } catch (Throwable unused) {
                        continue;
                    }
                }
            }
        }
        return null;
    }

    static String string(Object obj, String str) throws SecurityException {
        Object objCall = call(obj, str, new Object[0]);
        return objCall == null ? "" : String.valueOf(objCall);
    }

    static void log(String str) {
        XposedBridge.log("LenovoPenBridge: " + str);
    }

    private HookUtils() {
    }
}
