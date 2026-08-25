package com.aclaniakea.colorosporttuning;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.view.InputDevice;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/* loaded from: classes.dex */
final class HookUtils {
    private static final int HID_HOST_PROFILE = 4;
    private static volatile BluetoothProfile hidHostProxy;
    private static volatile boolean hidHostProxyRequested;
    private static volatile int lastLinkDisagreement = -1;

    /*
     * This pen is LE-only and runs over HOGP.  The stock stack tracks both legs
     * separately -- dumpsys shows
     *   "Selected transport=2  HID connection state=0  HOGP connection state=2"
     * -- and the HID Host profile hands out the BR/EDR leg's 0, which this pen
     * never uses.  Every consumer that asks the profile is therefore told the
     * pen is disconnected while it is working: the stock settings page greys
     * out every option, and onMotion()'s gate silently drops all haptics.
     *
     * The uhid input devices the stack creates for the HOGP link are direct
     * evidence that the link is up, and they disappear when it really drops.
     * Match only the explicit Lenovo pen names -- isPenName() is deliberately
     * loose and would also match the built-in NVTCapacitivePen digitizer, which
     * is always present and would pin this to true forever.
     */
    private static boolean penHidLinkPresent() {
        try {
            int[] ids = InputDevice.getDeviceIds();
            if (ids == null) {
                return false;
            }
            for (int id : ids) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) {
                    continue;
                }
                String name = device.getName();
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase();
                for (String known : PenBridgeConstants.LENOVO_NAMES) {
                    if (lower.contains(known)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
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
        if (disconnectRequested(context)) {
            return 0;
        }
        String process = Application.getProcessName();
        if ("system_server".equals(process) || "android".equals(process)) {
            return bluetoothConnected(context, Settings.Global.getString(context.getContentResolver(), "ipe_pencil_mac_addr")) ? 1 : 0;
        }
        return Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_link_connected", 0) == 1 ? 1 : 0;
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
        // Hall is the physical source of truth. The OEM GATT charge byte can
        // remain cached for several seconds after the pen leaves the rail;
        // never let that stale byte resurrect the charging indicator.
        if (physicalDocked(context) == 0) {
            return 0;
        }
        int iOemCharging = oemCharging(context);
        if (iOemCharging >= 0) {
            return iOemCharging;
        }
        if (i < 0) {
            return -1;
        }
        return i == 0 ? 0 : 1;
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
        // Connection state follows the real Bluetooth link. Only an
        // explicit settings-page disconnect forces it off.
        boolean z;
        if (disconnectRequested(context)) {
            z = false;
        } else {
            z = bluetoothConnected(context, string);
        }
        boolean z2 = z;
        String string2 = Settings.Global.getString(context.getContentResolver(), "lenovo_pen_serial");
        if (string2 == null || string2.length() == 0) {
            string2 = "LENOVO-" + (string == null ? "PEN" : string.replace(":", ""));
        }
        String str = string2;
        int iHardwareBattery = hardwareBattery(context);
        if (iHardwareBattery < 0 && z2) {
            // Only fall back to the last known level while the pen is still
            // connected. When disconnected, report no battery so the lock
            // screen ring / card shows "not connected" instead of a stale 100%.
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
        if (linkConnected(context) <= 0) {
            // Hall can fire while the pen is still waiting to power up. The
            // CPS placeholder at that point is 0%, so defer the capsule until
            // the real Bluetooth session exists instead of flashing 0%.
            return -1;
        }
        int iHardwareBattery = hardwareBattery(context);
        return iHardwareBattery >= 0 ? iHardwareBattery : -1;
    }

    /*
     * Either signal being positive means connected.  Both are evidence *of* a
     * link and neither can prove its absence: the HID Host profile hands out
     * the BR/EDR leg's 0 for a pen that only ever uses HOGP, and the uhid
     * device list is not equally visible from every process -- an earlier
     * version let the input-device answer win outright, and in the ipemanager
     * process, where the pen's uhid devices are not enumerable, that pinned the
     * settings page to "not connected" permanently.  OR can only ever be more
     * permissive than the old profile-only answer, which is the whole point.
     * Disagreements are logged once per transition so a wrong call is visible
     * rather than silent.
     */
    private static boolean reconcileLink(boolean profileConnected, boolean linkPresent) {
        if (profileConnected == linkPresent) {
            lastLinkDisagreement = -1;
            return profileConnected;
        }
        int marker = linkPresent ? 1 : 0;
        if (lastLinkDisagreement != marker) {
            lastLinkDisagreement = marker;
            log("pen link state disagrees: HID Host profile=" + profileConnected
                    + " live uhid link=" + linkPresent + "; reporting connected");
        }
        return true;
    }

    static boolean bluetoothConnected(Context context, String str) {
        if (str != null && str.length() != 0) {
            try {
                BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
                if (defaultAdapter == null) {
                    return false;
                }
                final BluetoothDevice remoteDevice = defaultAdapter.getRemoteDevice(str);
                boolean linkPresent = penHidLinkPresent();
                BluetoothProfile profile = hidHostProxy;
                if (profile != null) {
                    // BluetoothHidHost is hidden from the public SDK, but the
                    // stock profile proxy still exposes its device-specific
                    // getConnectionState(BluetoothDevice) implementation.
                    Object state = call(profile, "getConnectionState", remoteDevice);
                    if (state instanceof Number) {
                        boolean profileConnected = ((Number) state).intValue()
                                == BluetoothProfile.STATE_CONNECTED;
                        return reconcileLink(profileConnected, linkPresent);
                    }
                }
                if (!hidHostProxyRequested) {
                    synchronized (HookUtils.class) {
                        if (!hidHostProxyRequested) {
                            hidHostProxyRequested = true;
                            boolean accepted = defaultAdapter.getProfileProxy(context,
                                    new BluetoothProfile.ServiceListener() {
                                        @Override
                                        public void onServiceConnected(int profileId,
                                                BluetoothProfile proxy) {
                                            if (profileId == HID_HOST_PROFILE) {
                                                hidHostProxy = proxy;
                                            }
                                            hidHostProxyRequested = false;
                                        }

                                        @Override
                                        public void onServiceDisconnected(int profileId) {
                                            if (profileId == HID_HOST_PROFILE) {
                                                hidHostProxy = null;
                                            }
                                            hidHostProxyRequested = false;
                                        }
                                    }, HID_HOST_PROFILE);
                            if (!accepted) {
                                hidHostProxyRequested = false;
                            }
                        }
                    }
                }
                // The proxy arrives asynchronously. Until then use the stock
                // adapter's aggregate HID Host state. This is still profile 4
                // (not ACL/GATT) and is immediately available during boot.
                return reconcileLink(defaultAdapter.getProfileConnectionState(HID_HOST_PROFILE)
                        == BluetoothProfile.STATE_CONNECTED, linkPresent);
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
        String message = "LenovoPenBridge: " + str;
        // The exported hardware receiver also runs in the module APK's normal
        // process, where LSPosed classes are intentionally absent.  Resolve the
        // logger lazily so real Hall/battery broadcasts cannot crash that path.
        try {
            Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
            bridge.getMethod("log", String.class).invoke(null, message);
        } catch (Throwable ignored) {
            android.util.Log.i("LenovoPenBridge", message);
        }
    }

    private HookUtils() {
    }
}
