package com.aclaniakea.batteryhealthbridge;

import java.io.File;
import java.util.Scanner;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Bridges the ColorOS battery-health page to the real power-supply attributes.
 *
 * The UI reads the SOH percentage through p5/b.s(Context) and gates the data
 * panel through p5/b.A().  On this port the OPlusBatteryManager backend is
 * absent, so both are replaced with sysfs-backed values.
 */
public final class BatteryHealthBridge implements IXposedHookLoadPackage {
    private static final String TAG = "BatteryHealthBridge";
    private static final String TARGET = "com.oplus.battery";
    private static final String SYSFS_BATTERY = "/sys/class/power_supply/battery";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!TARGET.equals(lpp.packageName) || lpp.processName == null || !lpp.processName.startsWith(TARGET)) return;
        try {
            XposedHelpers.findAndHookMethod("p5.b", lpp.classLoader, "s", android.content.Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    int soh = readSoh();
                    if (soh > 0) {
                        param.setResult(soh);
                        XposedBridge.log(TAG + ": SOH bridged to " + soh);
                    }
                }
            });
            XposedBridge.log(TAG + ": SOH hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SOH hook failed");
            XposedBridge.log(t);
        }
        try {
            XposedHelpers.findAndHookMethod("p5.b", lpp.classLoader, "A", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            XposedBridge.log(TAG + ": health data gate forced open");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": health gate hook failed");
            XposedBridge.log(t);
        }
        try {
            XposedHelpers.findAndHookMethod("i5.a", lpp.classLoader, "e", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            XposedBridge.log(TAG + ": battery health feature forced on");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": battery health feature hook failed");
            XposedBridge.log(t);
        }
    }

    private static int readSoh() {
        long full = readLong(SYSFS_BATTERY + "/charge_full");
        long design = readLong(SYSFS_BATTERY + "/charge_full_design");
        if (full <= 0 || design <= 0) return -1;
        int soh = (int) Math.round(full * 100.0d / design);
        if (soh < 1) soh = 1;
        if (soh > 100) soh = 100;
        return soh;
    }

    private static long readLong(String path) {
        try (Scanner scanner = new Scanner(new File(path))) {
            if (scanner.hasNextLong()) return scanner.nextLong();
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
