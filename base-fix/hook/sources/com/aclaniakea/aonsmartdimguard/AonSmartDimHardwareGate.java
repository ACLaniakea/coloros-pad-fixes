package com.aclaniakea.aonsmartdimguard;

import com.aclaniakea.devicegate.DeviceGate;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Removes a source-ROM feature declaration that has no matching Lenovo CamX
 * AON sensor. The framework advertises aon_luminance_control and therefore
 * binds SmartFaceGaze even though the vendor IAONService reports zero sensors.
 * That broken request can destabilize the shared camera provider.
 *
 * The gate is deliberately scoped to OplusAONSmartDim. It does not change the
 * user's adaptive-sleep setting, ordinary AttentionService dispatch, or any
 * Camera2 client; screen timeout falls back to the stock non-AON path.
 */
public final class AonSmartDimHardwareGate implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.server.power.OplusAONSmartDim";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!DeviceGate.isSupported() || !"android".equals(lpp.packageName)
                || !"android".equals(lpp.processName)) {
            return;
        }
        try {
            Class<?> smartDim = XposedHelpers.findClass(TARGET, lpp.classLoader);
            XC_MethodHook skipVoid = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            };
            XC_MethodHook falseResult = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.FALSE);
                }
            };

            // mIsSmartAONSurpported starts false. Skipping onSystemReady keeps
            // all normal PowerManager paths on their original non-AON branch.
            XposedHelpers.findAndHookMethod(smartDim, "onSystemReady", skipVoid);
            XposedHelpers.findAndHookMethod(smartDim, "isSmartAONEnabled", falseResult);
            XposedHelpers.findAndHookMethod(smartDim, "initStartFaceGaze", skipVoid);
            XposedHelpers.findAndHookMethod(smartDim, "startSmartFaceGaze",
                    String.class, skipVoid);
            XposedBridge.log("AonSmartDimHardwareGate: disabled SmartFaceGaze; CamX has no AON sensor");
        } catch (Throwable error) {
            XposedBridge.log("AonSmartDimHardwareGate: install failed");
            XposedBridge.log(error);
        }
    }
}
