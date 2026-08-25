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
import java.util.concurrent.atomic.AtomicInteger;

/* loaded from: classes.dex */
/** Prevents port-only OStats CPU telemetry from assuming an incompatible CPU layout. */
public final class OStatsCpuGuard implements IXposedHookLoadPackage {
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean THERMAL_RECOVERY_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean SOCD_BRIDGE_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean SOCD_RECOVERY_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean CPU_BRIDGE_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean HBM_BRIDGE_REPORTED = new AtomicBoolean(false);
    /**
     * Cached-process ceiling for <=9 GB variants. 48 is where the 96 -> 64 ->
     * 48 walk documented below stopped, but the walk was never finished: each
     * step reduced the swap working set and the next value was never tried
     * because the constant was compiled in, so testing it meant a rebuild and
     * a reboot per arm. Reading it from a property makes the remaining range
     * measurable at runtime - flip the property, let ActivityManagerConstants
     * refresh, and the new ceiling applies - while an absent or out-of-range
     * property keeps the tested 48.
     */
    private static final String CACHE_CAP_PROP = "persist.sys.aclaniakea.max_cached";
    private static final int CACHE_CAP_DEFAULT = 48;
    private static final int CACHE_CAP_MIN = 8;
    private static final int CACHE_CAP_MAX = 256;
    private static final AtomicInteger CACHE_CAP_APPLIED = new AtomicInteger(-1);
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
    private static final int TEMPERATURE_TYPE_CPU = 0;
    private static final int TEMPERATURE_TYPE_SKIN = 3;
    private static final int THROTTLING_SEVERE = 3;
    private static final int THROTTLING_NONE = 0;
    /*
     * ThermalManagerService caches one Temperature per sensor and refreshes it
     * only when the HAL delivers a callback. The transplanted QTI HAL notifies
     * on severity transitions rather than on every sample, so a CPU entry that
     * was captured during a genuine load spike stays in the cache indefinitely.
     * Measured on device while every real zone sat at 38-40 C: the cache still
     * reported CPU3 at 83.0 C and CPU5 at 89.9 C, and apps reading
     * IThermalService.getCurrentTemperatures() see those stale values against a
     * 95 C threshold, which is what makes monitoring apps announce throttling
     * that is not happening.
     *
     * Correct such an entry from the live cpuss-0..3 cluster maximum - a real
     * sensor read at the moment of the call, never a constant. Only entries that
     * disagree with the live reading by more than this margin are touched, so a
     * freshly delivered callback keeps its genuine per-core spread.
     */
    private static final float CPU_STALE_DELTA_C = 15.0f;
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

    /**
     * The configured ceiling, clamped to a sane range. Anything unparseable,
     * absent, or outside the range falls back to the value that was actually
     * measured, so a typo in the property cannot silently produce a ceiling
     * nobody tested.
     */
    private static int cachedProcessCap() {
        try {
            String raw = (String) XposedHelpers.callStaticMethod(
                    Class.forName("android.os.SystemProperties"), "get",
                    CACHE_CAP_PROP, "");
            if (raw != null && raw.length() > 0) {
                int value = Integer.parseInt(raw.trim());
                if (value >= CACHE_CAP_MIN && value <= CACHE_CAP_MAX) {
                    return value;
                }
                XposedBridge.log(TAG + ": ignoring out-of-range " + CACHE_CAP_PROP
                        + "=" + value + "; keeping " + CACHE_CAP_DEFAULT);
            }
        } catch (Throwable ignored) {
            // Unset, unreadable or non-numeric: keep the measured default.
        }
        return CACHE_CAP_DEFAULT;
    }

