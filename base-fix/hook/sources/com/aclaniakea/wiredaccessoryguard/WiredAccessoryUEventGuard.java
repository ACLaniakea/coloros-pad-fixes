package com.aclaniakea.wiredaccessoryguard;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps a malformed kernel uevent from restarting system_server.
 *
 * The port's WiredAccessoryManager assumes every event delivered to its
 * observer has NAME or SWITCH_NAME. A malformed switch event violates that
 * assumption and throws a NullPointerException on the UEvent thread. This
 * only drops that invalid event; complete wired accessory events keep the
 * stock path.
 */
public final class WiredAccessoryUEventGuard implements IXposedHookLoadPackage {
    private static final String TARGET =
            "com.android.server.WiredAccessoryManager$WiredAccessoryObserver";
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName) || !"android".equals(lpp.processName)) {
            return;
        }
        try {
            Class<?> observer = XposedHelpers.findClass(TARGET, lpp.classLoader);
            XposedBridge.hookAllMethods(observer, "onUEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 1 || param.args[0] == null) {
                        return;
                    }
                    Object name = XposedHelpers.callMethod(param.args[0], "get", "NAME");
                    if (name == null) {
                        name = XposedHelpers.callMethod(param.args[0], "get", "SWITCH_NAME");
                    }
                    if (name != null) {
                        return;
                    }
                    param.setResult(null);
                    if (REPORTED.compareAndSet(false, true)) {
                        Object devPath = XposedHelpers.callMethod(param.args[0], "get", "DEVPATH");
                        Object subsystem = XposedHelpers.callMethod(param.args[0], "get", "SUBSYSTEM");
                        Object action = XposedHelpers.callMethod(param.args[0], "get", "ACTION");
                        XposedBridge.log("WiredAccessoryUEventGuard: dropped nameless switch uevent"
                                + " action=" + action + " subsystem=" + subsystem
                                + " devPath=" + devPath);
                    }
                }
            });
            XposedBridge.log("WiredAccessoryUEventGuard: installed malformed-uevent guard");
        } catch (Throwable error) {
            XposedBridge.log("WiredAccessoryUEventGuard: install failed");
            XposedBridge.log(error);
        }
    }
}
