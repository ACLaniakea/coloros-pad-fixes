package com.aclaniakea.colorosocrcamerabridge;

import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
/** Keeps ColorOS OCR camera setup on the available tablet camera implementation. */
public final class OcrScannerCameraBridge implements IXposedHookLoadPackage {
    private static final String TARGET = "com.coloros.ocrscanner";

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (DeviceGate.isSupported() && TARGET.equals(loadPackageParam.packageName) && !hasCameraUnit(loadPackageParam.classLoader)) {
            try {
                XposedHelpers.findAndHookMethod("com.oplus.scanner.ui.preview.b", loadPackageParam.classLoader, "b", new Object[]{new XC_MethodHook() {

                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                        methodHookParam.setResult(Class.forName("com.oplus.scanner.ui.preview.camera_2.Camera2PreviewImpl", true, loadPackageParam.classLoader).getDeclaredConstructor(new Class[0]).newInstance(new Object[0]));
                    }
                }});
                XposedBridge.log("OcrScannerCameraBridge: Camera2 fallback enabled");
            } catch (Throwable th) {
                XposedBridge.log("OcrScannerCameraBridge: hook failed");
                XposedBridge.log(th);
            }
        }
    }

    private static boolean hasCameraUnit(ClassLoader classLoader) {
        try {
            Class.forName("com.coloros.ocs.camera.impl.CameraUnitImpl", false, classLoader);
            return true;
        } catch (Throwable unused) {
            return false;
        }
    }
}
