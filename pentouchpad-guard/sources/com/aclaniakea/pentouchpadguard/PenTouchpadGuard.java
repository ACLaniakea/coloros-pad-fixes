package com.aclaniakea.pentouchpadguard;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.InputDevice;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 手写笔吸附守护：笔磁吸吸附（lenovo_pen_physical_docked=1）时禁用
 * NVTCapacitivePen 触控板输入并释放 120Hz 锁（允许 144Hz）；取下时恢复
 * 笔输入（120Hz 锁由 pen-bridge 原有逻辑接管）。
 *
 * 运行在 system 进程（LSPosed scope=android），反射调用
 * InputManager.setInputDeviceEnabled（隐藏 API，system 权限可用）。
 */
public final class PenTouchpadGuard implements IXposedHookLoadPackage {
    private static final String TAG = "PenTouchpadGuard";
    private static final String DOCKED_SETTING = "lenovo_pen_physical_docked";
    private static final String REFRESH_SETTING = "lenovo_pen_refresh_active";

    private static volatile Context appContext;
    private static volatile boolean lastDocked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"android".equals(p.packageName)) {
            return;
        }
        try {
            appContext = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": context failed " + t);
            return;
        }
        if (appContext == null) {
            XposedBridge.log(TAG + ": no application context, skip");
            return;
        }
        try {
            Handler h = new Handler(Looper.getMainLooper());
            appContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(DOCKED_SETTING), true,
                    new ContentObserver(h) {
                        @Override
                        public void onChange(boolean selfChange) {
                            apply();
                        }
                    });
            h.postDelayed(PenTouchpadGuard::apply, 3000L);
            XposedBridge.log(TAG + ": docked observer installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed " + t);
        }
    }

    private static void apply() {
        if (appContext == null) {
            return;
        }
        boolean docked;
        try {
            docked = Settings.Global.getInt(appContext.getContentResolver(), DOCKED_SETTING, 0) == 1;
        } catch (Throwable t) {
            docked = false;
        }
        if (docked == lastDocked) {
            return;
        }
        lastDocked = docked;
        setPenTouchpadEnabled(appContext, !docked);
        if (docked) {
            // 吸附：释放 120Hz 锁，允许刷新率升到 144Hz
            try {
                Settings.Global.putInt(appContext.getContentResolver(), REFRESH_SETTING, 0);
            } catch (Throwable t) {
                // ignore
            }
        }
        XposedBridge.log(TAG + ": docked=" + docked + " -> touchpad " + (docked ? "disabled" : "enabled")
                + ", refresh released=" + docked);
    }

    private static void setPenTouchpadEnabled(Context context, boolean enabled) {
        try {
            InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (im == null) {
                return;
            }
            List<Integer> nvt = new ArrayList<>();
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d != null && "NVTCapacitivePen".equalsIgnoreCase(d.getName())
                        && (d.getSources() & 0x4002) != 0) {
                    nvt.add(id);
                }
            }
            if (nvt.isEmpty()) {
                XposedBridge.log(TAG + ": no NVTCapacitivePen device found");
                return;
            }
            Method m2 = null;
            Method m3 = null;
            try {
                m2 = InputManager.class.getMethod("setInputDeviceEnabled", int.class, boolean.class);
            } catch (Throwable t) {
                // ignore
            }
            try {
                m3 = InputManager.class.getMethod("setInputDeviceEnabled", int.class, int.class, boolean.class);
            } catch (Throwable t) {
                // ignore
            }
            if (m2 == null && m3 == null) {
                XposedBridge.log(TAG + ": setInputDeviceEnabled API missing");
                return;
            }
            for (Integer id : nvt) {
                try {
                    if (m2 != null) {
                        m2.invoke(im, id, enabled);
                    } else {
                        m3.invoke(im, id, 0, enabled);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": device " + id + " set failed " + t);
                }
            }
            XposedBridge.log(TAG + ": NVT touchpad " + (enabled ? "enabled" : "disabled") + " devices=" + nvt);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": control failed " + t);
        }
    }
}
