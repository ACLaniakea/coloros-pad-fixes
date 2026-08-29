package com.aclaniakea.colorosvoicewakeupbridge;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * XiaoBu wakeword bridge.
 *
 * 后端优先级：原厂 DSP 一阶段（SoundTrigger/SVA，OVoice 内部的 c5.e 会话）优先，
 * BWV（AudioRecord + CPU 实时推理）作为降级路径。这与 ColorOS 原逻辑一致——
 * BWV 本来就是 DSP 的 fallback，而不是替代品。
 *
 * 早期移植版本在这里硬返回 -1003 把 DSP 一阶段整条拦掉、只留 BWV，理由是
 * "DSP 不可用"。那个结论是在音频/SoundTrigger HAL 还没修好的时候下的：当时
 * listModules 返回空，c5.e 打的是 "attachModule: no available modules"。HAL
 * 修好之后没人回头重测。现在 STHAL Module 0（QTI，version 259，concurrentCapture）
 * 已注册、qsap_voiceui 握手正常、/my_product/etc/OVMS_1st_wakeup.bin 模型在位，
 * 所以改成让原厂先试 DSP。
 *
 * DSP 是否真的起来了，判据取自框架层 android.hardware.soundtrigger.SoundTriggerModule
 * 的 startRecognition 返回码（0 = STATUS_OK），不去读 OVoice 混淆后的内部状态——
 * 混淆名会随版本变，框架 API 不会。
 *
 * 落地 {@code /data/local/tmp/ovoice_force_bwv} 可强制走 BWV，用于免重编 APK 的对照实验。
 */
