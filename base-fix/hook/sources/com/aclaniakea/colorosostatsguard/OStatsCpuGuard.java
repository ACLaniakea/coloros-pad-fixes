package com.aclaniakea.colorosostatsguard;

import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes.dex */
/** Prevents port-only OStats CPU telemetry from assuming an incompatible CPU layout. */
public final class OStatsCpuGuard implements IXposedHookLoadPackage {
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean THERMAL_RECOVERY_REPORTED = new AtomicBoolean(false);
    private static final String TAG = "ColorOSRuntimeFix";

    /*
     * The transplanted QTI thermal HAL advertises a 46.5 C severe threshold
     * for its virtual skin sensor, but no usable cold/clear threshold.  Once
     * the sensor reports SEVERE, that status can therefore remain latched even
     * after both skin-msm-therm and the HAL value have cooled substantially.
     * Prefer the tablet's physical skin-msm-therm sensor and clear only below
     * 43 C; use a conservative 2 C virtual-sensor hysteresis as a fallback.
     * The vendor thermal engine and all kernel trip points stay active.
     */
    private static final float SKIN_SEVERE_CLEAR_C = 44.5f;
    private static final float PHYSICAL_SKIN_CLEAR_C = 43.0f;
    private static final int TEMPERATURE_TYPE_SKIN = 3;
    private static final int THROTTLING_SEVERE = 3;
    private static final int THROTTLING_NONE = 0;
    private static volatile String physicalSkinTempPath;

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (DeviceGate.isSupported() && "android".equals(loadPackageParam.packageName) && "android".equals(loadPackageParam.processName)) {
            try {
                Field declaredField = Class.forName("com.oplus.util.OplusHoraeThermalHelper", true, loadPackageParam.classLoader).getDeclaredField("sHoraeProp");
                declaredField.setAccessible(true);
                declaredField.setInt(null, 1);
                XposedBridge.log("ColorOSRuntimeFix: corrected cached Horae enable flag");
            } catch (Throwable th) {
                XposedBridge.log("ColorOSRuntimeFix: Horae cached flag correction failed");
                XposedBridge.log(th);
            }
            installStaleSkinStatusRecovery();
            try {
                XposedHelpers.findAndHookMethod("com.android.server.hans.ostats.calc.CpuCalc", loadPackageParam.classLoader, "calculatePower", new Object[]{long[].class, new XC_MethodHook() { // from class: com.aclaniakea.colorosostatsguard.OStatsCpuGuard.1
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        long[] jArr = (long[]) methodHookParam.args[0];
                        double[] dArr = (double[]) XposedHelpers.getObjectField(methodHookParam.thisObject, "mCpuWeight");
                        if (jArr == null || dArr == null || jArr.length <= dArr.length) {
                            return;
                        }
                        methodHookParam.setResult(Double.valueOf(0.0d));
                        if (OStatsCpuGuard.REPORTED.compareAndSet(false, true)) {
                            XposedBridge.log("ColorOSRuntimeFix: guarded mismatched OStats CPU arrays " + jArr.length + " > " + dArr.length);
                        }
                    }
                }});
                XposedBridge.log("ColorOSRuntimeFix: OStats CPU hook installed in system_server");
            } catch (Throwable th2) {
                XposedBridge.log("ColorOSRuntimeFix: OStats CPU hook installation failed");
                XposedBridge.log(th2);
            }
        }
    }

    private static void installStaleSkinStatusRecovery() {
        try {
            Class<?> temperatureClass = XposedHelpers.findClass("android.os.Temperature", null);
            XposedHelpers.findAndHookMethod(temperatureClass, "getStatus", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof Integer) || ((Integer) result).intValue() < THROTTLING_SEVERE) {
                        return;
                    }
                    int type = ((Integer) XposedHelpers.callMethod(param.thisObject, "getType")).intValue();
                    if (type != TEMPERATURE_TYPE_SKIN) {
                        return;
                    }
                    float value = ((Float) XposedHelpers.callMethod(param.thisObject, "getValue")).floatValue();
                    float physicalValue = readPhysicalSkinTemperatureC();
                    boolean physicalIsCool = !Float.isNaN(physicalValue)
                            && physicalValue <= PHYSICAL_SKIN_CLEAR_C;
                    boolean virtualIsCool = Float.isNaN(physicalValue)
                            && !Float.isNaN(value)
                            && value <= SKIN_SEVERE_CLEAR_C;
                    if (!physicalIsCool && !virtualIsCool) {
                        return;
                    }
                    param.setResult(Integer.valueOf(THROTTLING_NONE));
                    if (THERMAL_RECOVERY_REPORTED.compareAndSet(false, true)) {
                        XposedBridge.log(TAG + ": cleared stale skin thermal status, virtual="
                                + value + "C physical=" + physicalValue + "C");
                    }
                }
            });
            XposedBridge.log(TAG + ": stale skin thermal-status recovery installed");
        } catch (Throwable th) {
            XposedBridge.log(TAG + ": stale skin thermal-status recovery failed");
            XposedBridge.log(th);
        }
    }

    private static float readPhysicalSkinTemperatureC() {
        try {
            String path = physicalSkinTempPath;
            if (path == null) {
                File thermalRoot = new File("/sys/class/thermal");
                File[] zones = thermalRoot.listFiles();
                if (zones == null) {
                    return Float.NaN;
                }
                for (File zone : zones) {
                    if (!zone.getName().startsWith("thermal_zone")) {
                        continue;
                    }
                    if ("skin-msm-therm".equals(readFirstLine(new File(zone, "type")))) {
                        path = new File(zone, "temp").getAbsolutePath();
                        physicalSkinTempPath = path;
                        break;
                    }
                }
            }
            if (path == null) {
                return Float.NaN;
            }
            String raw = readFirstLine(new File(path));
            return raw == null ? Float.NaN : Float.parseFloat(raw) / 1000.0f;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
