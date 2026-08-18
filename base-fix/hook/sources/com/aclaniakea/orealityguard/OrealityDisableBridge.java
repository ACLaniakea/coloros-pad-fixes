package com.aclaniakea.orealityguard;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Disables the broken AudioX/OReality effect path on this ColorOS port.
 *
 * The source ROM gates the "OReality 音效" menu on the
 * {@code oplus.software.audio.audiox_support} feature, which is baked into
 * the compiled feature table on this port. Its effect engine descriptor
 * (41f6c0f4-...) is missing on the Lenovo TB710FU vendor, so selecting it
 * fails with AudioEffect init error -3. This bridge forces both feature
 * probes to false inside Settings so the sound-effects page shows only the
 * working Dolby menu.
 */
public final class OrealityDisableBridge implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.settings";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!TARGET.equals(loadPackageParam.packageName)) {
            return;
        }
        for (String cls : new String[]{
                "com.oplus.settings.utils.SysFeatureUtils",
                "com.oplus.settings.utils.FeatureUtils"}) {
            try {
                XposedHelpers.findAndHookMethod(cls, loadPackageParam.classLoader,
                        "isOrealitySupported",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                                methodHookParam.setResult(Boolean.FALSE);
                            }
                        });
                XposedBridge.log("OrealityDisableBridge: disabled " + cls);
            } catch (Throwable th) {
                XposedBridge.log("OrealityDisableBridge: failed " + cls);
                XposedBridge.log(th);
            }
        }
    }
}