public final class ColorOSVoiceWakeupBridge implements IXposedHookLoadPackage {
    private static final String WAKEUP_SERVICE = "com.oplus.ovoicemanager.wakeup";
    private static final String SPEECH_ASSIST = "com.heytap.speechassist";
    private static final String VOICE_WAKEUP_FEATURE = "oplus.software.audio.voice_wakeup_support";
    private static final String XIAOBU_FEATURE = "oplus.software.audio.voice_wakeup_xbxb_support";
    private static final long START_DELAY_MS = 500L;
    private static final long RETRY_DELAY_MS = 40L;
    private static final long LISTEN_WINDOW_MS = 3500L;
    private static final long POST_WAKE_REARM_MS = 2500L;
    /**
     * PCM 断流多久算管线真的死了。**只是兜底**——正常的重开由 no-word 结果即时触发，
     * 这个阈值不能拿来当主循环，否则就是"听一段、聋一段"（见 hookSecondStage 的注释）。
     */
    private static final long BWV_STALL_MS = 5000L;
    private static final long BWV_WATCHDOG_PERIOD_MS = 500L;
    /** 只有这个窗口内真的出过唤醒候选，才允许 OVMS 去踩 OSense 的 VOICE_WAKEUP 场景。 */
    private static final long OSENSE_SCENE_WINDOW_MS = 3000L;
    /** ColorOS 的"语音唤醒"总开关。关掉时不再拉起 BWV，管线自然收敛到 0 占用。 */
    private static final String WAKEUP_SWITCH_KEY = "voice_to_wakeup";
    /** 用户不使用唤醒时，入口默认隐藏；手动设为 1 才重新显示。 */
    private static final String WAKEUP_ENTRY_KEY = "aclaniakea_xiaobu_wakeup_entry";
    /**
     * BWV 单次监听窗口。0 = 不改，用原厂值（本机 2600ms）。
     *
     * 曾经抬到 6000ms 想减少"窗口边界切断唤醒词"，但**整窗完整模型是在窗口结束时才出结果**，
     * 窗口拉长等于把唤醒延迟一起拉长——多设备协同里平板本来就比走 DSP 的手机慢半拍，
     * 再加 6 秒窗口只会更糟。所以默认不动，需要时用
     *   settings put global aclaniakea_ovoice_window <毫秒>
     */
    private static final String DETECTION_WINDOW_KEY = "aclaniakea_ovoice_window";
    private static final String DSP_OPT_IN_KEY = "aclaniakea_ovoice_dsp";
    private static final String STREAM_MODE_KEY = "aclaniakea_ovoice_stream";
    private static final String TWO_STAGE_KEY = "aclaniakea_ovoice_twostage";
    private static final String VPR_KEY = "aclaniakea_ovoice_vpr";
    private static final String VAD_KEY = "aclaniakea_ovoice_vad";
    /** 给原厂 DSP 一阶段留的上机窗口：attachModule + loadSoundModel + startRecognition 全程。 */
    private static final long DSP_PROBE_MS = 9000L;
    private static final float WAKEWORD_THRESHOLD_SCALE = 0.9f;
    private static final float WAKEWORD_THRESHOLD_FLOOR = 0.03f;
    private static final float STREAM_THRESHOLD_RESCALE_GUARD = 0.15f;
    private static final float BWV_PCM_GAIN = 1.0f;
    private static final float BWV_SOFT_LIMIT_START = 16384.0f;
    private static final float BWV_SOFT_LIMIT_RATIO = 0.5f;
    private static final AtomicBoolean CAPABILITY_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean SPEECH_ENTRY_REPORTED = new AtomicBoolean(false);
    private static final AtomicBoolean BWV_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_STAGE_RESULT = new AtomicBoolean(false);
    private static final AtomicBoolean SECOND_STAGE_RESULT = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_STAGE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean AUDIO_ROUTE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean AUDIO_LEVEL_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean SENSITIVITY_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean BOOT_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean BOOT_REQUESTED = new AtomicBoolean(false);
    private static final AtomicBoolean DSP_OBSERVER_HOOKED = new AtomicBoolean(false);
    /** startRecognition 回过 STATUS_OK，说明 DSP 已在监听，不需要 BWV。 */
    private static final AtomicBoolean DSP_OK = new AtomicBoolean(false);
    private static final AtomicBoolean BWV_FALLBACK_ARMED = new AtomicBoolean(false);
    private static final AtomicInteger START_REQUESTS = new AtomicInteger(0);
    private static final AtomicInteger RETRY_GENERATION = new AtomicInteger(0);
    private static final AtomicInteger REARM_GENERATION = new AtomicInteger(0);
    private static final AtomicInteger PCM_CHUNKS = new AtomicInteger(0);
    private static final AtomicBoolean WATCHDOG_RUNNING = new AtomicBoolean(false);
    private static final AtomicInteger NO_WORD_RESULTS = new AtomicInteger(0);
    private static volatile long LAST_PCM_MS = 0L;
    private static volatile long LAST_WAKE_EVENT_MS = 0L;
    private static final AtomicBoolean OSENSE_HOOKED = new AtomicBoolean(false);
    private static final AtomicInteger OSENSE_SKIPS = new AtomicInteger(0);
    private static final AtomicBoolean DETECTION_TIMEOUT_SET = new AtomicBoolean(false);
    private static final AtomicInteger DETECTION_PATCHES = new AtomicInteger(0);
    private static final AtomicBoolean DSP_ENUM_BLOCKED = new AtomicBoolean(false);
    private static final AtomicInteger DSP_ENUM_BLOCKS = new AtomicInteger(0);
    private static volatile Boolean FORCE_BWV;
    private static volatile Boolean STREAM_MODE;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!DeviceGate.isSupported() || p.processName == null) return;
        if ("android".equals(p.packageName) && "android".equals(p.processName)) hookSystemServer(p.classLoader);
        else if (SPEECH_ASSIST.equals(p.packageName) && p.processName.startsWith(SPEECH_ASSIST)) hookSpeechAssist(p);
        else if (WAKEUP_SERVICE.equals(p.packageName) && p.processName.startsWith(WAKEUP_SERVICE)) hookWakeupService(p);
        else if ("com.oplus.gesture".equals(p.packageName) && p.processName.startsWith("com.oplus.gesture")) hookGestureStorage(p.classLoader);
    }

    private static void hookGestureStorage(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.gesture.util.GestureUtil", loader, "getStorageContext", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    try {
                        Object arg = hook.args == null || hook.args.length == 0 ? null : hook.args[0];
                        if (arg instanceof Context) {
                            hook.setResult(((Context) arg).createDeviceProtectedStorageContext());
                        }
                    } catch (Throwable t) { XposedBridge.log(t); }
                }
            });
            XposedBridge.log("ColorOSVoiceWakeupBridge: gesture storage context pinned to DE storage");
        } catch (Throwable t) { XposedBridge.log(t); }
    }

    private static void hookSystemServer(ClassLoader loader) {
        if (!BOOT_HOOKED.compareAndSet(false, true)) return;
        try {
            XC_MethodHook boot = new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam p) {
                    if (!BOOT_REQUESTED.compareAndSet(false, true)) return;
                    try {
                        final Object context = XposedHelpers.getObjectField(p.thisObject, "mSystemContext");
                        if (!(context instanceof Context)) return;
                        final Handler h = new Handler(Looper.getMainLooper());
                        h.postDelayed(new Runnable() { int attempt;
                            @Override public void run() {
                                attempt++;
                                if (!wakeupEnabled()) {
                                    XposedBridge.log("ColorOSVoiceWakeupBridge: wakeup switch off; skipped boot bind");
                                    return;
                                }
                                try {
                                    Intent i = new Intent("com.oplus.exsystem.bind");
                                    i.setComponent(new ComponentName(WAKEUP_SERVICE, "com.oplus.ovoicemanager.wakeup.service.OplusAppServicesManagerClient"));
                                    XposedBridge.log("ColorOSVoiceWakeupBridge: requested OVoice BWV boot bind attempt=" + attempt + " result=" + XposedHelpers.callMethod(context, "startService", i));
                                } catch (Throwable t) { XposedBridge.log(t); }
                                if (attempt < 5) h.postDelayed(this, 4000L);
                            }
                        }, 6000L);
                    } catch (Throwable t) { XposedBridge.log(t); }
                }
            };
            Method hookMethod = XposedBridge.class.getMethod("hookMethod", Member.class, XC_MethodHook.class);
            int count = 0;
            for (Method m : XposedHelpers.findClass("com.android.server.SystemServer", loader).getDeclaredMethods()) {
                if ("startOtherServices".equals(m.getName())) { hookMethod.invoke(null, m, boot); count++; }
            }
            if (count == 0) throw new NoSuchMethodException("SystemServer.startOtherServices");
            XposedBridge.log("ColorOSVoiceWakeupBridge: BWV boot bridge installed overloads=" + count);
        } catch (Throwable t) { BOOT_HOOKED.set(false); XposedBridge.log(t); }
    }

    private static void hookSpeechAssist(XC_LoadPackage.LoadPackageParam p) {
        try {
            XposedHelpers.findAndHookMethod("com.heytap.speechassist.utils.FeatureOption", p.classLoader, "G1", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam p) {
                    if (wakeupEntryEnabled() && wakeupEnabled() && hasWakeupService()) {
                        p.setResult(Boolean.TRUE);
                        if (SPEECH_ENTRY_REPORTED.compareAndSet(false, true)) XposedBridge.log("ColorOSVoiceWakeupBridge: exposed XiaoBu wake entry");
                    } else {
                        p.setResult(Boolean.FALSE);
                    }
                }
            });
        } catch (Throwable t) { XposedBridge.log(t); }
    }

    private static void hookWakeupService(final XC_LoadPackage.LoadPackageParam p) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.content.OplusFeatureConfigManager", p.classLoader, "hasFeature", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    Object feature = hook.args == null || hook.args.length == 0 ? null : hook.args[0];
                    if (XIAOBU_FEATURE.equals(feature) || VOICE_WAKEUP_FEATURE.equals(feature)) {
                        boolean enabled = wakeupEnabled();
                        hook.setResult(Boolean.valueOf(enabled));
                        if (enabled && CAPABILITY_REPORTED.compareAndSet(false, true)) XposedBridge.log("ColorOSVoiceWakeupBridge: enabled OVoice capability");
                    }
                }
            });
            hookDspObserver(p.classLoader);
            blockDspEnumeration(p.classLoader);
            hookOsenseScene(p.classLoader);
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "s", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    if (!forceBwv()) return;   // 让原厂 DSP 一阶段自己跑
                    hook.setResult(-1003);
                    int calls = START_REQUESTS.incrementAndGet();
                    if (calls <= 3 || calls % 20 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: blocked first-stage request (BWV forced) call=" + calls);
                    startBwv(hook.thisObject, p.classLoader, "legacy first-stage request");
                }
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    if (forceBwv()) return;
                    int calls = START_REQUESTS.incrementAndGet();
                    if (calls <= 3 || calls % 20 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: stock DSP first-stage session request call=" + calls + " returned " + hook.getResult());
                    armBwvFallback(hook.thisObject, p.classLoader, "first-stage session request");
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "onServiceDied", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    if (forceBwv()) {
                        // BWV 模式下原厂恢复流程会去重连已被拦掉的一阶段后端，拦住它。
                        startBwv(hook.thisObject, p.classLoader, "service recovery");
                        hook.setResult(null);
                        return;
                    }
                    DSP_OK.set(false);
                    XposedBridge.log("ColorOSVoiceWakeupBridge: OVoice backend died; letting stock recovery re-attach DSP");
                }
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    if (!forceBwv()) armBwvFallback(hook.thisObject, p.classLoader, "service recovery");
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "onResourcesAvailable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    if (forceBwv()) startBwv(hook.thisObject, p.classLoader, "resources available");
                    else armBwvFallback(hook.thisObject, p.classLoader, "resources available");
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(final XC_MethodHook.MethodHookParam hook) {
                    raiseDetectionTimeout(p.classLoader);
                    FIRST_STAGE_RESULT.set(false); SECOND_STAGE_RESULT.set(false); BWV_STARTED.set(false); PCM_CHUNKS.set(0); RETRY_GENERATION.incrementAndGet();
                    DSP_OK.set(false);
                    if (forceBwv()) {
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() { startBwv(hook.thisObject, p.classLoader, "service startup"); } }, START_DELAY_MS);
                        XposedBridge.log("ColorOSVoiceWakeupBridge: BWV-only backend; DSP first stage bypassed");
                    } else {
                        // 兜底：即使 s() 一次都没被调到，也会在窗口结束时降级。
                        armBwvFallback(hook.thisObject, p.classLoader, "service startup");
                        XposedBridge.log("ColorOSVoiceWakeupBridge: DSP-first active; BWV armed as fallback in " + DSP_PROBE_MS + "ms");
                    }
                }
            });
            hookFirstStageMarker(p.classLoader);
            hookSecondStage(p);
            hookBwvAudio(p);
            hookWakeupSensitivity(p.classLoader);
            XposedBridge.log("ColorOSVoiceWakeupBridge: OVoice hooks installed (backend=" + (forceBwv() ? "BWV" : "DSP-first") + ")");
        } catch (Throwable t) { XposedBridge.log("ColorOSVoiceWakeupBridge: OVoice hook installation failed"); XposedBridge.log(t); }
    }

    /** 落地强制降级标记 → 免重编 APK 就能做 DSP/BWV 对照。只读一次。 */
    private static boolean forceBwv() {
        Boolean cached = FORCE_BWV;
        if (cached != null) return cached.booleanValue();
        // ★ 2026-08-29 起默认恒为 true：DSP 一阶段已实测判死——PAL 能加载模型，
        // 但联想 ADSP 的 capi_aispeech_wakeup 解不了小布的 BreenoSpeech blob，
        // 20 秒崩 15 次（见 memory: xiaobu-wakeup-dsp-viable 第 6 版）。
        // 每次尝试还会重启 SoundTrigger HAL、拖垮 audioserver，用户侧就是播放断音。
        //
        // 旧的 /data/local/tmp/ovoice_force_bwv 文件开关**从来没生效过**：
        // 应用域读不到 shell_data_file，File.exists() 恒为 false，
        // 于是这半年一直跑的是"DSP 优先、失败再降级"，白白搭上 9 秒探测窗口。
        // 想再做 DSP 对照实验用：settings put global aclaniakea_ovoice_dsp 1
        boolean forced = true;
        try {
            Application a = currentApplication();
            if (a != null && Settings.Global.getInt(a.getContentResolver(), DSP_OPT_IN_KEY, 0) != 0) forced = false;
        } catch (Throwable t) { XposedBridge.log(t); }
        FORCE_BWV = Boolean.valueOf(forced);
        XposedBridge.log("ColorOSVoiceWakeupBridge: backend = " + (forced ? "BWV only (DSP disabled by design)" : "DSP-first (opt-in via " + DSP_OPT_IN_KEY + ")"));
        return forced;
    }

    /**
     * 观察框架层 SoundTrigger 的真实结果。OVoice 的 c5.e 会话把返回码埋在自己的日志里，
     * 但那些是混淆类；直接挂 SoundTriggerModule 才是稳定判据。
     */
    /**
     * 掐掉 OVMS 对 DSP 一阶段的枚举。
     *
     * 只拦 s() 是不够的：OVMS 走的是 "legacy STModule API session impl" 这条路，
     * 会绕过 s() 直接 attachModule + loadPhraseSoundModel，于是每 2~5 秒就把
     * SoundTrigger HAL 打挂一次（SoundTriggerHalEnforcer: rebooting HAL）——
     * ST HAL 活在 audioserver 里，用户侧表现就是播放每隔几秒断一下。
     *
     * 最干净的扼流点是 listModulesAsOriginator 返回空表：OVMS 自己会打
     * "attachModule: no available modules" 然后老老实实降级，全程不碰 HAL。
     * 本 hook 只在 OVMS 进程内生效，不影响 Google 的 hotword 等其它 ST 客户端。
     */
    /**
     * 只在真有唤醒候选时才让 OVMS 触发 OSense 的 VOICE_WAKEUP 场景。
     *
     * `WakeupService.b(int)` 会调
     * `OsenseResClient.osenseSetSceneAction(new OsenseSaRequest("", "OSENSE_ACTION_VOICE_WAKEUP", 150))`。
     * OSense 进这个场景会改 WALT 迁移门槛，退出时按**它自己的默认值**还原
     * （`upmigrate 60 95 95` / `downmigrate 50 85 85`）——于是 fix 模块开机写的
     * `60 95 82 / 50 85 70` 基线活不过几秒。实测：写完 2~3 秒被整组打回。
     *
     * ★ 归因更正：这**不是** `oplus_cpu_sched_eas_opt.ko`（旧 memory 的怀疑对象），
     * 也不是 input boost（boost=0 时照样发生）。OVMS 一停，值就稳稳待住 20 秒以上。
     *
     * 原厂手机上这个场景只在 DSP 报出唤醒候选时才进；本机因为 DSP 判死、BWV 常驻，
     * 每次会话（re)start 都会踩一次，等于把一个"唤醒瞬间"的场景变成了常驻抖动。
     * 所以这里恢复原厂语义：没有唤醒候选就不进场景。
     */
    private static void hookOsenseScene(ClassLoader loader) {
        if (!OSENSE_HOOKED.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", loader, "b", Integer.TYPE, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    long since = android.os.SystemClock.elapsedRealtime() - LAST_WAKE_EVENT_MS;
                    if (LAST_WAKE_EVENT_MS != 0L && since <= OSENSE_SCENE_WINDOW_MS) return;
                    hook.setResult(null);
                    int n = OSENSE_SKIPS.incrementAndGet();
                    if (n <= 3 || n % 100 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: skipped OSense VOICE_WAKEUP scene on routine BWV restart (#" + n + "); keeps the sched baseline alive");
                }
            });
            XposedBridge.log("ColorOSVoiceWakeupBridge: OSense VOICE_WAKEUP scene gated to real wake candidates");
        } catch (Throwable t) { OSENSE_HOOKED.set(false); XposedBridge.log(t); }
    }

    private static void blockDspEnumeration(ClassLoader loader) {
        if (!DSP_ENUM_BLOCKED.compareAndSet(false, true)) return;
        int hooked = 0;
        try {
            Class<?> st = XposedHelpers.findClass("android.hardware.soundtrigger.SoundTrigger", loader);
            for (Method m : st.getDeclaredMethods()) {
                String n = m.getName();
                if (!"listModulesAsOriginator".equals(n) && !"listModulesAsMiddleman".equals(n) && !"listModules".equals(n)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                        if (!forceBwv()) return;
                        try {
                            if (hook.args != null) for (Object a : hook.args) if (a instanceof java.util.List) ((java.util.List<?>) a).clear();
                        } catch (Throwable t) { XposedBridge.log(t); }
                        hook.setResult(Integer.valueOf(0));
                        int c = DSP_ENUM_BLOCKS.incrementAndGet();
                        if (c <= 3 || c % 50 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: returned empty SoundTrigger module list (block #" + c + "); DSP never reaches the HAL");
                    }
                });
                hooked++;
            }
            XposedBridge.log("ColorOSVoiceWakeupBridge: DSP enumeration chokepoint installed on " + hooked + " methods");
        } catch (Throwable t) { DSP_ENUM_BLOCKED.set(false); XposedBridge.log(t); }
    }

    private static void hookDspObserver(ClassLoader loader) {
        if (!DSP_OBSERVER_HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> stm = XposedHelpers.findClass("android.hardware.soundtrigger.SoundTriggerModule", loader);
            int hooked = 0;
            for (Method m : stm.getDeclaredMethods()) {
                final String name = m.getName();
                if (!"loadSoundModel".equals(name) && !"startRecognition".equals(name)
                        && !"stopRecognition".equals(name) && !"unloadSoundModel".equals(name)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                        Object r = hook.getResult();
                        int rc = (r instanceof Integer) ? ((Integer) r).intValue() : 0;
                        if ("startRecognition".equals(name)) {
                            if (rc == 0) {
                                if (DSP_OK.compareAndSet(false, true)) XposedBridge.log("ColorOSVoiceWakeupBridge: DSP wakeword listening (SoundTriggerModule.startRecognition OK)");
                            } else {
                                XposedBridge.log("ColorOSVoiceWakeupBridge: DSP startRecognition failed rc=" + rc);
                            }
                        } else if ("stopRecognition".equals(name) || "unloadSoundModel".equals(name)) {
                            DSP_OK.set(false);
                            XposedBridge.log("ColorOSVoiceWakeupBridge: DSP " + name + " rc=" + rc);
                        } else {
                            XposedBridge.log("ColorOSVoiceWakeupBridge: DSP loadSoundModel rc=" + rc);
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log("ColorOSVoiceWakeupBridge: SoundTrigger observer installed on " + hooked + " methods");
        } catch (Throwable t) {
            DSP_OBSERVER_HOOKED.set(false);
            XposedBridge.log("ColorOSVoiceWakeupBridge: SoundTrigger observer unavailable");
            XposedBridge.log(t);
        }
    }

    /** DSP 窗口内没等到 startRecognition 成功，就启 BWV 顶上。 */
    private static void armBwvFallback(final Object service, final ClassLoader loader, final String reason) {
        if (DSP_OK.get()) return;
        if (!BWV_FALLBACK_ARMED.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() {
            BWV_FALLBACK_ARMED.set(false);
            if (DSP_OK.get()) { XposedBridge.log("ColorOSVoiceWakeupBridge: DSP session live, BWV fallback stood down (" + reason + ")"); return; }
            XposedBridge.log("ColorOSVoiceWakeupBridge: no DSP session after " + DSP_PROBE_MS + "ms, falling back to BWV (" + reason + ")");
            startBwv(service, loader, "DSP fallback: " + reason);
        }}, DSP_PROBE_MS);
    }

    private static void hookWakeupSensitivity(ClassLoader loader) {
        if (!SENSITIVITY_HOOKED.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod("l5.f0", loader, "g", Integer.TYPE, new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    Object result = hook.getResult();
                    if (result instanceof float[]) {
                        float[] src = (float[]) result;
                        float[] tuned = new float[src.length];
                        for (int i = 0; i < src.length; i++) {
                            float v = src[i] * WAKEWORD_THRESHOLD_SCALE;
                            tuned[i] = Math.max(v, WAKEWORD_THRESHOLD_FLOOR);
                        }
                        hook.setResult(tuned);
                    }
                }
            });
            XposedBridge.log("ColorOSVoiceWakeupBridge: BWV first-stage threshold scale=" + WAKEWORD_THRESHOLD_SCALE);
        } catch (Throwable t) {
            SENSITIVITY_HOOKED.set(false);
            XposedBridge.log("ColorOSVoiceWakeupBridge: BWV first-stage threshold hook unavailable");
            XposedBridge.log(t);
        }
        try {
            final Class<?> cfg = XposedHelpers.findClass("p1.a", loader);
            final Class<?> callback = XposedHelpers.findClass("j5.b$a", loader);
            XposedHelpers.findAndHookMethod("q1.a", loader, "h", cfg, callback, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    try {
                        if (hook.args == null || hook.args.length == 0 || hook.args[0] == null) return;
                        Object thresholds = XposedHelpers.getObjectField(hook.args[0], "a");
                        if (thresholds instanceof float[]) {
                            float[] src = (float[]) thresholds;
                            boolean changed = false;
                            for (int i = 0; i < src.length; i++) {
                                if (src[i] > STREAM_THRESHOLD_RESCALE_GUARD) {
                                    src[i] = Math.max(src[i] * WAKEWORD_THRESHOLD_SCALE, WAKEWORD_THRESHOLD_FLOOR);
                                    changed = true;
                                }
                            }
                            if (changed && src.length > 0) XposedBridge.log("ColorOSVoiceWakeupBridge: stream wakeup threshold lowered to " + src[0]);
                        }
                    } catch (Throwable t) { XposedBridge.log(t); }
                }
            });
            XposedBridge.log("ColorOSVoiceWakeupBridge: stream wakeup threshold hook installed");
        } catch (Throwable t) {
            XposedBridge.log("ColorOSVoiceWakeupBridge: stream wakeup threshold hook unavailable");
            XposedBridge.log(t);
        }
        try {
            final Class<?> hparams = XposedHelpers.findClass("com.oplus.bot.speech.sdk.wakeup.BwvSdk$HParams", loader);
            XposedHelpers.findAndHookMethod("com.oplus.bot.speech.sdk.wakeup.BwvSdk", loader, "SetHParams", hparams, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    try {
                        if (hook.args != null && hook.args.length > 0 && hook.args[0] != null) {
                            // stream_mode=false 会让 BWV 每个窗口跑一遍完整模型：
                            //   * CPU 常驻约 1/3 个核（实测 22~39%，重启频率降下来之后依旧）；
                            //   * 结果只在窗口边界产生，唤醒延迟 ≈ 一个检测窗口。
                            // 流式模式是连续轻量检测，听到就报，CPU 和延迟都低得多。
                            // 默认走流式；要退回整窗模型做对照：
                            //   settings put global aclaniakea_ovoice_stream 0
                            boolean stream = streamMode();
                            XposedHelpers.setBooleanField(hook.args[0], "stream_mode", stream);
                            boolean vpr = flag(VPR_KEY, true);
                            XposedHelpers.setBooleanField(hook.args[0], "enable_vpr", vpr);
                            // enable_vad 只在被人关掉时才写回：VAD 关掉意味着 KWS 对每一帧都推理，
                            // 是最贵的跑法。这里只保证它是开的，不改原厂语义。
                            if (!XposedHelpers.getBooleanField(hook.args[0], "enable_vad") && flag(VAD_KEY, true)) {
                                XposedHelpers.setBooleanField(hook.args[0], "enable_vad", true);
                            }
                            // two_stage：便宜的一级先筛、命中再跑贵的二级。原厂这里是 false，
                            // 打开是省 CPU 的正路，但要模型支持，故默认不动、留旋钮做实验：
                            //   settings put global aclaniakea_ovoice_twostage 1
                            boolean two = flag(TWO_STAGE_KEY, false);
                            if (two) XposedHelpers.setBooleanField(hook.args[0], "two_stage", true);
                            XposedBridge.log("ColorOSVoiceWakeupBridge: BWV SetHParams stream_mode=" + stream
                                    + " vpr=" + vpr
                                    + " vad=" + XposedHelpers.getBooleanField(hook.args[0], "enable_vad")
                                    + " two_stage=" + XposedHelpers.getBooleanField(hook.args[0], "two_stage"));
                        }
                    } catch (Throwable t) { XposedBridge.log(t); }
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.bot.speech.sdk.wakeup.BwvSdk", loader, "lambda$init$1", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    if (hook.args == null || hook.args.length == 0 || !(hook.args[0] instanceof String)) return;
                    String original = (String) hook.args[0];
                    XposedBridge.log("ColorOSVoiceWakeupBridge: BWV model init path=" + original);
                    try {
                        JSONObject json = new JSONObject(original);
                        JSONObject vprint = json.optJSONObject("vprintEngine");
                        if (vprint != null) {
                            if (vprint.has("snrThresh")) vprint.put("snrThresh", 2.5);
                            XposedBridge.log("ColorOSVoiceWakeupBridge: BWV vprint kept app speakerId=" + vprint.optString("speakerId", "?"));
                        }
                        if (json.has("snrThresh")) json.put("snrThresh", 2.5);
                        String tuned = json.toString();
                        hook.args[0] = tuned;
                        XposedBridge.log("ColorOSVoiceWakeupBridge: BWV vprint config applied (app speakerId)");
                    } catch (Throwable t) { XposedBridge.log(t); }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ColorOSVoiceWakeupBridge: BWV non-stream model hook unavailable");
            XposedBridge.log(t);
        }
    }

    private static void hookFirstStageMarker(ClassLoader loader) {
        if (!FIRST_STAGE_HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> service = Class.forName("com.oplus.ovoicemanager.wakeup.service.WakeupService", false, loader);
            for (Method m : service.getDeclaredMethods()) if ("a".equals(m.getName()) && m.getParameterTypes().length == 1) {
                XposedHelpers.findAndHookMethod(service.getName(), loader, "a", m.getParameterTypes()[0], new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam p) { LAST_WAKE_EVENT_MS = android.os.SystemClock.elapsedRealtime(); FIRST_STAGE_RESULT.set(true); RETRY_GENERATION.incrementAndGet(); XposedBridge.log("ColorOSVoiceWakeupBridge: BWV first-stage recognition received"); }
                });
                return;
            }
        } catch (Throwable t) { FIRST_STAGE_HOOKED.set(false); XposedBridge.log(t); }
    }

    private static void hookSecondStage(final XC_LoadPackage.LoadPackageParam p) {
        try {
            XC_MethodHook result = new XC_MethodHook() { @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                LAST_WAKE_EVENT_MS = android.os.SystemClock.elapsedRealtime();
                SECOND_STAGE_RESULT.set(true); BWV_STARTED.set(false); RETRY_GENERATION.incrementAndGet(); scheduleRearm(hook.thisObject, p.classLoader, POST_WAKE_REARM_MS, "second-stage result");
            }};
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "q", JSONObject.class, result);
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "r", JSONObject.class, result);
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "i", new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    // wakeupWord=null 是"这一窗没听到"，会话到此结束，必须**立刻**重开，
                    // 否则中间就是一段听不见的空窗。
                    //
                    // ★ 2026-08-30 实测教训：我一度以为"按结果重启"是识别慢的根因，
                    // 改成只靠 PCM 断流看门狗（2.5 秒无音频才重开）。结果是
                    //   听 2.6 秒 → 聋 2.5 秒 → 听 2.6 秒 …
                    // 占空比只剩一半，用户直接反馈"很不灵敏"。日志实锤：
                    //   00:01:02.172 AudioRecord constructed
                    //   00:01:07.346 BWV PCM stalled 2565ms; restarting pipeline
                    // 原厂那种"出结果就立刻重开"才是连续覆盖的正确做法，恢复之。
                    // 看门狗保留，但只当兜底（BWV_STALL_MS 放长），负责管线真死掉的情况。
                    int n = NO_WORD_RESULTS.incrementAndGet();
                    if (n <= 3 || n % 200 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: no-word result #" + n + "; re-arming immediately");
                    if (!SECOND_STAGE_RESULT.get()) retry(hook.thisObject, p.classLoader, RETRY_DELAY_MS, true, "no-word result");
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService$e", p.classLoader, "onReceive", Context.class, Intent.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    Object i = hook.args == null || hook.args.length < 2 ? null : hook.args[1];
                    if (i instanceof Intent && "android.media.ACTION_AUDIO_RECORD_STOP".equals(((Intent) i).getAction()) && SPEECH_ASSIST.equals(((Intent) i).getStringExtra("android.media.EXTRA_RECORD_PACKAGE_NAME"))) {
                        Object service = enclosingService(hook.thisObject); if (service != null) scheduleRearm(service, p.classLoader, 1200L, "SpeechAssist microphone stop");
                    }
                }
            });
        } catch (Throwable t) { XposedBridge.log(t); }
    }

    private static void hookBwvAudio(XC_LoadPackage.LoadPackageParam p) {
        if (AUDIO_ROUTE_HOOKED.compareAndSet(false, true)) try {
            XposedHelpers.findAndHookConstructor("m5.b", p.classLoader, AudioAttributes.class, AudioFormat.class, Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) { XposedBridge.log("ColorOSVoiceWakeupBridge: BWV AudioRecord capture constructed"); }
            });
        } catch (Throwable t) { AUDIO_ROUTE_HOOKED.set(false); XposedBridge.log(t); }
        if (AUDIO_LEVEL_HOOKED.compareAndSet(false, true)) try {
            XposedHelpers.findAndHookMethod("h5.b", p.classLoader, "b", byte[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    if (hook.args == null || hook.args.length == 0 || !(hook.args[0] instanceof byte[])) return;
                    byte[] pcm = (byte[]) hook.args[0];
                    if (pcm.length < 2) return;
                    LAST_PCM_MS = android.os.SystemClock.elapsedRealtime();
                    int chunks = PCM_CHUNKS.incrementAndGet();
                    int samples = pcm.length / 2;
                    long sum = 0; int peak = 0; int scanned = 0;
                    // Fast estimate pass (stride 4): standby audio is mostly quiet,
                    // so skip the per-sample gain/soft-limit rewrite unless needed.
                    boolean needRewrite = BWV_PCM_GAIN != 1.0f;
                    for (int i = 0; i + 1 < pcm.length; i += 8) {
                        short s = (short) (((pcm[i + 1] & 255) << 8) | (pcm[i] & 255));
                        int abs = s < 0 ? -s : s;
                        if (abs > peak) peak = abs;
                        if (abs > BWV_SOFT_LIMIT_START) needRewrite = true;
                        sum += (long) s * s;
                        scanned++;
                    }
                    if (needRewrite) {
                        sum = 0; peak = 0; scanned = 0;
                        for (int i = 0; i + 1 < pcm.length; i += 2) {
                            short s = (short) (((pcm[i + 1] & 255) << 8) | (pcm[i] & 255));
                            float v = s * BWV_PCM_GAIN;
                            if (v > BWV_SOFT_LIMIT_START) {
                                v = BWV_SOFT_LIMIT_START + (v - BWV_SOFT_LIMIT_START) * BWV_SOFT_LIMIT_RATIO;
                            } else if (v < -BWV_SOFT_LIMIT_START) {
                                v = -BWV_SOFT_LIMIT_START + (v + BWV_SOFT_LIMIT_START) * BWV_SOFT_LIMIT_RATIO;
                            }
                            long scaled = Math.round(v);
                            if (scaled > 32767) scaled = 32767;
                            if (scaled < -32768) scaled = -32768;
                            pcm[i] = (byte) (scaled & 255);
                            pcm[i + 1] = (byte) ((scaled >> 8) & 255);
                            int abs = scaled < 0 ? (int) -scaled : (int) scaled;
                            if (abs > peak) peak = abs;
                            sum += scaled * scaled;
                            scanned++;
                        }
                    }
                    if (chunks % 200 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: BWV PCM gain=" + BWV_PCM_GAIN + " chunks=" + chunks + " rms=" + (int) (scanned == 0 ? 0 : Math.sqrt(sum / scanned)) + " peak=" + peak);
                }
            });
        } catch (Throwable t) { AUDIO_LEVEL_HOOKED.set(false); XposedBridge.log(t); }
    }

    /**
     * PCM 断流看门狗。取代按结果重启的老逻辑：只有真的收不到音频了才重建管线，
     * 正常的 null 结果一律不打断。同时承担总开关的收敛——关掉唤醒后不再重新拉起。
     */
    private static void startWatchdog(final Object service, final ClassLoader loader) {
        if (!WATCHDOG_RUNNING.compareAndSet(false, true)) return;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() { @Override public void run() {
            try {
                if (!wakeupEnabled()) {
                    if (BWV_STARTED.get()) XposedBridge.log("ColorOSVoiceWakeupBridge: wakeup switch off; letting BWV pipeline drain");
                    BWV_STARTED.set(false);
                    WATCHDOG_RUNNING.set(false);
                    return;
                }
                // 流式模式下 SDK 不走 h5.b.b(byte[])，PCM 探针天然收不到数据，
                // 此时管线由结果回调自己驱动，看门狗必须让开，否则就是重启风暴。
                // 同理，从没收到过 PCM 也说明探针没挂上，宁可不动。
                if (streamMode() || PCM_CHUNKS.get() == 0) { h.postDelayed(this, BWV_WATCHDOG_PERIOD_MS); return; }
                long idle = android.os.SystemClock.elapsedRealtime() - LAST_PCM_MS;
                if (BWV_STARTED.get() && idle > BWV_STALL_MS) {
                    XposedBridge.log("ColorOSVoiceWakeupBridge: BWV PCM stalled " + idle + "ms; restarting pipeline");
                    BWV_STARTED.set(false);
                    WATCHDOG_RUNNING.set(false);
                    startBwv(service, loader, "PCM stall recovery");
                    return;
                }
            } catch (Throwable t) { XposedBridge.log(t); }
            h.postDelayed(this, BWV_WATCHDOG_PERIOD_MS);
        }}, BWV_WATCHDOG_PERIOD_MS);
    }

    /**
     * 把 BWV 单次监听窗口从 2600ms 抬到 6000ms，减少"窗口边界切断唤醒词"和重启间隙漏听。
     * 模块里那份 my_product/etc/OVMS_settings.xml 写了 6000 却从来没生效过——
     * KernelSU 对应用卸载模块挂载，OVMS 读到的一直是分区原件的 2600
     * （见 feedback_ksu_module_mount_invisible_to_apps）。所以改在进程内直接写静态字段。
     */
    private static void raiseDetectionTimeout(ClassLoader loader) {
        if (!DETECTION_TIMEOUT_SET.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod("javax.xml.parsers.DocumentBuilder", loader, "parse", File.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    try {
                        Object arg = hook.args == null || hook.args.length == 0 ? null : hook.args[0];
                        if (!(arg instanceof File) || !((File) arg).getPath().endsWith("OVMS_settings.xml")) return;
                        Object doc = hook.getResult();
                        if (doc == null) return;
                        org.w3c.dom.Document d = (org.w3c.dom.Document) doc;
                        int want = detectionWindowMs();
                        if (want <= 0) return;
                        org.w3c.dom.NodeList list = d.getDocumentElement().getElementsByTagName("detection_timeout");
                        for (int i = 0; i < list.getLength(); i++) {
                            org.w3c.dom.NodeList v = ((org.w3c.dom.Element) list.item(i)).getElementsByTagName("value");
                            if (v.getLength() == 0) continue;
                            String cur = v.item(0).getTextContent();
                            if (String.valueOf(want).equals(cur)) continue;
                            v.item(0).setTextContent(String.valueOf(want));
                            int n = DETECTION_PATCHES.incrementAndGet();
                            if (n <= 3 || n % 100 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: detection window " + cur + "ms -> " + want + "ms (patch #" + n + ")");
                        }
                    } catch (Throwable t) { XposedBridge.log(t); }
                }
            });
            XposedBridge.log("ColorOSVoiceWakeupBridge: OVMS_settings.xml parse hook installed (detection window override = " + detectionWindowMs() + "ms, 0=stock)");
        } catch (Throwable t) { DETECTION_TIMEOUT_SET.set(false); XposedBridge.log(t); }
    }

    /** 读一个 Settings.Global 布尔旋钮，读不到就用默认值。 */
    private static boolean flag(String key, boolean fallback) {
        try {
            Application a = currentApplication();
            if (a != null) return Settings.Global.getInt(a.getContentResolver(), key, fallback ? 1 : 0) != 0;
        } catch (Throwable t) { XposedBridge.log(t); }
        return fallback;
    }

    /** 监听窗口覆盖值，0 = 用原厂值。 */
    private static int detectionWindowMs() {
        try {
            Application a = currentApplication();
            if (a != null) return Settings.Global.getInt(a.getContentResolver(), DETECTION_WINDOW_KEY, 0);
        } catch (Throwable t) { XposedBridge.log(t); }
        return 0;
    }

    /** BWV 是否走流式检测。默认**不走**。
     * 2026-08-29 实测：开流式之后 SDK 不再经过 h5.b.b(byte[])，PCM 探针一条都收不到，
     * 断流看门狗于是每 1.5 秒重启一次管线（日志里 SetHParams 刷屏），BWV 直接不可用。
     * 要再试流式必须先给流式路径找到对应的音频回调做存活信号。
     * 打开做对照：settings put global aclaniakea_ovoice_stream 1 */
    private static boolean streamMode() {
        Boolean cached = STREAM_MODE;
        if (cached != null) return cached.booleanValue();
        boolean stream = false;
        try {
            Application a = currentApplication();
            if (a != null) stream = Settings.Global.getInt(a.getContentResolver(), STREAM_MODE_KEY, 0) != 0;
        } catch (Throwable t) { XposedBridge.log(t); }
        STREAM_MODE = Boolean.valueOf(stream);
        return stream;
    }

    /** 读 ColorOS 的语音唤醒总开关。缺省当作开，避免读不到就把功能关死。 */
    private static boolean wakeupEnabled() {
        try {
            Application a = currentApplication();
            if (a == null) return true;
            return Settings.Global.getInt(a.getContentResolver(), WAKEUP_SWITCH_KEY, 1) != 0;
        } catch (Throwable t) { return true; }
    }

    private static boolean wakeupEntryEnabled() {
        try {
            Application a = currentApplication();
            return a != null && Settings.Global.getInt(a.getContentResolver(), WAKEUP_ENTRY_KEY, 0) != 0;
        } catch (Throwable t) { return false; }
    }

    private static void scheduleRearm(final Object service, final ClassLoader loader, long delay, String reason) {
        final int generation = REARM_GENERATION.incrementAndGet();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() {
            if (generation == REARM_GENERATION.get()) {
                FIRST_STAGE_RESULT.set(false);
                SECOND_STAGE_RESULT.set(false);
                BWV_STARTED.set(false);
                startBwv(service, loader, "post-wakeup re-arm");
            }
        }}, delay);
        XposedBridge.log("ColorOSVoiceWakeupBridge: scheduled BWV re-arm in " + delay + "ms (" + reason + ")");
    }

    private static void retry(final Object service, final ClassLoader loader, long delay, boolean reset, final String reason) {
        if (FIRST_STAGE_RESULT.get() || SECOND_STAGE_RESULT.get()) return;
        if (reset) BWV_STARTED.set(false);
        final int generation = RETRY_GENERATION.incrementAndGet();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() {
            if (generation == RETRY_GENERATION.get() && !FIRST_STAGE_RESULT.get() && !SECOND_STAGE_RESULT.get()) { BWV_STARTED.set(false); startBwv(service, loader, reason); }
        }}, delay);
    }

    private static boolean startBwv(Object service, ClassLoader loader, String reason) {
        // DSP 在监听时不要再拉起 CPU 侧的 AudioRecord 推理——两条路抢同一支麦克风，
        // 而且 BWV 常驻会吃掉约一个核的 10%。二阶段结果后的 re-arm 也走这里。
        if (DSP_OK.get() && !forceBwv()) return false;
        if (!wakeupEnabled()) { XposedBridge.log("ColorOSVoiceWakeupBridge: wakeup switch is off; not starting BWV (" + reason + ")"); return false; }
        if (!BWV_STARTED.compareAndSet(false, true)) return false;
        raiseDetectionTimeout(loader);
        try {
            Method target = null; for (Method m : service.getClass().getDeclaredMethods()) if ("n".equals(m.getName()) && m.getParameterTypes().length == 1) { target = m; break; }
            if (target == null) throw new NoSuchMethodException("WakeupService.n(one arg)");
            Constructor<?> ctor = target.getParameterTypes()[0].getDeclaredConstructor(Integer.TYPE, Integer.TYPE);
            ctor.setAccessible(true); target.setAccessible(true); target.invoke(service, ctor.newInstance(0, 0));
            LAST_PCM_MS = android.os.SystemClock.elapsedRealtime();
            XposedBridge.log("ColorOSVoiceWakeupBridge: " + reason + "; started BWV AudioRecord listener");
            startWatchdog(service, loader);
            return true;
        } catch (Throwable t) { BWV_STARTED.set(false); XposedBridge.log("ColorOSVoiceWakeupBridge: BWV start failed"); XposedBridge.log(t); return false; }
    }

    private static Object enclosingService(Object receiver) {
        if (receiver == null) return null;
        try {
            Class<?> service = Class.forName("com.oplus.ovoicemanager.wakeup.service.WakeupService", false, receiver.getClass().getClassLoader());
            for (Field f : receiver.getClass().getDeclaredFields()) if (service.isAssignableFrom(f.getType())) { f.setAccessible(true); Object value = f.get(receiver); if (value != null) return value; }
        } catch (Throwable t) { XposedBridge.log(t); }
        return null;
    }

    private static boolean hasWakeupService() { try { Application a = currentApplication(); if (a == null) return false; a.getPackageManager().getApplicationInfo(WAKEUP_SERVICE, 0); return true; } catch (Throwable t) { return false; } }
    private static Application currentApplication() throws Exception { Object a = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null); return a instanceof Application ? (Application) a : null; }
}
