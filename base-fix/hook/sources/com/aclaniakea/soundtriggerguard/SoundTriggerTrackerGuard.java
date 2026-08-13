package com.aclaniakea.soundtriggerguard;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Neutralizes ExternalCaptureStateTracker on this port.
 *
 * The tracker thread repeatedly calls a private native connect() that aborts
 * system_server with "Assertion failed: status != NO_ERROR" when the
 * underlying audio-policy/SoundTrigger connection is unavailable.  The BWV
 * wakeword path does not use SoundTrigger, so the tracker is unnecessary.
 */
public final class SoundTriggerTrackerGuard implements IXposedHookLoadPackage {
    private static final String TAG = "SoundTriggerTrackerGuard";
    private static final String TARGET = "com.android.server.soundtrigger_middleware.ExternalCaptureStateTracker";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName) || !"android".equals(lpp.processName)) return;
        try {
            XposedHelpers.findAndHookMethod(TARGET, lpp.classLoader, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + ": ExternalCaptureStateTracker.run neutralized; native connect aborted path bypassed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook installation failed");
            XposedBridge.log(t);
        }
    }
}
