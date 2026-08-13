package com.aclaniakea.colorosgpustatsbridge;

import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.FileReader;

/* loaded from: classes.dex */
/** Reads real GPU counters instead of using source-device-only statistic paths. */
public final class RealtimeGpuBridge implements IXposedHookLoadPackage {
    private static final String NODE = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (DeviceGate.isSupported() && "com.coloros.phonemanager".equals(loadPackageParam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod("com.oplus.phonemanager.idleoptimize.landing.viewmodel.SuperComputingForVViewModel", loadPackageParam.classLoader, "getGpuBusyPercentage", new Object[]{new XC_MethodHook() { // from class: com.aclaniakea.colorosgpustatsbridge.RealtimeGpuBridge.1
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        Float busy = RealtimeGpuBridge.readBusy();
                        if (busy != null) {
                            methodHookParam.setResult(busy);
                        }
                    }
                }});
                XposedBridge.log("ColorOSRuntimeFix: realtime GPU bridge installed");
            } catch (Throwable th) {
                XposedBridge.log("ColorOSRuntimeFix: realtime GPU bridge failed: " + th);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Float readBusy() {
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader(NODE));
            try {
                String line = bufferedReader.readLine();
                if (line != null) {
                    float f = Float.parseFloat(line.replace("%", "").trim().split("\\s+")[0]);
                    Float fValueOf = (f < 0.0f || f > 100.0f) ? null : Float.valueOf(f);
                    bufferedReader.close();
                    return fValueOf;
                }
                bufferedReader.close();
                return null;
            } finally {
            }
        } catch (Throwable unused) {
            return null;
        }
    }
}
