package com.aclaniakea.faceauthoffmain;

import android.os.Handler;
import android.os.HandlerThread;

import com.aclaniakea.devicegate.DeviceGate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps the port's software face unlock off system_server's main looper.
 *
 * The ROM ships a Megvii face unlock ({@code ax.nd.faceunlock.FaceAuthBridge})
 * compiled into services.jar.  Its {@code startAuthenticate} posts the whole
 * start sequence to {@code Looper.getMainLooper()}: release the camera,
 * {@code Thread.sleep(100)}, {@code compareStart()}, build the controller and
 * start the preview.  Keyguard requests authentication the moment the screen
 * turns on, so every wake blocked system_server's main thread for 103-114ms
 * ("Slow dispatch ... FaceAuthBridge$$ExternalSyntheticLambda4") right inside
 * the unlock animation.
 *
 * The reference phone runs face unlock in a separate vendor HAL process and
 * never touches system_server's main thread.  Nothing in that start sequence
 * needs the main looper: camera calls are already serialised on the bridge's
 * own CameraServiceThread, frame comparison runs on its face_auth_thread, and
 * the controller's result handler is bound to the main looper explicitly.  So
 * the same body is run, unchanged, on a dedicated thread instead.
 *
 * Ordering: stopAuthenticate is called synchronously by FaceService.  A stop
 * that arrives before the relocated start has run bumps a generation counter
 * and the stale start is dropped, so a cancelled request can never open the
 * camera afterwards.
 *
 * Memory: the auth controller only sets a 640x480 preview through the legacy
 * Camera1 API and leaves picture-size at the sensor maximum (4160x3120).
 * cameraserver's Camera2Client creates the JPEG stream up front, so every wake
 * configured an unused 13MP BLOB stream (19.6MB per buffer, 8 HAL buffers,
 * 0 frames produced) and CamX built the full-resolution snapshot pipeline for
 * it: dmabuf rose by ~360MB per session, against ~160MB for the reference
 * phone's native face HAL.  That burst lands exactly when keyguard animates.
 * The picture size is dropped to the smallest supported size of the preview's
 * aspect ratio; preview and callback streams are untouched.
 */
public final class FaceAuthOffMainThread implements IXposedHookLoadPackage {
    private static final String TAG = "FaceAuthOffMainThread";
    private static final String BRIDGE = "ax.nd.faceunlock.FaceAuthBridge";
    private static final String AUTH_CONTROLLER = "ax.nd.faceunlock.camera.CameraFaceAuthController";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicLong GENERATION = new AtomicLong();
    private static volatile Handler worker;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName) || !"android".equals(lpp.processName)
                || !DeviceGate.isSupported()) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> bridge = XposedHelpers.findClassIfExists(BRIDGE, lpp.classLoader);
            if (bridge == null) {
                XposedBridge.log(TAG + ": " + BRIDGE + " absent, nothing to do");
                return;
            }
            final Method body = findStartBody(bridge);
            if (body == null) {
                XposedBridge.log(TAG + ": start body not found, leaving stock behaviour");
                return;
            }
            body.setAccessible(true);

            XposedHelpers.findAndHookMethod(bridge, "startAuthenticate",
                    int.class, int.class, Object.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            final Object self = param.thisObject;
                            final int sensorId = (Integer) param.args[0];
                            final int userId = (Integer) param.args[1];
                            final Object receiver = param.args[2];
                            final long generation = GENERATION.get();
                            worker().post(new Runnable() {
                                @Override
                                public void run() {
                                    if (GENERATION.get() != generation) {
                                        XposedBridge.log(TAG + ": dropped start cancelled before it ran");
                                        return;
                                    }
                                    try {
                                        body.invoke(self, receiver, sensorId, userId);
                                    } catch (Throwable error) {
                                        XposedBridge.log(TAG + ": relocated start failed " + error);
                                    }
                                }
                            });
                            param.setResult(null);
                        }
                    });

            XposedHelpers.findAndHookMethod(bridge, "stopAuthenticate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    GENERATION.incrementAndGet();
                }
            });
            XposedBridge.log(TAG + ": face auth start moved off system_server main looper ("
                    + body.getName() + ")");
            hookPictureSize(lpp.classLoader);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": install failed");
            XposedBridge.log(error);
        }
    }

    private static void hookPictureSize(ClassLoader loader) {
        Class<?> controller = XposedHelpers.findClassIfExists(AUTH_CONTROLLER, loader);
        if (controller == null) {
            XposedBridge.log(TAG + ": " + AUTH_CONTROLLER + " absent, picture size left alone");
            return;
        }
        XposedHelpers.findAndHookMethod(controller, "setupCameraParameters",
                android.hardware.Camera.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        shrinkPictureSize((android.hardware.Camera) param.args[0]);
                    }
                });
        XposedBridge.log(TAG + ": face auth picture size capped");
    }

    @SuppressWarnings("deprecation")
    private static void shrinkPictureSize(android.hardware.Camera camera) {
        if (camera == null) return;
        try {
            android.hardware.Camera.Parameters params = camera.getParameters();
            android.hardware.Camera.Size preview = params.getPreviewSize();
            android.hardware.Camera.Size current = params.getPictureSize();
            java.util.List<android.hardware.Camera.Size> sizes = params.getSupportedPictureSizes();
            if (preview == null || current == null || sizes == null || sizes.isEmpty()) return;
            double aspect = (double) preview.width / preview.height;
            android.hardware.Camera.Size sameAspect = null;
            android.hardware.Camera.Size smallest = null;
            for (android.hardware.Camera.Size size : sizes) {
                long area = (long) size.width * size.height;
                if (smallest == null || area < (long) smallest.width * smallest.height) {
                    smallest = size;
                }
                if (Math.abs((double) size.width / size.height - aspect) < 0.01
                        && (sameAspect == null
                        || area < (long) sameAspect.width * sameAspect.height)) {
                    sameAspect = size;
                }
            }
            android.hardware.Camera.Size target = sameAspect != null ? sameAspect : smallest;
            if ((long) target.width * target.height >= (long) current.width * current.height) {
                return;
            }
            params.setPictureSize(target.width, target.height);
            camera.setParameters(params);
            XposedBridge.log(TAG + ": face auth picture size " + current.width + "x"
                    + current.height + " -> " + target.width + "x" + target.height);
        } catch (Throwable error) {
            // A failed tweak must never break face unlock; stock size stays.
            XposedBridge.log(TAG + ": picture size tweak skipped " + error);
        }
    }

    /** The synthetic lambda body {@code lambda$startAuthenticate$N$...(Object, int, int)}. */
    private static Method findStartBody(Class<?> bridge) {
        for (Method method : bridge.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().startsWith("lambda$startAuthenticate$")
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == Void.TYPE
                    && types.length == 3 && types[0] == Object.class
                    && types[1] == int.class && types[2] == int.class) {
                return method;
            }
        }
        return null;
    }

    private static Handler worker() {
        Handler handler = worker;
        if (handler != null) return handler;
        synchronized (FaceAuthOffMainThread.class) {
            if (worker == null) {
                HandlerThread thread = new HandlerThread("FaceAuthStart");
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }
    }
}
