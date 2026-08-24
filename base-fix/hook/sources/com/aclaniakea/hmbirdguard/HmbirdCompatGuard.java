package com.aclaniakea.hmbirdguard;

import com.aclaniakea.devicegate.DeviceGate;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Disables only the source-ROM HMBIRD scene bridge on Lenovo GKI.
 *
 * ColorOS enables the manager whenever its binder stub exists, even when
 * sys.oplus.hmbird.manager.enable is false. It then registers a window
 * listener and dereferences scene data supplied by the missing HMBIRD kernel
 * stack. Scene's independent scheduler and the normal OPlus perf HAL do not
 * use this class.
 */
public final class HmbirdCompatGuard implements IXposedHookLoadPackage {
    private static final String TARGET =
            "com.android.server.oplus.osense.feature.uaf.scene.hmbirdscene.HmbirdSceneRecogManager";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName) || !DeviceGate.isSupported()) {
            return;
        }
        try {
            Class<?> manager = XposedHelpers.findClass(TARGET, lpp.classLoader);
            XC_MethodHook falseResult = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.FALSE);
                }
            };
            XposedHelpers.findAndHookMethod(manager, "hmbirdEnableCheck", falseResult);
            XposedHelpers.findAndHookMethod(manager, "isHmbirdSwitchOn", falseResult);
            XposedHelpers.findAndHookMethod(manager, "serviceReady", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
            XposedBridge.log("HmbirdCompatGuard: disabled incompatible scene listener");
        } catch (Throwable error) {
            XposedBridge.log("HmbirdCompatGuard: install failed");
            XposedBridge.log(error);
        }
    }
}
