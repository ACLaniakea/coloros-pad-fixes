package com.aclaniakea.dolbybridge;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps the sound-effects mode menu interactive on this port.
 *
 * With OReality removed the menu map ends up with a single entry (1=杜比全景声),
 * so Settings auto-disables the menu (one-choice mode). We re-add the
 * "原始" (OFF) entry so the user can toggle between OFF and Dolby Atmos, and
 * reflect the real Dolby state instead of the stale service-less default.
 */
public final class SoundEffectsMenuFix implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.settings";
    private static final String FRAGMENT = "com.oplus.settings.feature.soundeffects.view.SoundEffectsFragment";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(FRAGMENT, lp.classLoader, "updateSoundEffectsMenu",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object map = XposedHelpers.getObjectField(p.thisObject, "mSoundEffectValueToTitleMap");
                                if (map instanceof java.util.Map) {
                                    java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) map;
                                    if (!m.containsKey("0")) {
                                        m.put("0", "原始");
                                        XposedBridge.log("SoundEffectsMenuFix: added 原始 OFF entry, size=" + m.size());
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("SoundEffectsMenuFix: map patch failed " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: hook failed " + t);
        }
    }
}
