package com.aclaniakea.colorosvoicewakeupbridge;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** XiaoBu wakeword bridge using OVoice's BWV AudioRecord implementation only. */
public final class ColorOSVoiceWakeupBridge implements IXposedHookLoadPackage {
    private static final String WAKEUP_SERVICE = "com.oplus.ovoicemanager.wakeup";
    private static final String SPEECH_ASSIST = "com.heytap.speechassist";
    private static final String VOICE_WAKEUP_FEATURE = "oplus.software.audio.voice_wakeup_support";
    private static final String XIAOBU_FEATURE = "oplus.software.audio.voice_wakeup_xbxb_support";
    private static final long START_DELAY_MS = 500L;
    private static final long RETRY_DELAY_MS = 40L;
    private static final long LISTEN_WINDOW_MS = 3500L;
    private static final long POST_WAKE_REARM_MS = 2500L;
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
    private static final AtomicInteger START_REQUESTS = new AtomicInteger(0);
    private static final AtomicInteger RETRY_GENERATION = new AtomicInteger(0);
    private static final AtomicInteger REARM_GENERATION = new AtomicInteger(0);
    private static final AtomicInteger PCM_CHUNKS = new AtomicInteger(0);

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
                    if (hasWakeupService()) {
                        p.setResult(Boolean.TRUE);
                        if (SPEECH_ENTRY_REPORTED.compareAndSet(false, true)) XposedBridge.log("ColorOSVoiceWakeupBridge: exposed XiaoBu wake entry");
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
                        hook.setResult(Boolean.TRUE);
                        if (CAPABILITY_REPORTED.compareAndSet(false, true)) XposedBridge.log("ColorOSVoiceWakeupBridge: enabled OVoice capability (BWV-only)");
                    }
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "s", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    hook.setResult(-1003);
                    int calls = START_REQUESTS.incrementAndGet();
                    if (calls <= 3 || calls % 20 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: blocked legacy first-stage request; starting BWV call=" + calls);
                    startBwv(hook.thisObject, p.classLoader, "legacy first-stage request");
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "onServiceDied", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    startBwv(hook.thisObject, p.classLoader, "service recovery");
                    // The stock recovery reconnects the removed first-stage backend.
                    hook.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "onResourcesAvailable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) { startBwv(hook.thisObject, p.classLoader, "resources available"); }
            });
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(final XC_MethodHook.MethodHookParam hook) {
                    FIRST_STAGE_RESULT.set(false); SECOND_STAGE_RESULT.set(false); BWV_STARTED.set(false); PCM_CHUNKS.set(0); RETRY_GENERATION.incrementAndGet();
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() { startBwv(hook.thisObject, p.classLoader, "service startup"); } }, START_DELAY_MS);
                    XposedBridge.log("ColorOSVoiceWakeupBridge: BWV-only active; DSP/UIM/SoundTrigger path removed");
                }
            });
            hookFirstStageMarker(p.classLoader);
            hookSecondStage(p);
            hookBwvAudio(p);
            hookWakeupSensitivity(p.classLoader);
            XposedBridge.log("ColorOSVoiceWakeupBridge: BWV-only OVoice hooks installed");
        } catch (Throwable t) { XposedBridge.log("ColorOSVoiceWakeupBridge: BWV hook installation failed"); XposedBridge.log(t); }
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
                            XposedHelpers.setBooleanField(hook.args[0], "stream_mode", false);
                            XposedHelpers.setBooleanField(hook.args[0], "enable_vpr", true);
                            XposedBridge.log("ColorOSVoiceWakeupBridge: BWV SetHParams forced non-stream full model + vpr=on (app speakerId)");
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
                    @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam p) { FIRST_STAGE_RESULT.set(true); RETRY_GENERATION.incrementAndGet(); XposedBridge.log("ColorOSVoiceWakeupBridge: BWV first-stage recognition received"); }
                });
                return;
            }
        } catch (Throwable t) { FIRST_STAGE_HOOKED.set(false); XposedBridge.log(t); }
    }

    private static void hookSecondStage(final XC_LoadPackage.LoadPackageParam p) {
        try {
            XC_MethodHook result = new XC_MethodHook() { @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                SECOND_STAGE_RESULT.set(true); BWV_STARTED.set(false); RETRY_GENERATION.incrementAndGet(); scheduleRearm(hook.thisObject, p.classLoader, POST_WAKE_REARM_MS, "second-stage result");
            }};
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "q", JSONObject.class, result);
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "r", JSONObject.class, result);
            XposedHelpers.findAndHookMethod("com.oplus.ovoicemanager.wakeup.service.WakeupService", p.classLoader, "i", new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) { if (!SECOND_STAGE_RESULT.get()) retry(hook.thisObject, p.classLoader, RETRY_DELAY_MS, true, "no-word result"); }
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
                    int chunks = PCM_CHUNKS.incrementAndGet();
                    long sum = 0; int peak = 0; int samples = pcm.length / 2;
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
                    }
                    if (chunks % 50 == 0) XposedBridge.log("ColorOSVoiceWakeupBridge: BWV PCM gain=" + BWV_PCM_GAIN + " chunks=" + chunks + " rms=" + (int) (samples == 0 ? 0 : Math.sqrt(sum / samples)) + " peak=" + peak);
                }
            });
        } catch (Throwable t) { AUDIO_LEVEL_HOOKED.set(false); XposedBridge.log(t); }
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
        if (!BWV_STARTED.compareAndSet(false, true)) return false;
        try {
            Method target = null; for (Method m : service.getClass().getDeclaredMethods()) if ("n".equals(m.getName()) && m.getParameterTypes().length == 1) { target = m; break; }
            if (target == null) throw new NoSuchMethodException("WakeupService.n(one arg)");
            Constructor<?> ctor = target.getParameterTypes()[0].getDeclaredConstructor(Integer.TYPE, Integer.TYPE);
            ctor.setAccessible(true); target.setAccessible(true); target.invoke(service, ctor.newInstance(0, 0));
            XposedBridge.log("ColorOSVoiceWakeupBridge: " + reason + "; started BWV AudioRecord listener");
            retry(service, loader, LISTEN_WINDOW_MS, false, "BWV listening window timeout");
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
