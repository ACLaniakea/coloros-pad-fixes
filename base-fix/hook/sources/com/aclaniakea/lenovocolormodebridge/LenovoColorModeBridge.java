package com.aclaniakea.lenovocolormodebridge;

import android.app.Activity;
import android.os.Bundle;
import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* loaded from: classes.dex */
/** Maps the visible ColorOS colour-mode choices to the Lenovo display backend. */
public final class LenovoColorModeBridge implements IXposedHookLoadPackage {
    private static final String FRAGMENT = "com.oplus.settings.feature.display.protecteyes.ColorModeFragment";
    private static final String KEY_SOFT = "color_mode_soft";
    private static final String KEY_VIVID = "color_mode_vivid";
    private static final String TAG = "LenovoColorModeBridge";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (DeviceGate.isSupported() && "com.android.settings".equals(loadPackageParam.packageName) && "com.android.settings".equals(loadPackageParam.processName)) {
            try {
                XposedHelpers.findAndHookMethod(FRAGMENT, loadPackageParam.classLoader, "onCreate", new Object[]{Bundle.class, new XC_MethodHook() { // from class: com.aclaniakea.lenovocolormodebridge.LenovoColorModeBridge.1
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        LenovoColorModeBridge.applyTwoModeUi(methodHookParam.thisObject);
                    }
                }});
                XposedHelpers.findAndHookMethod(FRAGMENT, loadPackageParam.classLoader, "onResume", new Object[]{new XC_MethodHook() { // from class: com.aclaniakea.lenovocolormodebridge.LenovoColorModeBridge.2
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        LenovoColorModeBridge.applyTwoModeUi(methodHookParam.thisObject);
                    }
                }});
                XposedHelpers.findAndHookMethod(FRAGMENT, loadPackageParam.classLoader, "updatePreference", new Object[]{Integer.TYPE, new XC_MethodHook() { // from class: com.aclaniakea.lenovocolormodebridge.LenovoColorModeBridge.3
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        if (methodHookParam.args == null || methodHookParam.args.length <= 0 || !(methodHookParam.args[0] instanceof Integer) || ((Integer) methodHookParam.args[0]).intValue() != 6) {
                            return;
                        }
                        methodHookParam.args[0] = 0;
                    }
                }});
                XposedHelpers.findAndHookMethod(FRAGMENT, loadPackageParam.classLoader, "onPreferenceTreeClick", new Object[]{XposedHelpers.findClass("androidx.preference.Preference", loadPackageParam.classLoader), new XC_MethodHook() { // from class: com.aclaniakea.lenovocolormodebridge.LenovoColorModeBridge.4
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        int i;
                        if (methodHookParam.args == null || methodHookParam.args.length == 0 || methodHookParam.args[0] == null) {
                            return;
                        }
                        Object objCallMethod = XposedHelpers.callMethod(methodHookParam.args[0], "getKey", new Object[0]);
                        if (objCallMethod instanceof String) {
                            String str = (String) objCallMethod;
                            if (LenovoColorModeBridge.KEY_VIVID.equals(str)) {
                                i = 0;
                            } else if (!LenovoColorModeBridge.KEY_SOFT.equals(str)) {
                                return;
                            } else {
                                i = 1;
                            }
                            Object objCallMethod2 = XposedHelpers.callMethod(methodHookParam.thisObject, "getActivity", new Object[0]);
                            if (objCallMethod2 instanceof Activity) {
                                XposedHelpers.callMethod(methodHookParam.thisObject, "showPromptDialog", new Object[]{objCallMethod2, Integer.valueOf(i)});
                                methodHookParam.setResult(Boolean.FALSE);
                            }
                        }
                    }
                }});
                XposedBridge.log("LenovoColorModeBridge: native/standard Settings hook installed");
            } catch (Throwable th) {
                XposedBridge.log("LenovoColorModeBridge: Settings hook installation failed");
                XposedBridge.log(th);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void applyTwoModeUi(Object obj) {
        try {
            Object objectField = XposedHelpers.getObjectField(obj, "mColorModeVivid");
            Object objectField2 = XposedHelpers.getObjectField(obj, "mColorModeSoft");
            setText(objectField, "原生", "以屏幕原生色域显示，色彩更加艳丽");
            setText(objectField2, "标准", "以标准色域显示，色彩更加自然");
            Object objectField3 = XposedHelpers.getObjectField(obj, "mCategory");
            if (objectField3 == null) {
                return;
            }
            String[] strArr = {"mProfessionalModePreference", "mColorModeColorful", "mColorModeAdaptive", "mColorModeCinema", "mColorModeCinema3", "mColorModeOplusColorful", "mColorModeColorfulOplus3", "mColorModePowerSaving", "mColorMode3MoreDivider", "mColorMode3MoreDivider1", "mColorMode3MoreDivider2", "mColorSpace"};
            for (int i = 0; i < 12; i++) {
                Object objectField4 = XposedHelpers.getObjectField(obj, strArr[i]);
                if (objectField4 != null) {
                    XposedHelpers.callMethod(objectField3, "removePreference", new Object[]{objectField4});
                }
            }
        } catch (Throwable th) {
            XposedBridge.log("LenovoColorModeBridge: apply two-mode UI failed: " + th);
        }
    }

    private static void setText(Object obj, String str, String str2) {
        if (obj == null) {
            return;
        }
        XposedHelpers.callMethod(obj, "setTitle", new Object[]{str});
        XposedHelpers.callMethod(obj, "setSummary", new Object[]{str2});
    }
}
