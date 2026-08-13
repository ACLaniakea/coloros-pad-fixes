package com.aclaniakea.colorosporttuning;

/* loaded from: classes.dex */
final class DeviceGate {
    private DeviceGate() {
    }

    static boolean supported() {
        return property("ro.soc.model").toUpperCase().contains("SM8650Q") && property("ro.board.platform").toUpperCase().contains("PINEAPPLE");
    }

    private static String property(String str) {
        try {
            return String.valueOf(Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, str, ""));
        } catch (Throwable unused) {
            return "";
        }
    }
}
