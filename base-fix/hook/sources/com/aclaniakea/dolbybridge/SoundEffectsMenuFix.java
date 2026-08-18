package com.aclaniakea.dolbybridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps the sound-effects mode menu interactive on this port.
 *
 * With OReality removed the menu map ends up with a single entry (1=杜比全景声),
 * so Settings auto-disables the menu (one-choice mode). We re-add the
 * "原始" (OFF) entry so the user can toggle between OFF and Dolby Atmos, and
 * reflect the real Dolby state instead of the stale service-less default.
 */
public final class SoundEffectsMenuFix implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.settings";
    private static final String FRAGMENT = "com.oplus.settings.feature.soundeffects.view.SoundEffectsFragment";
    private static final String SERVICE = "com.aclaniakea.colorosostatsguard";
    private static final String SERVICE_CLASS = "com.aclaniakea.dolbybridge.DolbyBridgeService";
    private static final String DESCRIPTOR = "com.oplus.audio.effectcenter.IOplusEffectService";
    private static final Object LOCK = new Object();
    private static IBinder bridge;
    private static boolean binding;

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(FRAGMENT, lp.classLoader, "updateSoundEffectsMenu",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object map = XposedHelpers.getObjectField(p.thisObject, "mSoundEffectValueToTitleMap");
                                if (map instanceof java.util.Map) {
                                    java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) map;
                                    if (!m.containsKey("0")) {
                                        m.put("0", "原始");
                                        XposedBridge.log("SoundEffectsMenuFix: added 原始 OFF entry, size=" + m.size());
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("SoundEffectsMenuFix: map patch failed " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: hook failed " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.settings.feature.soundeffects.view.DolbyAudioModePreference",
                    lp.classLoader, "selectMode", int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            // selectMode(scene, soundEffectCategory): the first argument
                            // is the Dolby scene; the second is the effect category.
                            int mode = (Integer) p.args[0];
                            XposedBridge.log("SoundEffectsMenuFix: Dolby scene selected mode=" + mode);
                            sendTransaction((Context) XposedHelpers.callMethod(p.thisObject, "getContext"), 14, mode);
                        }
                    });
            XposedBridge.log("SoundEffectsMenuFix: scene fallback hook installed");
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: scene hook failed " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.settings.feature.soundeffects.view.DolbyAudioModePreference",
                    lp.classLoader, "selectEqualizer", int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            int preset = (Integer) p.args[0];
                            XposedBridge.log("SoundEffectsMenuFix: EQ preset selected=" + preset);
                            sendTransaction((Context) XposedHelpers.callMethod(p.thisObject, "getContext"), 3, preset);
                        }
                    });
            XposedBridge.log("SoundEffectsMenuFix: EQ fallback hook installed");
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: EQ hook failed " + t);
        }
        hookOemController(lp);
    }

    private static void hookOemController(XC_LoadPackage.LoadPackageParam lp) {
        final String manager = "com.oplus.partners.dolby.DolbyController";
        try {
            XposedHelpers.findAndHookMethod(manager, lp.classLoader,
                    "setUserCheckedMusicEqualizerPreset", int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            putInt((Context) XposedHelpers.getObjectField(p.thisObject, "mContext"),
                                    "system_dolby_music_ieq", (Integer) p.args[0]);
                        }
                    });
            XposedHelpers.findAndHookMethod(manager, lp.classLoader,
                    "setUserEqualizerCustomBandGains", int[].class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            putString((Context) XposedHelpers.getObjectField(p.thisObject, "mContext"),
                                    "system_dolby_music_geq", gains((int[]) p.args[0]));
                        }
                    });
            XposedHelpers.findAndHookMethod(manager, lp.classLoader,
                    "setUserEqualizerCustomBandGainsRenewal", int[].class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            putString((Context) XposedHelpers.getObjectField(p.thisObject, "mContext"),
                                    "system_dolby_music_geq", gains((int[]) p.args[0]));
                        }
                    });
            XposedHelpers.findAndHookMethod(manager, lp.classLoader,
                    "setUserCheckedDolbySwitch", boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Context c = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                            boolean on = (Boolean) p.args[0];
                            putInt(c, "system_dolby", on ? 1 : 0);
                            sendTransaction(c, 1, on ? 1 : 0);
                        }
                    });
            XposedHelpers.findAndHookMethod(manager, lp.classLoader,
                    "setUserCheckedSoundEffectMode", int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            putInt((Context) XposedHelpers.getObjectField(p.thisObject, "mContext"),
                                    "system_dolby_category", (Integer) p.args[0]);
                        }
                    });
            XposedBridge.log("SoundEffectsMenuFix: OEM Settings sync hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: OEM sync hook failed " + t);
        }
    }

    private static String gains(int[] a) {
        if (a == null) return "";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) s.append(','); s.append(a[i]); }
        return s.toString();
    }

    private static void putInt(Context c, String key, int value) {
        if (c == null) return;
        try { Settings.System.putInt(c.getContentResolver(), key, value); }
        catch (Throwable t) { XposedBridge.log("OEM putInt failed " + key + ": " + t); }
    }

    private static void putString(Context c, String key, String value) {
        if (c == null) return;
        try { Settings.System.putString(c.getContentResolver(), key, value); }
        catch (Throwable t) { XposedBridge.log("OEM putString failed " + key + ": " + t); }
    }

    private static void sendTransaction(Context context, int code, int value) {
        if (context == null) return;
        synchronized (LOCK) {
            if (bridge != null && bridge.isBinderAlive()) {
                transact(bridge, code, value);
                return;
            }
            if (binding) return;
            binding = true;
        }
        Intent i = new Intent().setComponent(new ComponentName(SERVICE, SERVICE_CLASS));
        try {
            context.bindService(i, new ServiceConnection() {
                public void onServiceConnected(ComponentName n, IBinder b) {
                    synchronized (LOCK) { bridge = b; binding = false; }
                    transact(b, code, value);
                }
                public void onServiceDisconnected(ComponentName n) {
                    synchronized (LOCK) { bridge = null; binding = false; }
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            synchronized (LOCK) { binding = false; }
            XposedBridge.log("SoundEffectsMenuFix: bind fallback failed " + t);
        }
    }

    private static void transact(IBinder b, int code, int value) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(value);
            b.transact(code, data, reply, 0);
            XposedBridge.log("SoundEffectsMenuFix: forwarded txn=" + code + " value=" + value);
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: scene transact failed " + t);
        } finally { reply.recycle(); data.recycle(); }
    }
}
