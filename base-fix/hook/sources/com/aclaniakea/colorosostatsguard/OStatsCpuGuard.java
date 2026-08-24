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
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes.dex */
/** Prevents port-only OStats CPU telemetry from assuming an incompatible CPU layout. */
public final class OStatsCpuGuard implements IXposedHookLoadPackage {
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean THERMAL_RECOVERY_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean SOCD_BRIDGE_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean SOCD_RECOVERY_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean HBM_BRIDGE_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean CACHE_LIMIT_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean POST_BOOT_CACHE_GRACE_REPORTED = new AtomicBoolean(false);
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
    private static final float SOCD_SEVERE_CLEAR_MAX_CLUSTER_C = 80.0f;
    private static volatile String physicalSkinTempPath;
    private static volatile String[] cpuClusterTempPaths;

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
            installSocdTemperatureBridge(loadPackageParam.classLoader);
            installCachedProcessLimitGuard(loadPackageParam.classLoader);
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

    /**
     * The source-phone framework overrides ActivityManager's cache limit to
     * 96 processes. On the 8 GB tablet that retained 80+ processes and built
     * a 3.8 GB ZRAM working set; wake then faulted hundreds of MB of
     * system_server/SystemUI pages back in at once. A 64-process follow-up
     * still produced roughly 3.45 GB swap-in and 5.1 GB swap-out in one
     * repeatable eleven-app switch, including zsmalloc order-0 failures. Use
     * a still-generous 48
     * process ceiling on <=9 GB variants, while preserving lower/default
     * limits and leaving 12 GB variants untouched. ActivityManagerConstants,
     * not ActivityManagerService.setProcessLimit(), owns the cached-process
     * override. The source framework also keeps a hard-coded ten-minute
     * post-boot no-kill window. On this 8 GB target it allows nearly 300
     * ProcessRecords and about 3 GB of ZRAM to accumulate before the first
     * trim, producing simultaneous swap-in and kswapd spikes. Apply both
     * corrections directly to ActivityManagerConstants after construction and
     * after its DeviceConfig refresh paths; no resident worker is needed.
     */
    private static void installCachedProcessLimitGuard(ClassLoader cl) {
        long ramKb = readPhysicalRamKb();
        if (ramKb <= 0L || ramKb > 9437184L) {
            XposedBridge.log(TAG + ": cached-process limit preserved for RAM=" + ramKb + "kB");
            return;
        }
        try {
            Class<?> constants = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerConstants", cl);
            XC_MethodHook policyHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    applyEightGbCachedProcessPolicy(param.thisObject);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyEightGbCachedProcessPolicy(param.thisObject);
                }
            };
            XposedBridge.hookAllConstructors(constants, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyEightGbCachedProcessPolicy(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(constants, "updateMaxCachedProcesses",
                    policyHook);
            for (Method method : constants.getDeclaredMethods()) {
                String name = method.getName().toLowerCase();
                if (name.contains("nokillcached") || name.contains("no_kill_cached")) {
                    XposedBridge.hookMethod(method, policyHook);
                }
            }
            XposedBridge.log(TAG + ": 8GB cached-process and post-boot grace guard installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cached-process limit guard failed");
            XposedBridge.log(t);
        }
    }

