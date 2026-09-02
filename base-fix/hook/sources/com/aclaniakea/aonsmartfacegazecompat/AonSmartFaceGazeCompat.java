package com.aclaniakea.aonsmartfacegazecompat;

import com.aclaniakea.devicegate.DeviceGate;

import java.util.Map;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.ByteBuffer;

import android.media.Image;
import android.content.Context;
import android.os.Handler;
import android.provider.Settings;

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
    private static final int ATTENTION_GAZE = 0x60001;
    private static final int LOW_RATE_GESTURE = 0x60005;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean RESULT_DELIVERED = new AtomicBoolean(true);
    private static final AtomicInteger NEGATIVE_SAMPLES = new AtomicInteger(0);
    private static final AtomicLong REQUEST_STARTED_MS = new AtomicLong(0L);
    private static final ThreadLocal<Boolean> TERMINAL_CALLBACK = new ThreadLocal<>();
    private static final int MIN_NEGATIVE_SAMPLES = 12;
    private static final long MIN_NEGATIVE_WINDOW_MS = 1500L;
    private static final long MAX_NEGATIVE_WINDOW_MS = 2500L;
    private static final AtomicInteger FRAME_PROBES = new AtomicInteger(0);
    private static final AtomicBoolean SCHEDULER_REPORTED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!DeviceGate.isSupported()) {
            return;
        }
        if ("android".equals(lpp.packageName) && "android".equals(lpp.processName)) {
            // Keep the framework's original 0x60007 SmartDim lifecycle.  The
            // native AON side below supplies only its missing profile entry.
            installSmartDimScheduler(lpp.classLoader);
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

    private static void installNativeGazeProbe(ClassLoader loader) {
        try {
            Class<?> processor = XposedHelpers.findClass("p.a", loader);
            int hooked = 0;
            for (Method method : processor.getDeclaredMethods()) {
                String name = method.getName();
                if (!("G".equals(name) || "H".equals(name)
                        || "I".equals(name) || "J".equals(name))) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        StringBuilder message = new StringBuilder(TAG)
                                .append(": native gaze ").append(method.getName());
                        if (param.args != null && param.args.length > 0) {
                            Object first = param.args[0];
                            message.append(" arg0=").append(first);
                            if (first instanceof java.util.Collection) {
                                message.append(" size=")
                                        .append(((java.util.Collection<?>) first).size());
                            }
                        }
                        XposedBridge.log(message.toString());
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + ": installed native gaze probes=" + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": native gaze probes were not installed " + error);
        }
    }

    /**
     * Android's generic AttentionDetector and ColorOS SmartDim are normally
     * backed by different AON commands.  This transplanted AON APK lacks the
     * SmartDim command, so the framework bridge below reuses 0x60001.  Letting
     * the generic AttentionService start the same command first makes the
     * later SmartDim start return ALREADY_STARTED and mixes both lifecycles.
     * Fail only the generic check so Android falls back to its normal timeout;
     * ColorOS SmartDim remains the sole owner of the real camera/model path.
     */
    private static void installStandardAttentionFallback(ClassLoader loader) throws Throwable {
        Class<?> service = XposedHelpers.findClass(
                "com.aiunit.aon.AONAttentionService$3", loader);
        int hooked = 0;
        for (Method method : service.getDeclaredMethods()) {
            if (!"checkAttention".equals(method.getName())) continue;
            final Class<?> returnType = method.getReturnType();
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        for (Object arg : param.args) {
                            if (arg == null) continue;
                            for (Method callback : arg.getClass().getMethods()) {
                                Class<?>[] types = callback.getParameterTypes();
                                if ("onFailure".equals(callback.getName())
                                        && types.length == 1 && types[0] == Integer.TYPE) {
                                    callback.invoke(arg, Integer.valueOf(2));
                                    break;
                                }
                            }
                        }
                    } catch (Throwable error) {
                        XposedBridge.log(TAG + ": generic Attention fallback callback failed "
                                + error);
                    }
                    if (returnType == Boolean.TYPE) {
                        param.setResult(Boolean.FALSE);
                    } else if (returnType == Void.TYPE) {
                        param.setResult(null);
                    }
                }
            });
            hooked++;
        }
        XposedBridge.log(TAG + ": isolated generic AttentionService hooks=" + hooked);
    }

    private static void installFrameProbe(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.aiunit.aon.operator.OGetY8FromYUVImage", loader,
                    "transform", Object.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof Image)) return;
                            int index = FRAME_PROBES.incrementAndGet();
                            if (index > 24) return;
                            try {
                                Image image = (Image) param.args[0];
                                Image.Plane plane = image.getPlanes()[0];
                                ByteBuffer data = plane.getBuffer().duplicate();
                                int width = image.getWidth();
                                int height = image.getHeight();
                                int rowStride = plane.getRowStride();
                                int pixelStride = plane.getPixelStride();
                                int base = data.position();
                                int limit = data.limit();
                                long sum = 0L;
                                int samples = 0;
                                int nonzero = 0;
                                for (int y = 0; y < height; y += 4) {
                                    for (int x = 0; x < width; x += 4) {
                                        int offset = base + y * rowStride + x * pixelStride;
                                        if (offset < base || offset >= limit) continue;
                                        int value = data.get(offset) & 0xff;
                                        sum += value;
                                        if (value != 0) nonzero++;
                                        samples++;
                                    }
                                }
                                XposedBridge.log(TAG + ": Y probe #" + index + " "
                                        + width + "x" + height + " stride=" + rowStride
                                        + "/" + pixelStride + " samples=" + samples
                                        + " nonzero=" + nonzero + " mean="
                                        + (samples == 0 ? -1 : sum / samples));
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": Y probe failed " + error);
                            }
                        }

                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            int index = FRAME_PROBES.get();
                            if (index <= 0 || index > 24 || param.getResult() == null) return;
                            try {
                                Object raw = XposedHelpers.callMethod(param.getResult(), "getData");
                                if (!(raw instanceof byte[])) return;
                                byte[] data = (byte[]) raw;
                                int limit = Math.min(320 * 240, data.length);
                                long sum = 0L;
                                int samples = 0;
                                int nonzero = 0;
                                for (int offset = 0; offset < limit; offset += 16) {
                                    int value = data[offset] & 0xff;
                                    sum += value;
                                    if (value != 0) nonzero++;
                                    samples++;
                                }
                                XposedBridge.log(TAG + ": Frame probe #" + index
                                        + " bytes=" + data.length + " Ysamples=" + samples
                                        + " nonzero=" + nonzero + " mean="
                                        + (samples == 0 ? -1 : sum / samples));
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": Frame probe failed " + error);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed read-only Y-plane probe");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": Y-plane probe was not installed " + error);
        }
    }

    /**
     * The transplanted AON APK advertises registration success for 0x60007 but
     * drops its start request before an engine is created.  Its native 0x60001
     * gaze pipeline uses the same real camera, FusionWithEye model and
     * FaceInfo callback expected by SmartFaceGaze.  Translate the four Binder
     * operations at the framework wrapper boundary so the stock SmartDim state
     * machine, orientation filtering and screen-on policy remain untouched.
     */
    private static void installFrameworkCommandBridge(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> target = XposedHelpers.findClass(
                    "com.android.server.power.aon.AON.SmartFaceGaze", loader);
            installSmartDimScheduler(loader);
            int hooked = 0;
            for (Method method : target.getDeclaredMethods()) {
                String name = method.getName();
                if ("registerSmartGaze".equals(name) && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object callback = param.args[0];
                            if (callback == null) {
                                param.setResult(Integer.valueOf(0x2001));
                                return;
                            }
                            Object service = XposedHelpers.getObjectField(param.thisObject, "service");
                            if (service == null) {
                                param.setResult(Integer.valueOf(0x1003));
                                return;
                            }
                            try {
                                XposedHelpers.setObjectField(param.thisObject,
                                        "mAONEventCallback", callback);
                                Object listener = XposedHelpers.getObjectField(
                                        param.thisObject, "aonlistener");
                                Object result = XposedHelpers.callMethod(service,
                                        "registerListener", listener,
                                        Integer.valueOf(ATTENTION_GAZE));
                                param.setResult(result);
                                XposedBridge.log(TAG + ": registered stock SmartFaceGaze listener"
                                        + " through 0x60001 result=" + result);
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": 0x60001 registration failed " + error);
                                param.setResult(Integer.valueOf(0x1004));
                            }
                        }
                    });
                    hooked++;
                } else if (("start".equals(name) || "stop".equals(name)
                        || "unRegisterSmartGaze".equals(name))
                        && method.getParameterTypes().length == 0) {
                    final String operation = name;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object service = XposedHelpers.getObjectField(param.thisObject, "service");
                            if (service == null) {
                                param.setResult(Integer.valueOf(0x1003));
                                return;
                            }
                            try {
                                if ("start".equals(operation)) {
                                    // 0x60001 is a one-shot Attention request. Its stock
                                    // Android client stops the request after the first
                                    // result, while 0x60007 owns that lifecycle inside AON.
                                    // Reset the one-shot gate before handing start to AON.
                                    RESULT_DELIVERED.set(false);
                                    NEGATIVE_SAMPLES.set(0);
                                    REQUEST_STARTED_MS.set(android.os.SystemClock.elapsedRealtime());
                                } else {
                                    RESULT_DELIVERED.set(true);
                                }
                                Object result;
                                if ("unRegisterSmartGaze".equals(operation)) {
                                    Object listener = XposedHelpers.getObjectField(
                                            param.thisObject, "aonlistener");
                                    result = XposedHelpers.callMethod(service,
                                            "unRegisterListener", listener,
                                            Integer.valueOf(ATTENTION_GAZE));
                                } else {
                                    result = XposedHelpers.callMethod(service, operation,
                                            Integer.valueOf(ATTENTION_GAZE));
                                }
                                param.setResult(result);
                                XposedBridge.log(TAG + ": " + operation
                                        + " mapped to 0x60001 result=" + result);
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": 0x60001 " + operation
                                        + " failed " + error);
                                param.setResult(Integer.valueOf(0x1004));
                            }
                        }
                    });
                    hooked++;
                }
            }

            /*
             * Complete the lifecycle semantic missing from the command-number
             * translation above.  Without this, 0x60001 emits gaze results at
             * the camera frame rate forever. OplusAONSmartDim consumes every
             * callback but only expects one terminal result per start, leaving
             * the front camera occupied after the screen has already dimmed.
             *
             * Keep the first real AON result untouched, suppress late Binder
             * callbacks, then release exactly this request from the service's
             * reference-counted request array. Other 0x60001 users are not
             * stopped because AONServiceBinder.stop() decrements one request.
             */
            Class<?> listener = XposedHelpers.findClass(
                    "com.android.server.power.aon.AON.SmartFaceGaze$1", loader);
            int resultHooks = 0;
            for (Method method : listener.getDeclaredMethods()) {
                String name = method.getName();
                Class<?>[] types = method.getParameterTypes();
                if (!("onEvent".equals(name) || "onEventParam".equals(name))
                        || types.length < 2 || types[0] != Integer.TYPE
                        || types[1] != Integer.TYPE) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        TERMINAL_CALLBACK.set(Boolean.FALSE);
                        int event = ((Integer) param.args[1]).intValue();
                        if (event != 0x10001 && event != 0x10002) return;
                        if (RESULT_DELIVERED.get()) {
                            param.setResult(null);
                            return;
                        }
                        if (event == 0x10002) {
                            int samples = NEGATIVE_SAMPLES.incrementAndGet();
                            long age = android.os.SystemClock.elapsedRealtime()
                                    - REQUEST_STARTED_MS.get();
                            // Lenovo CamX needs a short AE/pipeline warm-up. The
                            // transplanted 0x60001 engine reports its first dark
                            // frame as a terminal negative, unlike the source
                            // device's 0x60007 aggregator. Keep consuming real
                            // model results for a bounded window so a positive
                            // gaze can win; never hold the camera indefinitely.
                            if (age < MAX_NEGATIVE_WINDOW_MS) {
                                param.setResult(null);
                                return;
                            }
                        }
                        if (!RESULT_DELIVERED.compareAndSet(false, true)) {
                            param.setResult(null);
                            return;
                        }
                        TERMINAL_CALLBACK.set(Boolean.TRUE);
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        boolean terminal = Boolean.TRUE.equals(TERMINAL_CALLBACK.get());
                        TERMINAL_CALLBACK.remove();
                        if (!terminal) {
                            return;
                        }
                        try {
                            Object owner = XposedHelpers.getObjectField(param.thisObject, "this$0");
                            Object service = XposedHelpers.getObjectField(owner, "service");
                            Object result = XposedHelpers.callMethod(service, "stop",
                                    Integer.valueOf(ATTENTION_GAZE));
                            XposedBridge.log(TAG + ": terminal gaze result=0x"
                                    + Integer.toHexString(((Integer) param.args[1]).intValue())
                                    + " negativeSamples=" + NEGATIVE_SAMPLES.get()
                                    + " ageMs=" + (android.os.SystemClock.elapsedRealtime()
                                    - REQUEST_STARTED_MS.get())
                                    + ", released 0x60001 result=" + result);
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + ": terminal-result release failed " + error);
                        }
                    }
                });
                resultHooks++;
            }
            XposedBridge.log(TAG + ": installed one-shot result hooks=" + resultHooks);
            XposedBridge.log(TAG + ": installed framework command bridge hooks=" + hooked);
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + ": framework command bridge was not installed");
            XposedBridge.log(error);
        }
    }

    /**
     * The source framework schedules MSG_AUTO_DIM_DETECT at a fixed 60 s
     * after every user activity.  On this port the default screen timeout is
     * also 60 s, so the request is commonly delivered after the display has
     * already gone to sleep.  Keep the stock message and handler path, but
     * place it shortly before the user's actual screen-off deadline.
     */
    private static void installSmartDimScheduler(ClassLoader loader) {
        try {
            Class<?> smartDim = XposedHelpers.findClass(
                    "com.android.server.power.OplusAONSmartDim", loader);
            int hooked = 0;
            for (Method method : smartDim.getDeclaredMethods()) {
                if (!"handleUserActivity".equals(method.getName())
                        || method.getParameterTypes().length != 0) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context context = (Context) XposedHelpers.getObjectField(
                                    param.thisObject, "mContext");
                            Handler handler = (Handler) XposedHelpers.getObjectField(
                                    param.thisObject, "mHandler");
                            long timeout = Settings.System.getLong(
                                    context.getContentResolver(),
                                    Settings.System.SCREEN_OFF_TIMEOUT, 60_000L);
                            // Stock uses a one-minute decision point: for long
                            // timeouts a negative result may enter dim early,
                            // while a positive result keeps the configured
                            // timeout. At 60 s or below that point collides with
                            // screen-off, so run at the platform dim boundary.
                            long dimDuration = Math.min(7_000L,
                                    Math.max(1_000L, Math.round(timeout * 0.2d)));
                            long delay = timeout > 60_000L
                                    ? 60_000L
                                    : Math.max(5_000L, timeout - dimDuration);
                            handler.removeMessages(0x3ed);
                            handler.sendEmptyMessageDelayed(0x3ed, delay);
                            if (SCHEDULER_REPORTED.compareAndSet(false, true)) {
                                XposedBridge.log(TAG
                                        + ": timeout-aware auto-dim schedule=" + delay
                                        + "ms timeout=" + timeout + "ms dim="
                                        + dimDuration + "ms");
                            }
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + ": auto-dim scheduler failed " + error);
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + ": installed timeout-aware SmartDim scheduler hooks="
                    + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": timeout-aware SmartDim scheduler was not installed "
                    + error);
        }
    }
}
