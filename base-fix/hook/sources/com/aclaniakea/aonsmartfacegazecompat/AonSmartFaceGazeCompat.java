package com.aclaniakea.aonsmartfacegazecompat;

import com.aclaniakea.devicegate.DeviceGate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Restores the SmartFaceGaze command table entry omitted by the transplanted
 * AON application.  Framework uses 0x60007 for Smart AON, but this AON build
 * retains the command in its listener table while omitting both its capability
 * branch and camera frame-rate profile.  AONService consequently returns
 * 0x5020 before it sends START_WORK.
 *
 * The original gesture engine already uses the same continuous low-rate
 * Camera2 profile.  Reusing that exact enum object keeps camera creation,
 * QNN inference, callbacks, and lifecycle inside the stock AON service.  The
 * bridge deliberately refuses to enable the command if that stock profile is
 * absent, so a future AON APK layout change cannot create an empty profile.
 */
public final class AonSmartFaceGazeCompat implements IXposedHookLoadPackage {
    private static final String TAG = "AonSmartFaceGazeCompat";
    private static final String AON_PACKAGE = "com.aiunit.aon";
    private static final int SMART_FACE_GAZE = 0x60007;
    private static final int LOW_RATE_GESTURE = 0x60005;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!DeviceGate.isSupported()) {
            return;
        }
        if ("android".equals(lpp.packageName) && "android".equals(lpp.processName)) {
            // Preserve the stock framework cadence and 0x60007 lifecycle.
            // The native AON side below supplies only its missing command
            // profile; it never changes PowerManager timing.
            return;
        }
        if (!AON_PACKAGE.equals(lpp.packageName) || !AON_PACKAGE.equals(lpp.processName)
                || !INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> application = XposedHelpers.findClass(
                    "com.aiunit.aon.AONApplication", lpp.classLoader);
            Object rawProfiles = XposedHelpers.getStaticObjectField(application, "I");
            if (!(rawProfiles instanceof Map)) {
                throw new IllegalStateException("AON command profile map is unavailable");
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> profiles = (Map<Object, Object>) rawProfiles;
            Integer smartCommand = Integer.valueOf(SMART_FACE_GAZE);
            Object lowRateProfile = profiles.get(Integer.valueOf(LOW_RATE_GESTURE));
            if (lowRateProfile == null) {
                throw new IllegalStateException("AON low-rate Camera2 profile is unavailable");
            }
            profiles.put(smartCommand, lowRateProfile);

            XposedHelpers.findAndHookMethod(application, "a", Integer.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object command = param.args != null && param.args.length == 1
                                    ? param.args[0] : null;
                            if (command instanceof Integer
                                    && ((Integer) command).intValue() == SMART_FACE_GAZE) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": restored native 0x60007 with stock low-rate profile");
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + ": compatibility bridge was not installed");
            XposedBridge.log(error);
        }
    }

}