    private static void applyEightGbCachedProcessPolicy(Object constants) {
        if (constants == null) return;
        try {
            int requested = XposedHelpers.getIntField(
                    constants, "mOverrideMaxCachedProcesses");
            if (requested > 48) {
                XposedHelpers.setIntField(constants,
                        "mOverrideMaxCachedProcesses", 48);
                if (CACHE_LIMIT_REPORTED.compareAndSet(false, true)) {
                    XposedBridge.log(TAG + ": capped cached processes "
                            + requested + " -> 48 on 8GB-class device");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cached-process limit field update failed");
            XposedBridge.log(t);
        }
        try {
            Field graceField = constants.getClass().getDeclaredField(
                    "mNoKillCachedProcessesPostBootCompletedDurationMillis");
            graceField.setAccessible(true);
            Object oldValue = graceField.get(constants);
            long oldMillis = oldValue instanceof Number
                    ? ((Number) oldValue).longValue() : 0L;
            if (oldMillis != 0L) {
                if (graceField.getType() == Long.TYPE) {
                    graceField.setLong(constants, 0L);
                } else {
                    graceField.setInt(constants, 0);
                }
                if (POST_BOOT_CACHE_GRACE_REPORTED.compareAndSet(false, true)) {
                    XposedBridge.log(TAG + ": post-boot cached-process grace "
                            + oldMillis + "ms -> 0ms on 8GB-class device");
                }
            }
        } catch (NoSuchFieldException ignored) {
            // Older framework revisions do not expose this vendor extension.
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": post-boot cached-process grace update failed");
            XposedBridge.log(t);
        }
    }

    private static long readPhysicalRamKb() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("MemTotal:")) continue;
                String digits = line.replaceAll("[^0-9]", "");
                return digits.length() == 0 ? 0L : Long.parseLong(digits);
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    private static void installStaleSkinStatusRecovery() {
        try {
            Class<?> temperatureClass = XposedHelpers.findClass("android.os.Temperature", null);
            /*
             * ThermalManagerService reads the parcelable fields while handling
             * the HAL callback and caches that object. Hooking only getStatus()
             * therefore protects later clients but cannot stop a stale SEVERE
             * value entering the service cache. Sanitize each newly-created
             * Temperature before the callback consumer sees it as well.
             */
            XposedBridge.hookAllConstructors(temperatureClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sanitizeTemperatureObject(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(temperatureClass, "getStatus", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof Integer) || ((Integer) result).intValue() < THROTTLING_SEVERE) {
                        return;
                    }
                    Object nameObject = XposedHelpers.callMethod(param.thisObject, "getName");
                    if ("socd".equals(nameObject)) {
                        float maxCluster = readMaxCpuClusterTemperatureC();
                        if (!Float.isNaN(maxCluster)
                                && maxCluster <= SOCD_SEVERE_CLEAR_MAX_CLUSTER_C) {
                            param.setResult(Integer.valueOf(THROTTLING_NONE));
                            if (SOCD_RECOVERY_REPORTED.compareAndSet(false, true)) {
                                XposedBridge.log(TAG + ": cleared phantom socd thermal status, maxCluster="
                                        + maxCluster + "C");
                            }
                        }
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

    private static void sanitizeTemperatureObject(Object temperature) {
        try {
            String name = (String) XposedHelpers.callMethod(temperature, "getName");
            // Read the raw field: getStatus() is itself hooked below and may
            // already present a corrected value to callers.
            int status = XposedHelpers.getIntField(temperature, "mStatus");
            if ("hbm".equals(name)) {
                float physicalSkin = readPhysicalSkinTemperatureC();
                if (!Float.isNaN(physicalSkin)) {
                    XposedHelpers.setFloatField(temperature, "mValue", physicalSkin);
                    if (HBM_BRIDGE_REPORTED.compareAndSet(false, true)) {
                        XposedBridge.log(TAG + ": bridged disabled hbm surface reading to skin="
                                + physicalSkin + "C");
                    }
                }
                return;
            }
            if ("socd".equals(name)) {
                float maxCluster = readMaxCpuClusterTemperatureC();
                if (Float.isNaN(maxCluster)) {
                    return;
                }
                XposedHelpers.setFloatField(temperature, "mValue", maxCluster);
                if (status >= THROTTLING_SEVERE
                        && maxCluster <= SOCD_SEVERE_CLEAR_MAX_CLUSTER_C) {
                    XposedHelpers.setIntField(temperature, "mStatus", THROTTLING_NONE);
                    if (SOCD_RECOVERY_REPORTED.compareAndSet(false, true)) {
                        XposedBridge.log(TAG + ": sanitized phantom socd thermal event, maxCluster="
                                + maxCluster + "C");
                    }
                }
                return;
            }
            int type = ((Integer) XposedHelpers.callMethod(temperature, "getType")).intValue();
            if (type != TEMPERATURE_TYPE_SKIN || status < THROTTLING_SEVERE) {
                return;
            }
            float physicalValue = readPhysicalSkinTemperatureC();
            if (!Float.isNaN(physicalValue) && physicalValue <= PHYSICAL_SKIN_CLEAR_C) {
                XposedHelpers.setIntField(temperature, "mStatus", THROTTLING_NONE);
                if (THERMAL_RECOVERY_REPORTED.compareAndSet(false, true)) {
                    float virtualValue = ((Float) XposedHelpers.callMethod(temperature, "getValue"))
                            .floatValue();
                    XposedBridge.log(TAG + ": sanitized stale skin thermal event, virtual="
                            + virtualValue + "C physical=" + physicalValue + "C");
                }
            }
        } catch (Throwable ignored) {
            // Preserve the HAL object unchanged if this ROM changes its fields.
        }
    }

    /*
     * The phone HAL exposes a virtual "socd" value in whole degrees. On this
     * tablet it remains around 92-94 while all four physical cpuss sensors are
     * tens of degrees cooler, so Android clients see a permanent SEVERE BCL
     * temperature. Bridge that one exact virtual name to the maximum physical
     * cluster temperature. If physical data is unavailable, preserve the OEM
     * value/status; never invent a fallback temperature.
     */
    private static void installSocdTemperatureBridge(ClassLoader cl) {
        try {
            Class<?> temperatureClass = XposedHelpers.findClass("android.os.Temperature", cl);
            XposedHelpers.findAndHookMethod(temperatureClass, "getValue", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object nameObj = XposedHelpers.callMethod(param.thisObject, "getName");
                    if (!(nameObj instanceof String)) {
                        return;
                    }
                    if ("hbm".equals(nameObj)) {
                        float physicalSkin = readPhysicalSkinTemperatureC();
                        if (!Float.isNaN(physicalSkin)) {
                            param.setResult(Float.valueOf(physicalSkin));
                            if (HBM_BRIDGE_REPORTED.compareAndSet(false, true)) {
                                XposedBridge.log(TAG + ": bridged disabled hbm surface reading to skin="
                                        + physicalSkin + "C");
                            }
                        }
                        return;
                    }
                    if (!"socd".equals(nameObj)) {
                        return;
                    }
                    float maxCluster = readMaxCpuClusterTemperatureC();
                    if (Float.isNaN(maxCluster)) {
                        return;
                    }
                    param.setResult(Float.valueOf(maxCluster));
                    if (SOCD_BRIDGE_REPORTED.compareAndSet(false, true)) {
                        XposedBridge.log(TAG + ": bridged phantom socd temp to maxCluster="
                                + maxCluster + "C");
                    }
                }
            });
            XposedBridge.log(TAG + ": socd temperature bridge installed");
        } catch (Throwable th) {
            XposedBridge.log(TAG + ": socd temperature bridge failed");
            XposedBridge.log(th);
        }
    }

    private static float readMaxCpuClusterTemperatureC() {
        try {
            String[] paths = cpuClusterTempPaths;
            if (paths == null) {
                File thermalRoot = new File("/sys/class/thermal");
                File[] zones = thermalRoot.listFiles();
                if (zones == null) {
                    return Float.NaN;
                }
                java.util.ArrayList<String> found = new java.util.ArrayList<>();
                for (File zone : zones) {
                    if (!zone.getName().startsWith("thermal_zone")) {
                        continue;
                    }
                    String type = readFirstLine(new File(zone, "type"));
                    if (type != null && type.matches("cpuss-[0-3]")) {
                        found.add(new File(zone, "temp").getAbsolutePath());
                    }
                }
                if (found.isEmpty()) {
                    return Float.NaN;
                }
                paths = found.toArray(new String[0]);
                cpuClusterTempPaths = paths;
            }
            float max = Float.NaN;
            for (String path : paths) {
                String raw = readFirstLine(new File(path));
                if (raw == null) {
                    continue;
                }
                float value = Float.parseFloat(raw) / 1000.0f;
                if (value < 0.0f || value > 125.0f) {
                    continue;
                }
                if (Float.isNaN(max) || value > max) {
                    max = value;
                }
            }
            return max;
        } catch (Throwable ignored) {
            return Float.NaN;
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
