package com.aclaniakea.devicegate;

/* loaded from: classes.dex */
/** Shared hard gate: hooks run only on the real SM8650Q/pineapple tablet. */
public final class DeviceGate {
    private DeviceGate() {
    }

    public static boolean isSupported() {
        return value(getProperty("ro.soc.model")).contains("SM8650Q") && value(getProperty("ro.board.platform")).contains("PINEAPPLE");
    }

    private static String getProperty(String str) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getDeclaredMethod("get", String.class).invoke(null, str);
        } catch (Throwable unused) {
            return "";
        }
    }

    private static String value(String str) {
        return str == null ? "" : str.toUpperCase();
    }
}
