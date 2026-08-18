package com.aclaniakea.dolbybridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
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
    private static volatile boolean keeperBound;
    private static final ServiceConnection KEEPER = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            keeperBound = true;
            XposedBridge.log("SoundEffectsMenuFix: persistent Dolby bridge connected");
        }
        public void onServiceDisconnected(ComponentName name) { keeperBound = false; }
    };

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lp.classLoader,
                    "attach", Context.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            keepBridgeAlive((Context) p.args[0]);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: keeper hook failed " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(FRAGMENT, lp.classLoader, "updateSoundEffectsMenu",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Context c = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                                int enabled = c == null ? 0 : Settings.System.getInt(
                                        c.getContentResolver(), "system_dolby", 0);
                                // mCurrentMode is the actual source for both the
                                // assignment label and checked popup item.  It is
                                // not restored from a Settings key on this port.
                                XposedHelpers.setIntField(p.thisObject, "mCurrentMode",
                                        enabled != 0 ? 1 : 0);
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
            XposedHelpers.findAndHookMethod(FRAGMENT, lp.classLoader, "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Context c = (Context) XposedHelpers.callMethod(p.thisObject, "getContext");
                                int enabled = c == null ? 0 : Settings.System.getInt(
                                        c.getContentResolver(), "system_dolby", 0);
                                XposedHelpers.setIntField(p.thisObject, "mCurrentMode",
                                        enabled != 0 ? 1 : 0);
                                // The first stock pass may already have hidden
                                // DolbyAudioModePreference while mode was 0.
                                // Re-run the OEM switch routine so its complete
                                // scene/EQ content is restored, not just the menu label.
                                XposedHelpers.callMethod(p.thisObject, "switchSoundEffect");
                                XposedHelpers.callMethod(p.thisObject, "updateSoundEffectsMenu");
                                XposedBridge.log("SoundEffectsMenuFix: resumed mode="
                                        + (enabled != 0 ? 1 : 0));
                            } catch (Throwable t) {
                                XposedBridge.log("SoundEffectsMenuFix: resume sync failed " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: resume hook failed " + t);
        }
        hookOemController(lp);
    }

    private static void keepBridgeAlive(Context context) {
        if (context == null || keeperBound) return;
        try {
            Intent i = new Intent().setComponent(new ComponentName(
                    "com.aclaniakea.colorosostatsguard",
                    "com.aclaniakea.dolbybridge.DolbyBridgeService"));
            keeperBound = context.bindService(i, KEEPER, Context.BIND_AUTO_CREATE);
            XposedBridge.log("SoundEffectsMenuFix: persistent bridge bind=" + keeperBound);
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: persistent bridge bind failed " + t);
        }
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
                            Context c = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                            putString(c, "system_dolby_music_geq", gains((int[]) p.args[0]));
                        }
                    });
            XposedHelpers.findAndHookMethod(manager, lp.classLoader,
                    "setUserEqualizerCustomBandGainsRenewal", int[].class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Context c = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                            putString(c, "system_dolby_music_geq", gains((int[]) p.args[0]));
                            putInt(c, "system_dolby_music_ieq", (Integer) p.args[1]);
                        }
                    });
            XposedHelpers.findAndHookMethod(manager, lp.classLoader,
                    "setUserCheckedDolbySwitch", boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Context c = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                            boolean on = (Boolean) p.args[0];
                            putInt(c, "system_dolby", on ? 1 : 0);
                            putInt(c, "system_effect_profile", on ? 1 : 0);
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

}
