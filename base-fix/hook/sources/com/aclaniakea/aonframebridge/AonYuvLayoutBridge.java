package com.aclaniakea.aonframebridge;

import android.content.Intent;
import android.media.Image;
import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Repairs the stock AON camera-buffer handoff on this port.  The real front
 * camera provides YUV_420_888 planes with hardware row/pixel strides while
 * the transplanted service expects contiguous planes.  Only that layout is
 * normalized before the original model consumes it; this class never creates
 * a face, gaze result, or framework attention callback.
 *
 * <p>The port's {@code nativeDelete(handle)} teardown is unsafe on the
 * transplanted SM8650 QNN/CDSP stack.  We therefore suppress only that native
 * destructor while allowing the stock Java release method to clear its handle
 * and lifecycle state.  This avoids the stale-Java-runtime loop without
 * changing inference input/output or extending the framework's own timeout.</p>
 */
public final class AonYuvLayoutBridge implements IXposedHookLoadPackage {
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean FRAME_DUMPED = new AtomicBoolean(false);
    private static final AtomicBoolean HOOK_REPORTED = new AtomicBoolean(false);
    private static final String TAG = "ColorOSAonYuvFix";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (DeviceGate.isSupported() && "com.aiunit.aon".equals(loadPackageParam.packageName) && "com.aiunit.aon".equals(loadPackageParam.processName)) {
            try {
                // The native runtime is attached before nativeCreate by the
                // KernelSU namespace loader.  Do not call Runtime.load* here:
                // this process has a private linker namespace and a Java-side
                // load would resolve the port's ODM copy instead of the
                // mounted Lenovo AIBoost/QNN stack.
                XposedHelpers.findAndHookMethod("com.aiunit.aon.operator.OGetY8FromYUVImage", loadPackageParam.classLoader, "transform", new Object[]{Object.class, new XC_MethodHook() { // from class: com.aclaniakea.aonframebridge.AonYuvLayoutBridge.1
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Exception {
                        if (!(methodHookParam.args[0] instanceof Image)) {
                            return;
                        }
                        Image image = (Image) methodHookParam.args[0];
                        if (image.getFormat() != 35) {
                            return;
                        }
                        Object result = methodHookParam.getResult();
                        if (HOOK_REPORTED.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + ": transform after-hook reached result=" + (result == null ? "null" : result.getClass().getName()));
                        }
                        if (result == null) {
                            return;
                        }
                        try {
                            AonYuvLayoutBridge.normalize(image, result);
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + ": frame-layout hook failed " + throwable);
                        }
                    }
                }});
                // InferenceAiboost.b() must still run: it clears the Java-side
                // native handle and marks the model released.  Only the
                // transplanted native destructor is unsafe, so guard that
                // private static native call instead of short-circuiting b().
                XposedHelpers.findAndHookMethod("com.aiunit.aon.model.InferenceAiboost", loadPackageParam.classLoader, "nativeDelete", new Object[]{Long.TYPE, new XC_MethodHook() {
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        methodHookParam.setResult(null);
                    }
                }});
                XposedHelpers.findAndHookMethod("com.aiunit.aon.AONAttentionService", loadPackageParam.classLoader, "onUnbind", new Object[]{Intent.class, new XC_MethodHook() { // from class: com.aclaniakea.aonframebridge.AonYuvLayoutBridge.2
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        AonYuvLayoutBridge.endReleasedSession();
                    }
                }});
                XposedHelpers.findAndHookMethod("com.aiunit.aon.AONAttentionService$3", loadPackageParam.classLoader, "cancelAttentionCheck", new Object[]{Class.forName("com.oplus.wrapper.service.attention.IAttentionCallback", false, loadPackageParam.classLoader), new XC_MethodHook() { // from class: com.aclaniakea.aonframebridge.AonYuvLayoutBridge.3
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        AonYuvLayoutBridge.endReleasedSession();
                    }
                }});
                XposedHelpers.findAndHookMethod("com.aiunit.aon.AONAttentionService$2", loadPackageParam.classLoader, "onEventParam", new Object[]{Integer.TYPE, Integer.TYPE, Class.forName("com.aiunit.aon.utils.core.FaceInfo", false, loadPackageParam.classLoader), new XC_MethodHook() { // from class: com.aclaniakea.aonframebridge.AonYuvLayoutBridge.4
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        if (((Integer) methodHookParam.args[1]).intValue() == 65537) {
                            AonYuvLayoutBridge.endReleasedSession();
                        }
                    }
                }});
                XposedBridge.log("ColorOSAonYuvFix: real YUV normalizer and native destructor guard installed");
            } catch (Throwable th) {
                XposedBridge.log("ColorOSAonYuvFix: installation failed");
                XposedBridge.log(th);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void endReleasedSession() {
        // The old implementation killed AON after release.  That forced a
        // cold QNN start for every request and could outlive the framework
        // window.  The stock service now remains alive and emits only real
        // model events through its untouched callback chain.
        XposedBridge.log("ColorOSAonYuvFix: attention session released; retaining real QNN graph");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void normalize(Image image, Object obj) throws Exception {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            return;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int i = (width + 1) / 2;
        int i2 = (height + 1) / 2;
        int i3 = width * height;
        int i4 = (i * 2 * i2) + i3;
        byte[] bArr = (byte[]) obj.getClass().getMethod("getData", new Class[0]).invoke(obj, new Object[0]);
        if (bArr == null || bArr.length < i4) {
            return;
        }
        boolean shouldDumpFrame = FRAME_DUMPED.compareAndSet(false, true);
        if (shouldDumpFrame) {
            dumpFrameData("aon-yuv-before.yuv", bArr);
            reportPlaneLayout(image, planes);
        }
        if (planes[0].getPixelStride() == 1 && planes[0].getRowStride() == width && planes[1].getPixelStride() == 1 && planes[1].getRowStride() == i && planes[2].getPixelStride() == 1 && planes[2].getRowStride() == i) {
            if (shouldDumpFrame) {
                dumpFrameData("aon-yuv-after.yuv", bArr);
            }
            return;
        }
        copyPlane(planes[0], width, height, bArr, 0);
        copyPlane(planes[1], i, i2, bArr, i3);
        copyPlane(planes[2], i, i2, bArr, i3 + (i * i2));
        Arrays.fill(bArr, i4, bArr.length, (byte) 0);
        if (shouldDumpFrame) {
            dumpFrameData("aon-yuv-after.yuv", bArr);
        }
        if (REPORTED.compareAndSet(false, true)) {
            XposedBridge.log("ColorOSAonYuvFix: normalized " + width + "x" + height + " Y=" + planes[0].getRowStride() + "/" + planes[0].getPixelStride() + " U=" + planes[1].getRowStride() + "/" + planes[1].getPixelStride() + " V=" + planes[2].getRowStride() + "/" + planes[2].getPixelStride());
        }
    }

    private static void copyPlane(Image.Plane plane, int i, int i2, byte[] bArr, int i3) {
        ByteBuffer byteBufferDuplicate = plane.getBuffer().duplicate();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int position = byteBufferDuplicate.position();
        int iLimit = byteBufferDuplicate.limit();
        for (int i4 = 0; i4 < i2; i4++) {
            int i5 = i4 * rowStride;
            int i6 = (i4 * i) + i3;
            for (int i7 = 0; i7 < i; i7++) {
                int i8 = position + (i7 * pixelStride) + i5;
                bArr[i6 + i7] = i8 >= position && i8 < iLimit ? byteBufferDuplicate.get(i8) : (byte) 0;
            }
        }
    }

    private static void reportPlaneLayout(Image image, Image.Plane[] planes) {
        StringBuilder sb = new StringBuilder(TAG).append(": frame probe ")
                .append(image.getWidth()).append('x').append(image.getHeight());
        for (int index = 0; index < planes.length; index++) {
            ByteBuffer buffer = planes[index].getBuffer();
            sb.append(" P").append(index).append('=')
                    .append(buffer.position()).append('/')
                    .append(buffer.limit()).append('/')
                    .append(buffer.capacity()).append('/')
                    .append(buffer.remaining()).append('/')
                    .append(planes[index].getRowStride()).append('/')
                    .append(planes[index].getPixelStride());
        }
        XposedBridge.log(sb.toString());
    }

    private static void dumpFrameData(String name, byte[] data) {
        try {
            File directory = new File("/data/user_de/0/com.aiunit.aon/files");
            if (!directory.exists() && !directory.mkdirs()) {
                XposedBridge.log(TAG + ": cannot create frame dump directory");
                return;
            }
            File file = new File(directory, name);
            FileOutputStream output = new FileOutputStream(file, false);
            try {
                output.write(data, 0, Math.min(data.length, 153600));
                output.flush();
            } finally {
                output.close();
            }
            XposedBridge.log(TAG + ": dumped " + name + " bytes=" + Math.min(data.length, 153600));
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": frame dump failed " + name + " " + throwable);
        }
    }
}
