package com.aclaniakea.aoncameraopbridge;

import android.os.Handler;
import android.os.Looper;

import com.aclaniakea.devicegate.DeviceGate;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Arbitrates the port's real AON session with ordinary Camera2 clients.
 *
 * Lenovo's CamX stack does not implement the Oplus-specific closeAON device
 * path, but the framework still has the stock AON lifecycle:
 * OplusAONSmartDim -> SmartFaceGaze -> AONService.  The previous workaround
 * disabled that lifecycle and denied CAMERA AppOps to AON permanently.  That
 * made normal camera stable at the cost of removing AON entirely.
 *
 * This bridge stays at the framework ownership boundary instead.  A normal
 * camera CAMERA AppOp stops an existing SmartFaceGaze session on its original
 * worker before the client reaches CameraService.  While a normal camera is
 * open, new SmartFaceGaze starts are deferred.  Once every normal camera is
 * closed, the stock SmartDim state machine is allowed to resume only when its
 * user setting and awake state still permit it.  No AON result, frame, or
 * camera status is fabricated and no process is force-stopped.
 */
public final class AonCameraOpBridge implements IXposedHookLoadPackage {
    private static final String TAG = "AonCameraArbitration";
    private static final String AON_PACKAGE = "com.aiunit.aon";
    private static final String SMART_DIM = "com.android.server.power.OplusAONSmartDim";
    private static final int OP_CAMERA = 26;
    private static final int CAMERA_STATE_OPEN = 0;
    private static final int CAMERA_STATE_CLOSED = 3;
    private static final long RESUME_DELAY_MS = 750L;
    private static final String FRONT_CAMERA_ID = "1";
    private static final int FRONT_LED_ON = 150;
    private static final String[] FRONT_LED_PATHS = {
            "/sys/class/leds/blue/brightness",
            "/sys/class/leds/green/brightness",
            "/sys/class/leds/red/brightness"
    };

