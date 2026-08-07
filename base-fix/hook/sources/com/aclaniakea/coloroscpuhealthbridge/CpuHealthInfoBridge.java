package com.aclaniakea.coloroscpuhealthbridge;

import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.FileReader;

/* loaded from: classes.dex */
/** Adapts CPU-health presentation to the kernel information exposed by the tablet. */
public final class CpuHealthInfoBridge implements IXposedHookLoadPackage {
    private static final String TARGET = "com.coloros.phonemanager";
    private static long previousIdle;
    private static long previousTotal;

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (DeviceGate.isSupported() && TARGET.equals(loadPackageParam.packageName)) {
            synchronized (CpuHealthInfoBridge.class) {
                long[] cpuStat = readCpuStat();
                previousTotal = cpuStat[0];
                previousIdle = cpuStat[1];
            }
            try {
                XposedHelpers.findAndHookMethod("android.os.PerformanceManager", loadPackageParam.classLoader, "getHICpuLoading", new Object[]{new XC_MethodHook() { // from class: com.aclaniakea.coloroscpuhealthbridge.CpuHealthInfoBridge.1
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        methodHookParam.setResult("cur_cpuloading:" + CpuHealthInfoBridge.samplePercent());
                    }
                }});
                XposedBridge.log("CpuHealthInfoBridge: getHICpuLoading bridged");
            } catch (Throwable th) {
                XposedBridge.log("CpuHealthInfoBridge: hook failed");
                XposedBridge.log(th);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized int samplePercent() {
        long[] cpuStat = readCpuStat();
        long j = cpuStat[0];
        long j2 = j - previousTotal;
        long j3 = cpuStat[1];
        long j4 = j3 - previousIdle;
        previousTotal = j;
        previousIdle = j3;
        if (j2 <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, (int) ((((j2 - j4) * 100) + (j2 / 2)) / j2)));
    }

    private static long[] readCpuStat() {
        long[] jArr;
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader("/proc/stat"));
            try {
                String line = bufferedReader.readLine();
                if (line == null || !line.startsWith("cpu ")) {
                    jArr = new long[]{0, 0};
                } else {
                    String[] strArrSplit = line.trim().split("\\s+");
                    long j = 0;
                    for (int i = 1; i < strArrSplit.length; i++) {
                        j += Long.parseLong(strArrSplit[i]);
                    }
                    long j2 = Long.parseLong(strArrSplit[4]);
                    if (strArrSplit.length > 5) {
                        j2 += Long.parseLong(strArrSplit[5]);
                    }
                    jArr = new long[]{j, j2};
                }
                return jArr;
            } finally {
                bufferedReader.close();
            }
        } catch (Throwable unused) {
            return new long[]{0, 0};
        }
    }
}
