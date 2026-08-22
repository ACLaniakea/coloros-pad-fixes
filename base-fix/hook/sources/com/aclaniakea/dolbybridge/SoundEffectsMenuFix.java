package com.aclaniakea.dolbybridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
    private static final String DOLBY_OBSERVER = FRAGMENT + "$DolbyStateObserver";
    private static volatile boolean keeperBound;
    private static volatile boolean keeperRetryScheduled;
    private static volatile int keeperAttempts;
    private static volatile Context keeperContext;
    private static volatile boolean dolbyRefreshScheduled;
    // LSPosed may instantiate the module entry while a scoped child process is
    // still being forked and Looper.getMainLooper() is null.  Never create a
    // Handler from the class initializer: one unrelated scoped process failing
    // here can make the whole module look intermittently unloaded.
    private static volatile Handler mainHandler;
    private static final ServiceConnection KEEPER = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            keeperBound = true;
            keeperAttempts = 0;
            XposedBridge.log("SoundEffectsMenuFix: persistent Dolby bridge connected");
        }
        public void onServiceDisconnected(ComponentName name) {
            keeperBound = false;
            scheduleBridgeRetry();
        }
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
                                // mCurrentMode is the actual source for both the
                                // assignment label and checked popup item.  It is
                                // not restored from a Settings key on this port.
                                Object map = XposedHelpers.getObjectField(p.thisObject, "mSoundEffectValueToTitleMap");
                                if (map instanceof java.util.Map) {
                                    java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) map;
                                    if (!m.containsKey("0")) {
                                        m.put("0", "原始");
                                        XposedBridge.log("SoundEffectsMenuFix: added 原始 OFF entry, size=" + m.size());
                                    }
                                }
                                int mode = resolveMode(p.thisObject, c);
                                XposedHelpers.setIntField(p.thisObject, "mCurrentMode", mode);
                                forceFragmentMode(p.thisObject, mode);
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
                                int mode = resolveMode(p.thisObject, c);
                                int enabled = c == null ? 0 : Settings.System.getInt(
                                        c.getContentResolver(), "system_dolby", 0);
                                // The stock fragment also observes the hidden
                                // Secure key dolby_switch. On this port it can
                                // remain 1 after the public/system mode is set
                                // to Original, reopening the Dolby controls.
                                putSecureInt(c, "dolby_switch", enabled != 0 ? 1 : 0);
                                XposedHelpers.setIntField(p.thisObject, "mCurrentMode", mode);
                                // The first stock pass may already have hidden
                                // DolbyAudioModePreference while mode was 0.
                                // Re-run the OEM switch routine so its complete
                                // scene/EQ content is restored, not just the menu label.
                                XposedHelpers.callMethod(p.thisObject, "switchSoundEffect");
                                XposedHelpers.callMethod(p.thisObject, "updateSoundEffectsMenu");
                                XposedBridge.log("SoundEffectsMenuFix: resumed mode=" + mode);
                            } catch (Throwable t) {
                                XposedBridge.log("SoundEffectsMenuFix: resume sync failed " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: resume hook failed " + t);
        }
        hookFragmentStateSources(lp);
        hookOemController(lp);
    }

    private static void hookFragmentStateSources(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> preference = XposedHelpers.findClass("androidx.preference.Preference", lp.classLoader);
            XposedHelpers.findAndHookMethod(FRAGMENT, lp.classLoader, "onPreferenceChange",
                    preference, Object.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            String value = String.valueOf(p.args[1]);
                            if (!"0".equals(value) && !"1".equals(value) && !"3".equals(value)) return;
                            // Keep the OEM mutual-exclusion decision. The stock
                            // fragment returns false (and shows its own toast)
                            // when Spatial Audio is selected while MSS sound-
                            // field expansion is available/enabled. Previously
                            // this after-hook ignored that result and forced mode
                            // 3 anyway, leaving both controls visually enabled.
                            if (Boolean.FALSE.equals(p.getResult())) {
                                XposedBridge.log("SoundEffectsMenuFix: OEM rejected user mode=" + value);
                                return;
                            }
                            Context c = (Context) XposedHelpers.callMethod(p.thisObject, "getContext");
                            int mode = Integer.parseInt(value);
                            if (mode == 0 || mode == 1) {
                                syncModeKeys(c, mode == 1);
                            } else {
                                // Spatial Audio is owned by the stock
                                // SpatialAudioPresenter/Spatializer path. Keep
                                // Dolby's backing engine state untouched and
                                // persist only the outer menu selection.
                                putInt(c, "system_effect_profile", 3);
                            }
                            forceFragmentMode(p.thisObject, mode);
                            XposedHelpers.callMethod(p.thisObject, "updateSoundEffectsMenu");
                            XposedBridge.log("SoundEffectsMenuFix: user mode=" + value
                                    + " dolbyContentVisible=" + (mode == 1));
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: preference hook failed " + t);
        }
        try {
            Class<?> model = XposedHelpers.findClass("com.oplus.partners.dolby.DolbyModel", lp.classLoader);
            XposedHelpers.findAndHookMethod(FRAGMENT, lp.classLoader, "updateView",
                    model, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Context c = (Context) XposedHelpers.callMethod(p.thisObject, "getContext");
                            int mode = resolveMode(p.thisObject, c);
                            if (mode != 1 || !isDolbyViewReady(p.thisObject)) {
                                // The OEM callback is asynchronous. In Spatial
                                // mode (or before the Dolby Preference has
                                // bound its ViewStub) its unconditional GEQ
                                // refresh crashes Settings. The stock model is
                                // retained and refreshed once the Dolby view is
                                // actually selected and bound.
                                forceFragmentMode(p.thisObject, mode);
                                XposedHelpers.callMethod(p.thisObject, "updateSoundEffectsMenu");
                                if (mode == 1) scheduleDolbyRefresh(p.thisObject, 0);
                                p.setResult(null);
                            }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Context c = (Context) XposedHelpers.callMethod(p.thisObject, "getContext");
                            int mode = resolveMode(p.thisObject, c);
                            forceFragmentMode(p.thisObject, mode);
                            XposedHelpers.callMethod(p.thisObject, "updateSoundEffectsMenu");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: model callback hook failed " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(DOLBY_OBSERVER, lp.classLoader, "onChange",
                    boolean.class, Uri.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Object fragment = XposedHelpers.getObjectField(p.thisObject, "this$0");
                            Context c = (Context) XposedHelpers.callMethod(fragment, "getContext");
                            if (c == null) return;
                            int enabled = Settings.System.getInt(c.getContentResolver(), "system_dolby", 0);
                            putSecureInt(c, "dolby_switch", enabled != 0 ? 1 : 0);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: secure observer hook failed " + t);
        }
    }

    private static boolean isDolbyViewReady(Object fragment) {
        try {
            Object preference = XposedHelpers.getObjectField(fragment, "mDolbyPreference");
            return preference != null
                    && XposedHelpers.getObjectField(preference, "music_equalizer_custom_view_stub") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void scheduleDolbyRefresh(final Object fragment, final int attempt) {
        if (fragment == null || dolbyRefreshScheduled || attempt >= 10) return;
        Handler handler = getMainHandler();
        if (handler == null) return;
        dolbyRefreshScheduled = true;
        boolean posted = handler.postDelayed(new Runnable() {
            @Override public void run() {
                dolbyRefreshScheduled = false;
                try {
                    Context c = (Context) XposedHelpers.callMethod(fragment, "getContext");
                    if (resolveMode(fragment, c) != 1) return;
                    if (!isDolbyViewReady(fragment)) {
                        scheduleDolbyRefresh(fragment, attempt + 1);
                        return;
                    }
                    Object controller = XposedHelpers.getObjectField(fragment, "mDolbyController");
                    if (controller != null) {
                        XposedHelpers.callMethod(controller, "tryUpdateViewContent", 0);
                        XposedBridge.log("SoundEffectsMenuFix: delayed Dolby view refresh completed");
                    }
                } catch (Throwable t) {
                    XposedBridge.log("SoundEffectsMenuFix: delayed Dolby view refresh failed " + t);
                }
            }
        }, 300L);
        if (!posted) dolbyRefreshScheduled = false;
    }

    private static void syncModeKeys(Context c, boolean enabled) {
        putInt(c, "system_dolby", enabled ? 1 : 0);
        putInt(c, "system_effect_profile", enabled ? 1 : 0);
        putSecureInt(c, "dolby_switch", enabled ? 1 : 0);
    }

    private static int resolveMode(Object fragment, Context c) {
        if (fragment == null || c == null) return 0;
        try {
            Object mapObject = XposedHelpers.getObjectField(fragment, "mSoundEffectValueToTitleMap");
            boolean hasSpatial = mapObject instanceof java.util.Map
                    && ((java.util.Map<?, ?>) mapObject).containsKey("3");
            int current = XposedHelpers.getIntField(fragment, "mCurrentMode");
            boolean spatialOpen = false;
            try {
                spatialOpen = Boolean.TRUE.equals(XposedHelpers.callMethod(fragment, "isSpatialAudioOpen"));
            } catch (Throwable ignored) {}
            // MSS sound-field expansion also uses the platform spatializer, so
            // isSpatialAudioOpen() alone cannot identify the outer "Spatial
            // Audio" mode. Mirror the OEM Settings priority: while MSS is
            // active/available, mode 3 is invalid and the prior Dolby state is
            // retained. This also repairs stale conflicts persisted by older
            // module versions after the page is resumed.
            if (isMssSpatialRenderBlocking(fragment, c)) {
                int fallback = Settings.System.getInt(
                        c.getContentResolver(), "system_dolby", 0) != 0 ? 1 : 0;
                int profile = Settings.System.getInt(
                        c.getContentResolver(), "system_effect_profile", fallback);
                if (current == 3 || profile == 3 || spatialOpen) {
                    putInt(c, "system_effect_profile", fallback);
                    XposedBridge.log("SoundEffectsMenuFix: repaired Spatial/MSS conflict, mode="
                            + fallback);
                }
                return fallback;
            }
            if (hasSpatial && (spatialOpen || current == 3)) return 3;
            return Settings.System.getInt(c.getContentResolver(), "system_dolby", 0) != 0 ? 1 : 0;
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: resolve mode failed " + t);
            return Settings.System.getInt(c.getContentResolver(), "system_dolby", 0) != 0 ? 1 : 0;
        }
    }

    private static boolean isMssSpatialRenderBlocking(Object fragment, Context c) {
        try {
            Object model = XposedHelpers.getStaticObjectField(
                    fragment.getClass(), "mMssSpatialRenderModel");
            if (model != null) {
                boolean enabled = Boolean.TRUE.equals(XposedHelpers.callMethod(model, "isEnabled"));
                boolean mssEnabled = Boolean.TRUE.equals(
                        XposedHelpers.callMethod(model, "isMssEnabled"));
                return enabled || mssEnabled;
            }
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: MSS model query failed " + t);
        }
        // During the first fragment pass the OEM service/model can still be
        // unbound. Its persisted Secure state is the safest fallback and is
        // the same key written by MssSpatialRenderModel.setEnabled().
        try {
            return c != null && Settings.Secure.getInt(
                    c.getContentResolver(), "mss_spatial_render_switch", 0) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void forceFragmentMode(Object fragment, int mode) {
        if (fragment == null) return;
        try {
            boolean dolbyVisible = mode == 1;
            XposedHelpers.setIntField(fragment, "mCurrentMode", mode);
            Object preference = XposedHelpers.getObjectField(fragment, "mDolbyPreference");
            if (preference != null) {
                XposedHelpers.callMethod(preference, "showContent", dolbyVisible);
                XposedHelpers.callMethod(preference, "setVisible", dolbyVisible);
            }
            Object category = XposedHelpers.getObjectField(fragment, "mDolbyPreferenceCategory");
            if (category != null) XposedHelpers.callMethod(category, "setVisible", dolbyVisible);
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: content visibility sync failed " + t);
        }
    }

    private static void keepBridgeAlive(Context context) {
        if (context == null || keeperBound) return;
        Context app = context.getApplicationContext();
        keeperContext = app != null ? app : context;
        try {
            Intent i = new Intent().setComponent(new ComponentName(
                    "com.aclaniakea.colorosostatsguard",
                    "com.aclaniakea.dolbybridge.DolbyBridgeService"));
            // PackageManager can expose the updated Hook package a few
            // seconds after Settings starts during cold boot. Explicitly
            // start the component and retry the bind instead of losing the
            // only Application.attach attempt to that transient window.
            try { keeperContext.startService(i); } catch (Throwable ignored) {}
            keeperBound = keeperContext.bindService(i, KEEPER, Context.BIND_AUTO_CREATE);
            XposedBridge.log("SoundEffectsMenuFix: persistent bridge bind=" + keeperBound);
            if (!keeperBound) scheduleBridgeRetry();
        } catch (Throwable t) {
            XposedBridge.log("SoundEffectsMenuFix: persistent bridge bind failed " + t);
            scheduleBridgeRetry();
        }
    }

    private static void scheduleBridgeRetry() {
        if (keeperBound || keeperRetryScheduled || keeperContext == null || keeperAttempts >= 20) return;
        Handler handler = getMainHandler();
        if (handler == null) return;
        keeperRetryScheduled = true;
        boolean posted = handler.postDelayed(new Runnable() {
            @Override public void run() {
                keeperRetryScheduled = false;
                keeperAttempts++;
                keepBridgeAlive(keeperContext);
            }
        }, 1500L);
        if (!posted) keeperRetryScheduled = false;
    }

    private static Handler getMainHandler() {
        Handler handler = mainHandler;
        if (handler != null) return handler;
        Looper looper = Looper.getMainLooper();
        if (looper == null) return null;
        synchronized (SoundEffectsMenuFix.class) {
            if (mainHandler == null) mainHandler = new Handler(looper);
            return mainHandler;
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
                            syncModeKeys(c, on);
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

    private static void putSecureInt(Context c, String key, int value) {
        if (c == null) return;
        try {
            // Avoid recursively waking DolbyStateObserver when the canonical
            // value is already present. Some ColorOS providers notify even
            // when putInt writes an unchanged value.
            int current = Settings.Secure.getInt(c.getContentResolver(), key, Integer.MIN_VALUE);
            if (current != value) Settings.Secure.putInt(c.getContentResolver(), key, value);
        }
        catch (Throwable t) { XposedBridge.log("OEM putSecureInt failed " + key + ": " + t); }
    }

}