    private static final AtomicBoolean SMART_DIM_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean CAMERA_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean APPOPS_HOOKED = new AtomicBoolean(false);
    private static final AtomicReference<Object> SMART_DIM_INSTANCE = new AtomicReference<>();
    private static final AtomicBoolean PAUSED_FOR_CAMERA = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, Boolean> NON_AON_CAMERAS =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean FRONT_LED_WRITTEN = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!DeviceGate.isSupported() || !"android".equals(lpp.packageName)
                || !"android".equals(lpp.processName)) {
            return;
        }
        hookSmartDim(lpp.classLoader);
        hookCameraActivity(lpp.classLoader);
        hookCameraStartOperations(lpp.classLoader);
    }

    private static void hookSmartDim(ClassLoader loader) {
        if (!SMART_DIM_HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> target = XposedHelpers.findClass(SMART_DIM, loader);
            int readyHooks = 0;
            int startHooks = 0;
            for (Method method : target.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if ("onSystemReady".equals(method.getName()) && types.length == 0) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            rememberSmartDim(param.thisObject);
                        }
                    });
                    readyHooks++;
                } else if ("startSmartFaceGaze".equals(method.getName())
                        && method.getReturnType() == Void.TYPE
                        && types.length == 1 && types[0] == String.class) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rememberSmartDim(param.thisObject);
                            if (!NON_AON_CAMERAS.isEmpty()) {
                                PAUSED_FOR_CAMERA.set(true);
                                param.setResult(null);
                                XposedBridge.log(TAG + ": deferred AON start while camera is busy");
                            }
                        }
                    });
                    startHooks++;
                }
            }
            XposedBridge.log(TAG + ": installed SmartDim hooks ready=" + readyHooks
                    + " start=" + startHooks);
        } catch (Throwable error) {
            SMART_DIM_HOOKED.set(false);
            XposedBridge.log(TAG + ": SmartDim hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void hookCameraActivity(ClassLoader loader) {
        if (!CAMERA_HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> proxy = XposedHelpers.findClass(
                    "com.android.server.camera.CameraServiceProxy", loader);
            Class<?> stats = XposedHelpers.findClass(
                    "android.hardware.CameraSessionStats", loader);
            XC_MethodHook activityHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 1
                                || param.args[0] == null) return;
                        Object session = param.args[0];
                        Object client = XposedHelpers.callMethod(session, "getClientName");
                        if (!(client instanceof String) || AON_PACKAGE.equals(client)) return;
                        String clientName = (String) client;
                        Object stateValue = XposedHelpers.callMethod(session, "getNewCameraState");
                        if (!(stateValue instanceof Integer)) return;
                        Object idValue = XposedHelpers.callMethod(session, "getCameraId");
                        String cameraId = idValue instanceof String ? (String) idValue : (String) client;
                        int state = ((Integer) stateValue).intValue();
                        updateFrontLed(cameraId, clientName, state);
                        if (state == CAMERA_STATE_OPEN) {
                            NON_AON_CAMERAS.put(cameraId, Boolean.TRUE);
                            pauseAonForCamera("opened:" + client);
                        } else if (state == CAMERA_STATE_CLOSED) {
                            NON_AON_CAMERAS.remove(cameraId);
                            if (NON_AON_CAMERAS.isEmpty()) scheduleResume();
                        }
                    } catch (Throwable error) {
                        XposedBridge.log(TAG + ": camera activity observation failed " + error);
                    }
                }
            };
            int hooked = 0;
            for (Method method : proxy.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if ("updateActivityCount".equals(method.getName()) && types.length == 1
                        && types[0] == stats) {
                    XposedBridge.hookMethod(method, activityHook);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": installed CameraService activity hooks=" + hooked);
        } catch (Throwable error) {
            CAMERA_HOOKED.set(false);
            XposedBridge.log(TAG + ": CameraService hook installation failed");
            XposedBridge.log(error);
        }
    }

    /**
     * Lenovo's camera stack lacks ColorOS's front-camera LED bridge.  The
     * framework already receives ownership events with the client package,
     * so write LEDs directly here instead of polling dumpsys every second.
     */
    private static void updateFrontLed(String cameraId, String client, int state) {
        if (!FRONT_CAMERA_ID.equals(cameraId) || isIndicatorExcludedClient(client)) return;
        final boolean on;
        if (state == CAMERA_STATE_OPEN) on = true;
        else if (state == CAMERA_STATE_CLOSED) on = false;
        else return;
        if (FRONT_LED_WRITTEN.get() == on) return;
        try {
            byte[] value = Integer.toString(on ? FRONT_LED_ON : 0).getBytes("US-ASCII");
            for (String path : FRONT_LED_PATHS) {
                try (FileOutputStream output = new FileOutputStream(path)) {
                    output.write(value);
                }
            }
            FRONT_LED_WRITTEN.set(on);
            XposedBridge.log(TAG + ": front indicator=" + (on ? "on" : "off")
                    + " client=" + client);
        } catch (Throwable error) {
            // Cosmetic I/O must never perturb the camera ownership path.
            XposedBridge.log(TAG + ": front indicator write failed " + error);
        }
    }

    private static boolean isIndicatorExcludedClient(String client) {
        return "android".equals(client)
                || AON_PACKAGE.equals(client)
                || "com.oplus.facelock".equals(client)
                || "com.oplus.faceunlock".equals(client);
    }

    private static void hookCameraStartOperations(ClassLoader loader) {
        if (!APPOPS_HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> service = XposedHelpers.findClass("com.android.server.appop.AppOpsService", loader);
            XC_MethodHook handoff = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object[] args = param.args;
                        if (args == null || args.length < 4 || !(args[1] instanceof Integer)
                                || ((Integer) args[1]).intValue() != OP_CAMERA) return;
                        Object packageArg = args[3];
                        if (!(packageArg instanceof String) || AON_PACKAGE.equals(packageArg)) return;
                        // This is before CameraService opens the physical device.  The short
                        // worker handoff is the port-side equivalent of stock closeAON.
                        pauseAonForCamera("appop:" + packageArg);
                    } catch (Throwable error) {
                        XposedBridge.log(TAG + ": early camera handoff failed " + error);
                    }
                }
            };
            int hooked = 0;
            for (Method method : service.getDeclaredMethods()) {
                String name = method.getName();
                Class<?>[] types = method.getParameterTypes();
                if (("startOperation".equals(name) || "startOperationForDevice".equals(name))
                        && types.length >= 4 && types[1] == Integer.TYPE
                        && types[2] == Integer.TYPE && types[3] == String.class) {
                    XposedBridge.hookMethod(method, handoff);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": installed early CAMERA AppOps hooks=" + hooked);
        } catch (Throwable error) {
            APPOPS_HOOKED.set(false);
            XposedBridge.log(TAG + ": AppOps hook installation failed");
            XposedBridge.log(error);
        }
    }

    private static void rememberSmartDim(Object smartDim) {
        if (smartDim != null) SMART_DIM_INSTANCE.set(smartDim);
    }

    private static void pauseAonForCamera(String reason) {
        final Object smartDim = SMART_DIM_INSTANCE.get();
        if (smartDim == null || !isFaceGazeActive(smartDim)) return;
        if (!PAUSED_FOR_CAMERA.compareAndSet(false, true)) return;
        runOnSmartDimWorker(smartDim, new Runnable() {
            @Override
            public void run() {
                try {
                    if (isFaceGazeActive(smartDim)) {
                        XposedHelpers.callMethod(smartDim, "stopSmartFaceGaze", "cameraBusy");
                        XposedBridge.log(TAG + ": stopped active AON before " + reason);
                    }
                } catch (Throwable error) {
                    PAUSED_FOR_CAMERA.set(false);
                    XposedBridge.log(TAG + ": AON stop failed " + error);
                }
            }
        });
    }

    private static void scheduleResume() {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!NON_AON_CAMERAS.isEmpty() || !PAUSED_FOR_CAMERA.get()) return;
                    final Object smartDim = SMART_DIM_INSTANCE.get();
                    if (smartDim == null || !isSmartDimEnabledAndAwake(smartDim)) {
                        PAUSED_FOR_CAMERA.set(false);
                        return;
                    }
                    if (!PAUSED_FOR_CAMERA.compareAndSet(true, false)) return;
                    runOnSmartDimWorker(smartDim, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (NON_AON_CAMERAS.isEmpty()
                                        && isSmartDimEnabledAndAwake(smartDim)
                                        && !isFaceGazeActive(smartDim)) {
                                    XposedHelpers.callMethod(smartDim, "startSmartFaceGaze",
                                            "cameraReleased");
                                    XposedBridge.log(TAG + ": resumed AON after camera release");
                                }
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": AON resume failed " + error);
                            }
                        }
                    });
                }
            }, RESUME_DELAY_MS);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not schedule AON resume " + error);
        }
    }

    private static boolean isFaceGazeActive(Object smartDim) {
        try {
            return XposedHelpers.getBooleanField(smartDim, "mIsSmartFaceGazeOn");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSmartDimEnabledAndAwake(Object smartDim) {
        try {
            Object enabled = XposedHelpers.callMethod(smartDim, "isSmartAONEnabled");
            return Boolean.TRUE.equals(enabled)
                    && XposedHelpers.getBooleanField(smartDim, "mIsWakefulnessAwake");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void runOnSmartDimWorker(Object smartDim, Runnable work) {
        try {
            Object worker = XposedHelpers.getObjectField(smartDim, "mHandler");
            if (worker instanceof Handler) {
                Handler handler = (Handler) worker;
                if (Looper.myLooper() == handler.getLooper()) {
                    work.run();
                    return;
                }
                try {
                    Object completed = XposedHelpers.callMethod(handler, "runWithScissors", work,
                            Long.valueOf(120L));
                    if (Boolean.TRUE.equals(completed)) return;
                } catch (Throwable ignored) {
                    // Some framework builds hide runWithScissors.  Posting to the same
                    // worker remains safe; the CameraService activity hook is a backup.
                }
                handler.post(work);
                return;
            }
        } catch (Throwable ignored) {
            // AON is optional.  A missing worker must never perturb the camera caller.
        }
        work.run();
    }
}