    private static void applyEightGbCachedProcessPolicy(Object constants) {
        if (constants == null) return;
        int cap = cachedProcessCap();
        try {
            int requested = XposedHelpers.getIntField(
                    constants, "mOverrideMaxCachedProcesses");
            // Lower whatever the framework asks for; additionally allow the
            // ceiling to move back up, but only when the current value is the
            // one this guard installed. Without that second case the cap can
            // only ratchet down within a boot, which made the range
            // untestable: lowering it and then raising the property again left
            // the old, lower ceiling in place. The ownership check is what
            // keeps that from overriding a genuinely lower value the framework
            // chose on its own, e.g. under memory pressure.
            boolean owned = requested == CACHE_CAP_APPLIED.get();
            if (requested > cap || (owned && requested != cap)) {
                XposedHelpers.setIntField(constants,
                        "mOverrideMaxCachedProcesses", cap);
                // Log on the first application and again whenever the ceiling
                // actually changes, so an A/B run can be read off the log.
                if (CACHE_CAP_APPLIED.getAndSet(cap) != cap
                        || CACHE_LIMIT_REPORTED.compareAndSet(false, true)) {
                    XposedBridge.log(TAG + ": cached-process ceiling "
                            + requested + " -> " + cap + " on 8GB-class device");
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
            if (type == TEMPERATURE_TYPE_CPU) {
                float cached = XposedHelpers.getFloatField(temperature, "mValue");
                float liveCluster = readMaxCpuClusterTemperatureC();
                if (!Float.isNaN(liveCluster) && !Float.isNaN(cached)
                        && Math.abs(cached - liveCluster) > CPU_STALE_DELTA_C) {
                    XposedHelpers.setFloatField(temperature, "mValue", liveCluster);
                    if (CPU_BRIDGE_REPORTED.compareAndSet(false, true)) {
                        XposedBridge.log(TAG + ": bridged stale cached CPU reading " + cached
                                + "C to live cluster max=" + liveCluster + "C");
                    }
                }
                return;
            }
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
                        // A stale cached CPU entry can only be corrected on the read
                        // path. ThermalManagerService keeps one Temperature per sensor
                        // and refreshes it when the HAL delivers a callback; the ported
                        // QTI HAL notifies on severity transitions rather than on every
                        // sample, so an object built during a real spike is never
                        // reconstructed and the constructor sanitiser above gets no
                        // second chance. Measured with every real zone at 61 C: the
                        // cache still served CPU3 83.3 C, CPU4 83.7 C and CPU5 89.9 C.
                        // Correct from the live cpuss-0..3 maximum, and only when the
                        // disagreement exceeds CPU_STALE_DELTA_C so a freshly delivered
                        // callback keeps its genuine per-core spread.
                        Object typeObj = XposedHelpers.callMethod(param.thisObject, "getType");
                        if (!(typeObj instanceof Integer)
                                || ((Integer) typeObj).intValue() != TEMPERATURE_TYPE_CPU) {
                            return;
                        }
                        Object cachedObj = param.getResult();
                        if (!(cachedObj instanceof Float)) {
                            return;
                        }
                        float cached = ((Float) cachedObj).floatValue();
                        float liveCluster = readMaxCpuClusterTemperatureC();
                        if (Float.isNaN(liveCluster) || Float.isNaN(cached)
                                || Math.abs(cached - liveCluster) <= CPU_STALE_DELTA_C) {
                            return;
                        }
                        param.setResult(Float.valueOf(liveCluster));
                        if (CPU_BRIDGE_REPORTED.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + ": bridged stale cached CPU reading " + cached
                                    + "C to live cluster max=" + liveCluster + "C");
                        }
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
            if (raw == null) {
                return Float.NaN;
            }
            float value = Float.parseFloat(raw) / 1000.0f;
            // Same plausibility gate the cluster reader applies. Nothing here can
            // resolve to one of the port's phantom zones - the lookup above matches
            // the exact name skin-msm-therm, never a substring such as -therm, so
            // xo-therm / wls-therm / usb-therm and friends can never be selected.
            // This guards only against the physical sensor itself going bad and
            // reporting an out-of-range value, which would otherwise be bridged
            // straight into hbm and every SKIN-type consumer.
            if (value < 0.0f || value > 125.0f) {
                return Float.NaN;
            }
            return value;
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
