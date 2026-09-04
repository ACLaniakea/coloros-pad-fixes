package com.aclaniakea.uafqoscompat;

import com.aclaniakea.devicegate.DeviceGate;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Makes the port follow the stock phone's effective UAF QoS fallback.
 *
 * The transplanted framework generates QoS resources whose scene id and/or
 * type is absent from its shipped scene table.  Even the three masks present
 * in that table have no matching rule type at runtime. QosScheduler therefore
 * repeatedly logs and walks its failure path in system_server. The stock
 * reference disables this optional QoS sub-scheduler when the table is not
 * usable; memory, HybridSwap, perf HAL, WALT and normal Oplus scene handling
 * do not use this method.
 *
 * This is intentionally a narrow framework compatibility guard rather than a
 * synthetic policy table.  Inventing policies for another CPU topology would
 * alter scheduling behaviour more than the stock fallback does.
 */
public final class UafQosCompat implements IXposedHookLoadPackage {
    private static final String TARGET =
            "com.android.server.oplus.osense.feature.uaf.qos.QosSchedulerManager";
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName) || !"android".equals(lpp.processName)
                || !DeviceGate.isSupported()) {
            return;
        }
        try {
            Class<?> scheduler = XposedHelpers.findClass(TARGET, lpp.classLoader);
            XposedBridge.hookAllMethods(scheduler, "executeSceneQosStrategy",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Match the reference device's disabled optional
                            // QoS sub-scheduler.  This avoids dispatching a
                            // resource into a table that has no usable rules.
                            param.setResult(null);
                            if (REPORTED.compareAndSet(false, true)) {
                                XposedBridge.log("UafQosCompat: bypassed incomplete optional QoS scene path");
                            }
                        }
                    });
            XposedBridge.log("UafQosCompat: installed stock-fallback guard");
        } catch (Throwable error) {
            XposedBridge.log("UafQosCompat: install failed");
            XposedBridge.log(error);
        }
    }
}
