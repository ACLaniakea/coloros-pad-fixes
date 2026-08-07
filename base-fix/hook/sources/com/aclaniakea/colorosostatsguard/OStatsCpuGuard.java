package com.aclaniakea.colorosostatsguard;

import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes.dex */
/** Prevents port-only OStats CPU telemetry from assuming an incompatible CPU layout. */
public final class OStatsCpuGuard implements IXposedHookLoadPackage {
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);
    private static final String TAG = "ColorOSRuntimeFix";

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
}
