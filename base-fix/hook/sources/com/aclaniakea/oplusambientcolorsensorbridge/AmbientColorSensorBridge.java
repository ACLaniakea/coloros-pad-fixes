package com.aclaniakea.oplusambientcolorsensorbridge;

import android.content.Context;
import android.hardware.SensorEvent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Pair;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/* loaded from: classes.dex */
/** Bridges ColorOS ambient-colour sensor consumers to the tablet's real sensor path. */
public final class AmbientColorSensorBridge implements IXposedHookLoadPackage {
    private static final String ANIMATION_MANAGER_CLASS = "com.android.server.display.color.eyeprotect.manager.AnimationManager";
    private static final int CCT_INDEX = 0;
    private static final long ENVIRONMENT_TRANSITION_GRACE_MS = 750;
    private static final int FRAMEWORK_LUX_INDEX = 9;
    private static final long MAX_ENV_RGB_ANIMATION_DURATION_MS = 30000;
    private static final String PROTECT_EYES_UTIL_CLASS = "com.android.server.display.color.eyeprotect.util.ProtectEyesUtil";
    private static final int REAL_LUX_INDEX = 1;
    private static final String REDUCE_SATURATION_UTIL_CLASS = "com.android.server.display.color.eyeprotect.util.OplusReduceSaturationUtil";
    private static final String RGB_MANAGER_CLASS = "com.android.server.display.color.eyeprotect.manager.OplusRgbBallManager";
    private static final String SETTING_ENABLE_COLOR_TEMPERATURE_REGULATION = "setting_enable_color_temperature_regulation";
    private static final String TAG = "AmbientColorSensorBridge";
    private static final String TARGET_CLASS = "com.android.server.display.color.eyeprotect.manager.AiCurveManager";
    private static final String TEMPERATURE_ENTITY_CLASS = "com.android.server.display.color.eyeprotect.model.OplusTemperatureEntity";
    private static final AtomicInteger REMAP_COUNT = new AtomicInteger();
    private static final long DEFAULT_ENV_RGB_ANIMATION_DURATION_MS = 4000;
    private static final AtomicLong ENV_ANIMATION_DURATION_MS = new AtomicLong(DEFAULT_ENV_RGB_ANIMATION_DURATION_MS);
    private static final AtomicLong ENVIRONMENT_TRANSITION_UNTIL_MS = new AtomicLong(0);
    private static final AtomicInteger RGB_DURATION_LOG_COUNT = new AtomicInteger();
    private static final AtomicInteger COEFF_FALLBACK_LOG_COUNT = new AtomicInteger();
    private static final AtomicInteger GET_RGB_FALLBACK_LOG_COUNT = new AtomicInteger();

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if ("android".equals(loadPackageParam.packageName) && "android".equals(loadPackageParam.processName)) {
            XposedBridge.log("AmbientColorSensorBridge: installed " + (hookMethod(loadPackageParam.classLoader, "handleColorTemperatureChange") + hookMethod(loadPackageParam.classLoader, "handleColorTemperatureTwoChange") + hookEnvironmentState(loadPackageParam.classLoader) + hookEnvironmentalAnimation(loadPackageParam.classLoader) + hookRgbAnimationDuration(loadPackageParam.classLoader) + hookDefaultRgbPath(loadPackageParam.classLoader) + hookRgbMatrixQuery(loadPackageParam.classLoader)) + " sensor bridge hook(s)");
        }
    }

    private static int hookEnvironmentState(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader, "changeColorTemperatureRegulation", new Object[]{TEMPERATURE_ENTITY_CLASS, new XC_MethodHook() { // from class: com.aclaniakea.oplusambientcolorsensorbridge.AmbientColorSensorBridge.1
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (methodHookParam.args == null || methodHookParam.args.length == 0 || methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX] == null) {
                        return;
                    }
                    try {
                        if (!XposedHelpers.getBooleanField(methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX], "mForceClose") && AmbientColorSensorBridge.isColorTemperatureRegulationOpen(methodHookParam.thisObject, classLoader)) {
                            Object objectField = XposedHelpers.getObjectField(methodHookParam.thisObject, "mAiCurveModel");
                            if (objectField != null) {
                                XposedHelpers.setBooleanField(objectField, "mIsDragging", false);
                            }
                            XposedHelpers.setBooleanField(methodHookParam.thisObject, "mUserByDrag", false);
                            XposedBridge.log("AmbientColorSensorBridge: environmental mode cleared transient drag state");
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
            XposedBridge.log("AmbientColorSensorBridge: environmental state hook installed");
            return REAL_LUX_INDEX;
        } catch (Throwable th) {
            XposedBridge.log("AmbientColorSensorBridge: failed to hook environmental state: " + th.toString());
            return CCT_INDEX;
        }
    }

    private static int hookEnvironmentalAnimation(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(ANIMATION_MANAGER_CLASS, classLoader, "showForAnimation", new Object[]{Integer.TYPE, Long.TYPE, Boolean.TYPE, new XC_MethodHook() { // from class: com.aclaniakea.oplusambientcolorsensorbridge.AmbientColorSensorBridge.2
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (methodHookParam.args == null || methodHookParam.args.length < 3 || !(methodHookParam.args[AmbientColorSensorBridge.REAL_LUX_INDEX] instanceof Long)) {
                        return;
                    }
                    long jLongValue = ((Long) methodHookParam.args[AmbientColorSensorBridge.REAL_LUX_INDEX]).longValue();
                    if ((methodHookParam.args[2] instanceof Boolean) && ((Boolean) methodHookParam.args[2]).booleanValue() && jLongValue > 100 && jLongValue <= AmbientColorSensorBridge.MAX_ENV_RGB_ANIMATION_DURATION_MS) {
                        AmbientColorSensorBridge.ENVIRONMENT_TRANSITION_UNTIL_MS.set(SystemClock.elapsedRealtime() + jLongValue + AmbientColorSensorBridge.ENVIRONMENT_TRANSITION_GRACE_MS);
                    }
                    boolean zIsColorTemperatureRegulationOpen = AmbientColorSensorBridge.isColorTemperatureRegulationOpen(methodHookParam.thisObject, classLoader);
                    if (jLongValue <= 100 || jLongValue > AmbientColorSensorBridge.MAX_ENV_RGB_ANIMATION_DURATION_MS) {
                        return;
                    }
                    if ((zIsColorTemperatureRegulationOpen || AmbientColorSensorBridge.isEnvironmentTransitionActive()) && !AmbientColorSensorBridge.isUserDraggingCct(methodHookParam.thisObject, classLoader)) {
                        try {
                            Object objCallStaticMethod = XposedHelpers.callStaticMethod(XposedHelpers.findClass(AmbientColorSensorBridge.RGB_MANAGER_CLASS, classLoader), "getInstance", new Object[AmbientColorSensorBridge.CCT_INDEX]);
                            Object objCallMethod = XposedHelpers.callMethod(objCallStaticMethod, "isSupportColorModeRGB", new Object[AmbientColorSensorBridge.CCT_INDEX]);
                            if ((objCallMethod instanceof Boolean) && ((Boolean) objCallMethod).booleanValue()) {
                                AmbientColorSensorBridge.ENV_ANIMATION_DURATION_MS.set(jLongValue);
                                if (AmbientColorSensorBridge.RGB_DURATION_LOG_COUNT.incrementAndGet() <= 12) {
                                    XposedBridge.log("AmbientColorSensorBridge: ambient RGB duration=" + jLongValue + " cct=" + methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX]);
                                }
                            }
                        } catch (Throwable unused) {
                        }
                    }
                }
            }});
            XposedBridge.log("AmbientColorSensorBridge: environmental animation hook installed");
            return REAL_LUX_INDEX;
        } catch (Throwable th) {
            XposedBridge.log("AmbientColorSensorBridge: failed to hook environmental animation: " + th.toString());
            return CCT_INDEX;
        }
    }

    private static int hookDefaultRgbPath(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(REDUCE_SATURATION_UTIL_CLASS, classLoader, "setRGB", new Object[]{Pair.class, Integer.TYPE, Integer.TYPE, Boolean.TYPE, new XC_MethodHook() { // from class: com.aclaniakea.oplusambientcolorsensorbridge.AmbientColorSensorBridge.3
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (methodHookParam.args != null && methodHookParam.args.length >= 4 && (methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX] instanceof Pair) && (methodHookParam.args[AmbientColorSensorBridge.REAL_LUX_INDEX] instanceof Integer) && (methodHookParam.args[2] instanceof Integer) && (methodHookParam.args[3] instanceof Boolean) && ((Boolean) methodHookParam.args[3]).booleanValue()) {
                        try {
                            Object obj = methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX];
                            Object objectField = XposedHelpers.getObjectField(obj, "first");
                            Object objectField2 = XposedHelpers.getObjectField(obj, "second");
                            if (!AmbientColorSensorBridge.isDefaultRgb(classLoader) && AmbientColorSensorBridge.hasUsableProtectCoeff(objectField) && AmbientColorSensorBridge.hasUsableProtectCoeff(objectField2)) {
                                return;
                            }
                            methodHookParam.args[3] = Boolean.FALSE;
                            if (AmbientColorSensorBridge.COEFF_FALLBACK_LOG_COUNT.incrementAndGet() <= 12) {
                                XposedBridge.log("AmbientColorSensorBridge: coefficient fallback " + ("mainCoeff=" + AmbientColorSensorBridge.describeProtectCoeff(objectField) + " subCoeff=" + AmbientColorSensorBridge.describeProtectCoeff(objectField2) + " cct=" + methodHookParam.args[2] + " useCoeff=false"));
                            }
                        } catch (Throwable unused) {
                        }
                    }
                }
            }});
            XposedBridge.log("AmbientColorSensorBridge: RGB coefficient fallback hook installed");
            return REAL_LUX_INDEX;
        } catch (Throwable th) {
            XposedBridge.log("AmbientColorSensorBridge: failed to hook RGB coefficient fallback: " + th.toString());
            return CCT_INDEX;
        }
    }

    private static int hookRgbMatrixQuery(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(REDUCE_SATURATION_UTIL_CLASS, classLoader, "getRGB", new Object[]{Pair.class, Integer.TYPE, Integer.TYPE, Context.class, Boolean.TYPE, new XC_MethodHook() { // from class: com.aclaniakea.oplusambientcolorsensorbridge.AmbientColorSensorBridge.4
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (methodHookParam.args != null && methodHookParam.args.length >= 5 && (methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX] instanceof Pair) && (methodHookParam.args[4] instanceof Boolean) && ((Boolean) methodHookParam.args[4]).booleanValue()) {
                        try {
                            Object obj = methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX];
                            Object objectField = XposedHelpers.getObjectField(obj, "first");
                            Object objectField2 = XposedHelpers.getObjectField(obj, "second");
                            if (!AmbientColorSensorBridge.isDefaultRgb(classLoader) && AmbientColorSensorBridge.hasUsableProtectCoeff(objectField) && AmbientColorSensorBridge.hasUsableProtectCoeff(objectField2)) {
                                return;
                            }
                            methodHookParam.args[4] = Boolean.FALSE;
                            if (AmbientColorSensorBridge.GET_RGB_FALLBACK_LOG_COUNT.incrementAndGet() <= 8) {
                                XposedBridge.log("AmbientColorSensorBridge: getRGB coefficient fallback mainCoeff=" + AmbientColorSensorBridge.describeProtectCoeff(objectField) + " subCoeff=" + AmbientColorSensorBridge.describeProtectCoeff(objectField2) + " cct=" + methodHookParam.args[2] + " useCoeff=false");
                            }
                        } catch (Throwable unused) {
                        }
                    }
                }
            }});
            XposedBridge.log("AmbientColorSensorBridge: RGB matrix query fallback hook installed");
            return REAL_LUX_INDEX;
        } catch (Throwable th) {
            XposedBridge.log("AmbientColorSensorBridge: failed to hook RGB matrix query fallback: " + th.toString());
            return CCT_INDEX;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean hasUsableProtectCoeff(Object obj) {
        if (obj == null) {
            return false;
        }
        try {
            Object objectField = XposedHelpers.getObjectField(obj, "protectCoeff");
            if (!(objectField instanceof List)) {
                return false;
            }
            List list = (List) objectField;
            if (list.size() != FRAMEWORK_LUX_INDEX) {
                return false;
            }
            for (Object obj2 : list) {
                if (!(obj2 instanceof Double[]) || ((Double[]) obj2).length != 5) {
                    return false;
                }
            }
            return true;
        } catch (Throwable unused) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String describeProtectCoeff(Object obj) {
        if (obj == null) {
            return "null-rgb";
        }
        try {
            Object objectField = XposedHelpers.getObjectField(obj, "protectCoeff");
            if (objectField instanceof List) {
                return Integer.toString(((List) objectField).size());
            }
            return objectField == null ? "null" : objectField.getClass().getSimpleName();
        } catch (Throwable unused) {
            return "unreadable";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isEnvironmentTransitionActive() {
        return SystemClock.elapsedRealtime() < ENVIRONMENT_TRANSITION_UNTIL_MS.get();
    }

    private static boolean isDefaultRgb(ClassLoader classLoader) {
        try {
            Object manager = XposedHelpers.callStaticMethod(XposedHelpers.findClass(RGB_MANAGER_CLASS, classLoader), "getInstance", new Object[0]);
            Object result = XposedHelpers.callMethod(manager, "isCurDefaultRGB", new Object[0]);
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (Throwable unused) {
            return false;
        }
    }

    private static int hookRgbAnimationDuration(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(RGB_MANAGER_CLASS, classLoader, "postRGBAnimationCallback", new Object[]{new XC_MethodHook() { // from class: com.aclaniakea.oplusambientcolorsensorbridge.AmbientColorSensorBridge.5
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    try {
                        Object obj = methodHookParam.thisObject;
                        if (AmbientColorSensorBridge.isColorTemperatureRegulationOpen(obj, classLoader) && !AmbientColorSensorBridge.isUserDraggingCct(obj, classLoader)) {
                            long j = AmbientColorSensorBridge.ENV_ANIMATION_DURATION_MS.get();
                            if (j <= 100 || j > AmbientColorSensorBridge.MAX_ENV_RGB_ANIMATION_DURATION_MS) {
                                j = AmbientColorSensorBridge.DEFAULT_ENV_RGB_ANIMATION_DURATION_MS;
                            }
                            Object objectField = XposedHelpers.getObjectField(obj, "mValueAnimator");
                            if (objectField == null) {
                                return;
                            }
                            XposedHelpers.callMethod(objectField, "setDuration", new Object[]{Long.valueOf(j)});
                            if (AmbientColorSensorBridge.RGB_DURATION_LOG_COUNT.incrementAndGet() <= 12) {
                                XposedBridge.log("AmbientColorSensorBridge: applied RGB animator duration=" + j);
                            }
                        }
                    } catch (Throwable unused) {
                    }
                }
            }});
            XposedBridge.log("AmbientColorSensorBridge: RGB animation duration hook installed");
            return REAL_LUX_INDEX;
        } catch (Throwable th) {
            XposedBridge.log("AmbientColorSensorBridge: failed to hook RGB animation duration: " + th.toString());
            return CCT_INDEX;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isColorTemperatureRegulationOpen(Object obj, ClassLoader classLoader) {
        try {
            try {
                Object objCallStaticMethod = XposedHelpers.callStaticMethod(XposedHelpers.findClass(PROTECT_EYES_UTIL_CLASS, classLoader), "isOpenSettingColorTemperature", new Object[]{((Context) XposedHelpers.getObjectField(obj, "mContext")).getContentResolver(), Integer.valueOf(XposedHelpers.getIntField(obj, "mCurrentUser"))});
                if (objCallStaticMethod instanceof Boolean) {
                    if (((Boolean) objCallStaticMethod).booleanValue()) {
                        return true;
                    }
                }
                return false;
            } catch (Throwable unused) {
                Context context = (Context) XposedHelpers.getObjectField(obj, "mContext");
                XposedHelpers.getIntField(obj, "mCurrentUser");
                return Settings.System.getInt(context.getContentResolver(), SETTING_ENABLE_COLOR_TEMPERATURE_REGULATION, CCT_INDEX) == REAL_LUX_INDEX;
            }
        } catch (Throwable unused2) {
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isUserDraggingCct(Object obj, ClassLoader classLoader) {
        Context context;
        int intField;
        try {
            context = (Context) XposedHelpers.getObjectField(obj, "mContext");
            intField = XposedHelpers.getIntField(obj, "mCurrentUser");
        } catch (Throwable unused) {
            // JADX cannot express the original failure edge correctly.  A
            // missing private field simply means no active manual CCT drag.
            return false;
        }
        if (context == null) {
            return false;
        }
        Object objCallStaticMethod = XposedHelpers.callStaticMethod(XposedHelpers.findClass(PROTECT_EYES_UTIL_CLASS, classLoader), "isUserDragCCT", new Object[]{context.getContentResolver(), Integer.valueOf(intField)});
        if (objCallStaticMethod instanceof Boolean) {
            if (((Boolean) objCallStaticMethod).booleanValue()) {
                return true;
            }
        }
        return false;
    }

    private static int hookMethod(ClassLoader classLoader, String str) {
        try {
            XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader, str, new Object[]{SensorEvent.class, new XC_MethodHook() { // from class: com.aclaniakea.oplusambientcolorsensorbridge.AmbientColorSensorBridge.6
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    float[] fArr;
                    if (methodHookParam.args == null || methodHookParam.args.length == 0 || !(methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX] instanceof SensorEvent) || (fArr = ((SensorEvent) methodHookParam.args[AmbientColorSensorBridge.CCT_INDEX]).values) == null || fArr.length <= AmbientColorSensorBridge.FRAMEWORK_LUX_INDEX) {
                        return;
                    }
                    float f = fArr[AmbientColorSensorBridge.CCT_INDEX];
                    float f2 = fArr[AmbientColorSensorBridge.REAL_LUX_INDEX];
                    if (!AmbientColorSensorBridge.isFinite(f) || !AmbientColorSensorBridge.isFinite(f2) || f < 1000.0f || f > 20000.0f || f2 < 0.0f || f2 > 200000.0f) {
                        return;
                    }
                    float f3 = fArr[AmbientColorSensorBridge.FRAMEWORK_LUX_INDEX];
                    fArr[AmbientColorSensorBridge.FRAMEWORK_LUX_INDEX] = f2;
                    int iIncrementAndGet = AmbientColorSensorBridge.REMAP_COUNT.incrementAndGet();
                    if (iIncrementAndGet <= 5) {
                        XposedBridge.log("AmbientColorSensorBridge: remapped sample #" + iIncrementAndGet + " cct=" + f + " lux=" + f2 + " oldIndex9=" + f3);
                    }
                }
            }});
            return REAL_LUX_INDEX;
        } catch (Throwable th) {
            XposedBridge.log("AmbientColorSensorBridge: failed to hook " + str);
            XposedBridge.log(th);
            return CCT_INDEX;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isFinite(float f) {
        return (Float.isNaN(f) || Float.isInfinite(f)) ? false : true;
    }
}
