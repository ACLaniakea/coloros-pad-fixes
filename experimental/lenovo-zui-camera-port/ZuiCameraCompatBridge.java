package com.aclaniakea.zuicameracompat;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** ZUI 相机（联想原厂）在 ColorOS 移植上的设备身份兼容。
 *  AppFeatures 按 Build.DEVICE 匹配 res/raw/app_features 的设备配置表：
 *  ColorOS 移植后 Build.DEVICE 是 OPD2513 系，匹配不到任何条目，落到默认
 *  AOSP_Pad（无 modules-id），getSupportedModes() 返回 null，ModeRecyclerView
 *  NPE 闪退。本机实际是 TB710FU，配置表里就有 "TB710FU,TB373FU" 原厂条目
 *  （modules-id 31,1,0,24,6，YUV/AI_CAPTURE/BST/OCR/LENOVO_PEN 全功能）。
 *  这里在 generateSupportedList 读 mDevice 前把它改成 TB710FU，命中原厂配置。
 */
public final class ZuiCameraCompatBridge implements IXposedHookLoadPackage {
    private static final String TAG = "ZuiCameraCompat";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.zui.camera".equals(lpparam.packageName)) {
            return;
        }
        try {
            Class<?> appFeatures = XposedHelpers.findClass("com.zui.camera.app.AppFeatures", lpparam.classLoader);
            Class<?> cameraDeviceInfo = XposedHelpers.findClass(
                    "com.zui.camera.app.CameraDeviceInfo", lpparam.classLoader);
            /*
             * This APK is the LGSI build.  In that build CameraDeviceInfo does
             * not consult mDevice at all: it always selects devices[0].  The
             * stock JSON already contains the exact TB710FU entry at a later
             * index, so promote only that entry in the in-memory JSON.  This
             * keeps the vendor's original 0/2 rear and 1 front mapping instead
             * of fabricating camera IDs or changing Android's global Build.
             */
            XposedHelpers.findAndHookMethod(cameraDeviceInfo, "getConfig",
                    Context.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object value = param.getResult();
                            if (!(value instanceof org.json.JSONObject)) return;
                            org.json.JSONObject root = (org.json.JSONObject) value;
                            org.json.JSONArray devices = root.optJSONArray("devices");
                            if (devices == null || devices.length() == 0) return;
                            int target = -1;
                            for (int i = 0; i < devices.length(); i++) {
                                org.json.JSONObject item = devices.optJSONObject(i);
                                if (item != null && item.optString("device").contains("TB710FU")) {
                                    target = i;
                                    break;
                                }
                            }
                            if (target <= 0) return;
                            org.json.JSONArray reordered = new org.json.JSONArray();
                            reordered.put(devices.get(target));
                            for (int i = 0; i < devices.length(); i++) {
                                if (i != target) reordered.put(devices.get(i));
                            }
                            root.put("devices", reordered);
                            XposedBridge.log(TAG + ": promoted TB710FU camera config from index=" + target);
                        }
                    });
            // generateSupportedList 是私有方法，before 阶段改写 mDevice。
            XposedHelpers.findAndHookMethod(appFeatures, "generateSupportedList", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedHelpers.setObjectField(param.thisObject, "mDevice", "TB710FU");
                        }
                    });
            // CameraDeviceInfo 按 mDevice 匹配相机 id 配置（camera_info/capture_info）。
            // OP6547L1 匹配不到 -> camera id 映射错 -> Morpho ImageRefiner/降噪库
            // 按错误 camera id 加载 XML -> 间接调用跳错 -> SIGILL（拍照/微距/人像
            // 追踪/自动闪光灯闪退）。改成 TB710FU 命中原厂配置。
            XposedHelpers.findAndHookMethod(cameraDeviceInfo,
                    "generateCameraIdConfig", Context.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedHelpers.setObjectField(param.thisObject, "mDevice", "TB710FU");
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + ": camera IDs backMain="
                                    + XposedHelpers.getObjectField(param.thisObject, "mBackMainId")
                                    + " backWide="
                                    + XposedHelpers.getObjectField(param.thisObject, "mBackWideId")
                                    + " frontMain="
                                    + XposedHelpers.getObjectField(param.thisObject, "mFrontMainId"));
                        }
                    });
            // AlgoUtils 按 Build.MODEL 校验"编译设备"（cur_device 需包含在型号里，
            // 如 TB132）。ColorOS 移植后 MODEL=OPD2513 不匹配，算法库走错误路径
            // （微距/人像追踪 SIGILL）。hook getBuildModel 返回联想型号 TB132。
            XposedHelpers.findAndHookMethod("com.zui.camera.lcaf.common.AlgoUtils", lpparam.classLoader,
                    "getBuildModel", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult("TB132");
                        }
                    });
            // 调试：打印 NoiseReduction3.do_init 的 XML 路径，定位拍照 SIGILL 根因
            try {
                XposedHelpers.findAndHookMethod("com.zui.camera.lcaf.denoise.NoiseReduction3", lpparam.classLoader,
                        "do_init", int.class, int.class, int.class, int.class, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log(TAG + ": do_init(" + param.args[0] + "," + param.args[1]
                                        + "," + param.args[2] + "," + param.args[3] + ") xml=" + param.args[4]);
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": do_init hook failed: " + t);
            }
            // Morpho native 在 ColorOS 上 SIGILL（ImageRefiner/auto_framing 初始化
            // 跳错）。跳过 native init 让拍照/模式切换可用（无降噪/人像追踪效果）。
            XposedHelpers.findAndHookMethod("com.zui.camera.lcaf.denoise.NoiseReduction3", lpparam.classLoader,
                    "do_init", int.class, int.class, int.class, int.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + ": skipping NoiseReduction3.do_init xml=" + param.args[4]);
                            param.setResult(0);
                        }
                    });
            // The shipped ZUI APK has changed this native method's parameter
            // list across builds. Hook every actual overload rather than
            // assuming a no-argument form; a missing optional autofocus hook
            // must never abort installation of the camera-id and capture hooks.
            Class<?> autoFraming = XposedHelpers.findClass(
                    "com.zui.camera.lcaf.autoframing.MorphoAutoFramingJNI", lpparam.classLoader);
            XposedBridge.hookAllMethods(autoFraming, "init", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log(TAG + ": skipping MorphoAutoFramingJNI.init args=" + param.args.length);
                    param.setResult(0);
                }
            });
            // 兜底：即使配置解析失败，getSupportedModes() 也不要返回 null（TB710FU 原厂 modes）。
            XposedHelpers.findAndHookMethod(appFeatures, "getSupportedModes",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.getResult() == null) {
                                param.setResult(new int[] {31, 1, 0, 24, 6});
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked AppFeatures device -> TB710FU");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed");
            XposedBridge.log(t);
        }
    }

}
