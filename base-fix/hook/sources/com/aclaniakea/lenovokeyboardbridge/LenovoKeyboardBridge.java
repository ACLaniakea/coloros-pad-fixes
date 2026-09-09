package com.aclaniakea.lenovokeyboardbridge;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Lenovo Keyboard Pack (17ef:6271) missing-key bridge.
 *
 * The keyboard reports four Lenovo-private consumer usages.  The port had no
 * Vendor_17EF_Product_6271.kl, so InputReader exposed every one as UNKNOWN.
 * The module overlay maps them to MUTE/F13/F14/F15.  This class consumes only
 * F13..F15 from this exact keyboard and uses the already-present OPlus window
 * managers; no shell command, polling daemon, or fake window implementation.
 */
public final class LenovoKeyboardBridge implements IXposedHookLoadPackage {
    private static final String TAG = "LenovoKeyboardBridge";
    private static final String SETTINGS = "com.android.settings";
    private static final int LENOVO_VENDOR = 0x17ef;
    private static final int KEYBOARD_PRODUCT = 0x6271;
    // Use Android/OPlus policy-visible consumer keys.  F13..F15 are legal
    // layout targets but ColorOS drops them before its gesture controller, so
    // a framework bridge can never see the physical press.
    // ALL_APPS and APP_SWITCH are consumed by Launcher/SystemUI before the
    // window policy can complete the OPlus operation.  These two are inert
    // policy-visible consumer keys on ColorOS and retain the private mapping.
    private static final int KEY_SMALL_WINDOW = KeyEvent.KEYCODE_VOICE_ASSIST;
    private static final int KEY_SPLIT_SCREEN = KeyEvent.KEYCODE_HELP;
    private static final int KEY_AI = KeyEvent.KEYCODE_ASSIST;
    private static final String AI_PACKAGE = "aclaniakea_lenovo_keyboard_ai_package";
    private static final String AI_ACTION = "aclaniakea_lenovo_keyboard_ai_action";
    private static final String AI_SHOW_SYSTEM_APPS = "aclaniakea_lenovo_keyboard_show_system_apps";
    private static final String AI_PREF_KEY = "aclaniakea_lenovo_keyboard_ai_key";
    private static final String CUSTOM_PAGE_EXTRA = "aclaniakea_lenovo_keyboard_custom_page";
    // Reuse the stock ColorOS SubSettings host for the pen page as well.  The
    // renderer remains in Settings, so card grouping, switches and mark rows
    // are all real COUI widgets rather than a module-owned dialog.
    private static final String PEN_HAPTIC_PAGE_EXTRA = "aclaniakea_lenovo_pen_haptic_page";
    private static final String PEN_HAPTIC_ENABLED = "lenovo_pen_global_writing_haptic";
    private static final String PEN_HAPTIC_PATTERN = "lenovo_pen_global_writing_haptic_pattern";
    private static final String CUSTOM_PAGE_MODE = "aclaniakea_lenovo_keyboard_page_mode";
    private static final String PAGE_MODE_APP_PICKER = "app_picker";
    // Marks an otherwise stock ManageApplications instance as the independent
    // custom-key picker.  Normal Settings > 应用管理 is untouched.
    private static final String APP_MANAGEMENT_PICKER_EXTRA =
            "aclaniakea_lenovo_keyboard_app_management_picker";
    private static final int APP_PICKER_SYSTEM_APPS_MENU_ID = 0x6ac1;
    /** The app picker currently on screen, so its list can be refiltered in
     *  place.  Rebuilding the page cannot do this: buildColorOsAppPicker()
     *  returns immediately once its root view is present. */
    private static AppPickerAdapter livePickerAdapter;
    private static android.app.Activity livePickerActivity;
    private static final String ACTION_APP = "app";
    private static final String ACTION_NOTIFICATIONS = "notifications";
    private static final String ACTION_RECENTS = "recents";
    private static final String ACTION_SMALL_WINDOW = "small_window";
    private static final String ACTION_SPLIT_SCREEN = "split_screen";
    private static final AtomicBoolean POLICY_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean SETTINGS_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean TRACKPAD_STATE_OBSERVER = new AtomicBoolean(false);
    // LSPosed classes are loaded from an in-memory module dex.  Keep the
    // original system_server loader for OPlus server-private services.
    private static volatile ClassLoader SYSTEM_SERVER_LOADER;
    // A ColorOS key travels through more than one policy façade.  Dispatching
    // is also hooked as a fallback, so make one physical DOWN invoke one action.
    private static final AtomicLong LAST_HANDLED_EVENT = new AtomicLong(Long.MIN_VALUE);

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if ("android".equals(lpp.packageName) && "android".equals(lpp.processName)) {
            hookInputPolicy(lpp.classLoader);
        } else if (SETTINGS.equals(lpp.packageName) && lpp.processName != null
                && lpp.processName.startsWith(SETTINGS)) {
            hookKeyboardSettings(lpp.classLoader);
        }
    }

    private static void hookInputPolicy(ClassLoader loader) {
        if (!POLICY_HOOKED.compareAndSet(false, true)) return;
        SYSTEM_SERVER_LOADER = loader;
        hookTrackpadInputFilter(loader);
        // PhoneWindowManager is the live policy path used by InputDispatcher.
        // Some OPlus branches keep a similarly named InputPolicy helper, but
        // that helper is not necessarily in the dispatch path. Prefer the
        // framework policy and retain the helper only as a compatibility
        // fallback for vendor branches that do not expose it.
        final String[] policyClasses = new String[] {
                "com.android.server.input.KeyGestureController",
                "com.android.server.policy.SubPhoneWindowManager",
                "com.android.server.policy.InputPolicy",
                "com.android.server.policy.PhoneWindowManager",
                "com.android.server.policy.OplusPhoneWindowManager"
        };
        boolean installed = false;
        for (String name : policyClasses) {
            try {
                Class<?> policy = XposedHelpers.findClass(name, loader);
                boolean queueing = hookKeyInterception(policy);
                boolean dispatching = hookKeyDispatching(policy);
                if (queueing || dispatching) {
                    XposedBridge.log(TAG + ": input bridge installed in " + name);
                    installed = true;
                }
            } catch (Throwable ignored) {
                // Absent on this ColorOS branch; continue to the next class.
            }
        }
        if (!installed) XposedBridge.log(TAG + ": no live input-policy interception method found");
    }

    /**
     * ColorOS's tpEnable is only consumed by the OPlus Bluetooth keyboard
     * stack.  The Lenovo cover is a direct external touchpad, so the stock
     * UI changed its state but InputReader continued dispatching events.
     *
     * Reuse the already-installed OPlus InputManager filter callback instead
     * of a user-space reader/daemon.  Returning true here is the framework's
     * documented "intercept this event" result; it drops only MotionEvents
     * from the exact Lenovo touchpad while tpEnable is off.
     */
    private static void hookTrackpadInputFilter(ClassLoader loader) {
        boolean liveFilterInstalled = false;
        // InputManagerService.filterInputEvent is the InputDispatcher's real
        // Java filtering path.  The OPlus intermittent filter below is only
        // an auxiliary jitter/gesture filter and is not invoked for a wired
        // HID touchpad, which is why the Settings switch previously changed
        // state without stopping Lenovo's pointer events.
        try {
            Class<?> service = XposedHelpers.findClass(
                    "com.android.server.input.InputManagerService", loader);
            XposedHelpers.findAndHookMethod(service, "filterInputEvent",
                    InputEvent.class, Integer.TYPE, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            InputEvent event = (InputEvent) hook.args[0];
                            if (isLenovoTouchpadEvent(event) && !isTrackpadEnabled(hook.thisObject)) {
                                // true is the framework contract for "event was
                                // handled"; InputDispatcher will not deliver it.
                                hook.setResult(Boolean.TRUE);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Lenovo touchpad master-switch filter installed in InputManagerService");
            liveFilterInstalled = true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": primary touchpad input filter hook failed");
            XposedBridge.log(t);
        }
        try {
            Class<?> ext = XposedHelpers.findClass(
                    "com.android.server.input.InputManagerServiceExtImpl", loader);
            XposedHelpers.findAndHookMethod(ext, "isNeedIntermittentIntercept", InputEvent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            InputEvent event = (InputEvent) hook.args[0];
                            if (!isLenovoTouchpadEvent(event)) return;
                            if (!isTrackpadEnabled(hook.thisObject)) {
                                hook.setResult(Boolean.TRUE);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Lenovo touchpad intermittent-filter compatibility hook installed"
                    + (liveFilterInstalled ? "" : " (primary unavailable)"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": touchpad input filter hook failed");
            XposedBridge.log(t);
        }
        installTrackpadDeviceStateBridge(loader);
    }

    /**
     * A wired HID touchpad is consumed in native InputReader before the Java
     * filter callback is even requested unless a global InputFilter is active.
     * Use the framework's own enable/disableInputDevice API instead.  It is
     * event-driven from the same Global setting the ColorOS switch writes and
     * makes InputReader expose the device as Enabled: false while off.
     */
    private static void installTrackpadDeviceStateBridge(ClassLoader loader) {
        try {
            final Class<?> service = XposedHelpers.findClass(
                    "com.android.server.input.InputManagerService", loader);
            XposedHelpers.findAndHookMethod(service, "systemRunning", new XC_MethodHook() {
                @Override protected void afterHookedMethod(final MethodHookParam hook) {
                    final Object inputService = hook.thisObject;
                    final Context context = (Context) XposedHelpers.getObjectField(inputService, "mContext");
                    if (context == null) return;
                    if (TRACKPAD_STATE_OBSERVER.compareAndSet(false, true)) {
                        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                            @Override public void onChange(boolean selfChange) {
                                applyLenovoTrackpadState(inputService, context);
                            }
                        };
                        context.getContentResolver().registerContentObserver(
                                Settings.Global.getUriFor("tpEnable"), false, observer);
                        XposedBridge.log(TAG + ": trackpad state observer registered");
                    }
                    applyLenovoTrackpadState(inputService, context);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": trackpad device-state bridge failed");
            XposedBridge.log(t);
        }
    }

    private static void applyLenovoTrackpadState(Object inputService, Context context) {
        if (inputService == null || context == null) return;
        final boolean enabled = Settings.Global.getInt(context.getContentResolver(), "tpEnable", 1) != 0;
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null || device.getVendorId() != LENOVO_VENDOR
                        || device.getProductId() != KEYBOARD_PRODUCT
                        || (device.getSources() & InputDevice.SOURCE_TOUCHPAD)
                        != InputDevice.SOURCE_TOUCHPAD) continue;
                XposedHelpers.callMethod(inputService,
                        enabled ? "enableInputDevice" : "disableInputDevice", id);
                XposedBridge.log(TAG + ": Lenovo touchpad device id=" + id
                        + " enabled=" + enabled);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": apply touchpad state failed");
            XposedBridge.log(t);
        }
    }

    private static boolean isLenovoTouchpadEvent(InputEvent event) {
        if (event == null) return false;
        try {
            InputDevice device = InputDevice.getDevice(event.getDeviceId());
            return device != null && device.getVendorId() == LENOVO_VENDOR
                    && device.getProductId() == KEYBOARD_PRODUCT
                    && (device.getSources() & InputDevice.SOURCE_TOUCHPAD)
                    == InputDevice.SOURCE_TOUCHPAD;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTrackpadEnabled(Object inputServiceExt) {
        try {
            // Both InputManagerService and its OPlus extension own this field.
            Context context = (Context) XposedHelpers.getObjectField(inputServiceExt, "mContext");
            return Settings.Global.getInt(context.getContentResolver(), "tpEnable", 1) != 0;
        } catch (Throwable ignored) {
            // Never disable the user's physical input on an unexpected OEM
            // branch merely because a private field changed its name.
            return true;
        }
    }

    private static boolean hookKeyInterception(final Class<?> policy) {
        try {
            for (Method method : policy.getDeclaredMethods()) {
                if (!"interceptKeyBeforeQueueing".equals(method.getName())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length < 2 || p[0] != KeyEvent.class) continue;
                final Class<?> resultType = method.getReturnType();
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam hook) {
                        KeyEvent event = (KeyEvent) hook.args[0];
                        if (!isTargetKeyboard(event)) return;
                        final int code = event.getKeyCode();
                        if (code != KEY_SMALL_WINDOW && code != KEY_SPLIT_SCREEN && code != KEY_AI) return;
                        // Voice assist is launched by the framework on KEY_UP,
                        // after our action has already run on KEY_DOWN.  Consume
                        // both edges (and repeats) from this exact keyboard.
                        hook.setResult(consumeResult(resultType));
                        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return;
                        if (code == KEY_SMALL_WINDOW || code == KEY_SPLIT_SCREEN || code == KEY_AI) {
                            XposedBridge.log(TAG + ": received code=" + code
                                    + " device=" + event.getDeviceId());
                        }
                        // Every OPlus policy façade must consume the physical
                        // key.  Only the action itself is debounced.  Returning
                        // before consumption here let a later façade launch the
                        // stock VOICE_ASSIST handler on the home screen.
                        if (alreadyHandled(event)) return;
                        final Object policyObject = hook.thisObject;
                        postAsync(new Runnable() {
                            @Override public void run() {
                                // Some OPlus policy facades intentionally have no Context.
                                // The key must still be consumed; resolve system_server's
                                // canonical context only when executing the OEM operation.
                                Context context = policyContext(policyObject);
                                if (context == null) {
                                    XposedBridge.log(TAG + ": no system context for code=" + code);
                                    return;
                                }
                                XposedBridge.log(TAG + ": handling code=" + code);
                                if (code == KEY_SMALL_WINDOW) startZoomForTopApp(context);
                                else if (code == KEY_SPLIT_SCREEN) startSplitForTopApp();
                                else runConfiguredCustomKey(context);
                            }
                        });
                    }
                });
                return true;
            }
            return false;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": input-policy hook failed for " + policy.getName());
            XposedBridge.log(t);
            return false;
        }
    }

    /**
     * Some ColorOS branches delegate the queueing callback through an OEM
     * façade before the normal PhoneWindowManager body.  The dispatch callback
     * remains the last common point.  It is a fallback only: a queueing hook
     * wins and the event-time guard prevents double actions.
     */
    private static boolean hookKeyDispatching(final Class<?> policy) {
        try {
            for (Method method : policy.getDeclaredMethods()) {
                if (!"interceptKeyBeforeDispatching".equals(method.getName())) continue;
                Class<?>[] p = method.getParameterTypes();
                int eventIndex = -1;
                for (int i = 0; i < p.length; i++) {
                    if (p[i] == KeyEvent.class) { eventIndex = i; break; }
                }
                if (eventIndex < 0) continue;
                final int keyEventArg = eventIndex;
                final Class<?> resultType = method.getReturnType();
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam hook) {
                        KeyEvent event = (KeyEvent) hook.args[keyEventArg];
                        if (!isTargetKeyboard(event)) return;
                        int code = event.getKeyCode();
                        if (code != KEY_SMALL_WINDOW && code != KEY_SPLIT_SCREEN && code != KEY_AI) return;
                        hook.setResult(consumeResult(resultType));
                        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return;
                        XposedBridge.log(TAG + ": dispatch fallback received code=" + code
                                + " device=" + event.getDeviceId());
                        // Dispatching is the final policy point.  Consume here as
                        // well so VOICE_ASSIST cannot launch the stock assistant
                        // when a vendor façade does not expose mContext.
                        if (alreadyHandled(event)) return;
                        final Object policyObject = hook.thisObject;
                        postAsync(new Runnable() {
                            @Override public void run() {
                                Context context = policyContext(policyObject);
                                if (context == null) {
                                    XposedBridge.log(TAG + ": no system context for dispatch code=" + code);
                                    return;
                                }
                                XposedBridge.log(TAG + ": handling dispatch code=" + code);
                                if (code == KEY_SMALL_WINDOW) startZoomForTopApp(context);
                                else if (code == KEY_SPLIT_SCREEN) startSplitForTopApp();
                                else runConfiguredCustomKey(context);
                            }
                        });
                    }
                });
                return true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": input-policy dispatch hook failed for " + policy.getName());
            XposedBridge.log(t);
        }
        return false;
    }

    private static Object consumeResult(Class<?> resultType) {
        if (resultType == Boolean.TYPE || resultType == Boolean.class) return Boolean.TRUE;
        if (resultType == Long.TYPE || resultType == Long.class) return Long.valueOf(-1L);
        if (resultType == Integer.TYPE || resultType == Integer.class) return Integer.valueOf(0);
        return null;
    }

    private static boolean alreadyHandled(KeyEvent event) {
        // The same physical HID report traverses KeyGestureController and
        // three OPlus policy façades. They construct distinct KeyEvent
        // objects with slightly different eventTime values, so use a short
        // real-time debounce keyed by the code instead of object timestamp.
        long now = SystemClock.uptimeMillis();
        long marker = (now << 10) | (event.getKeyCode() & 0x3ffL);
        long previous = LAST_HANDLED_EVENT.getAndSet(marker);
        return (previous & 0x3ffL) == (marker & 0x3ffL)
                && ((marker >>> 10) - (previous >>> 10)) < 350L;
    }

    /**
     * LSPosed loads modules before the target process has necessarily prepared
     * its main Looper.  Do not create a Handler in a static initializer: that
     * made Settings fail to load this entire bridge.  InputPolicy calls happen
     * after boot, so normally use the main queue; retain a short-lived worker
     * fallback for the very early edge case.
     */
    private static void postAsync(final Runnable action) {
        Looper looper = Looper.getMainLooper();
        if (looper != null) {
            new Handler(looper).post(action);
        } else {
            new Thread(action, "LenovoKeyboardBridge").start();
        }
    }

    private static void postDelayed(final Runnable action, long delayMillis) {
        Looper looper = Looper.getMainLooper();
        if (looper != null) {
            new Handler(looper).postDelayed(action, delayMillis);
        } else {
            postAsync(action);
        }
    }

    private static boolean isTargetKeyboard(KeyEvent event) {
        try {
            InputDevice d = InputDevice.getDevice(event.getDeviceId());
            if (d == null) return false;
            if (d.getVendorId() == LENOVO_VENDOR && d.getProductId() == KEYBOARD_PRODUCT) return true;
            // The uinput bridge identity is fixed above; retaining the name
            // fallback covers ColorOS builds that do not copy USB ids to a
            // synthesized keyboard InputDevice.
            String name = d.getName();
            return name != null && name.startsWith("Lenovo Keyboard Pack Private Key Bridge");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Context policyContext(Object policy) {
        try {
            Object context = XposedHelpers.getObjectField(policy, "mContext");
            if (context instanceof Context) return (Context) context;
        } catch (Throwable ignored) { }
        try {
            // KeyGestureController and several OPlus policy facades do not
            // own mContext.  They still run inside system_server, where this
            // is the canonical Context used by the original window services.
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            Object context = thread == null ? null
                    : threadClass.getMethod("getSystemContext").invoke(thread);
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> systemServerClass(String name) throws ClassNotFoundException {
        ClassLoader loader = SYSTEM_SERVER_LOADER;
        if (loader != null) return Class.forName(name, false, loader);
        return Class.forName(name, false, ClassLoader.getSystemClassLoader());
    }

    /**
     * Toggle the focused task through ColorOS' task-level flexible-window API.
     *
     * This deliberately does not use startZoomWindow(Intent): that method is
     * for launching a new activity in Zoom mode and can re-deliver an intent
     * to the app.  toggleFlexibleWindowByApp is the OEM implementation for an
     * existing task, and accepts a null activity token when a task id is known.
     */
    private static void startZoomForTopApp(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am == null ? null : am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return;
            ActivityManager.RunningTaskInfo top = tasks.get(0);
            if (isHomeTask(top)) {
                // The stock key has no window action on Launcher.  It must
                // nevertheless remain consumed so it cannot invoke Voice
                // Assistant through the fallback consumer-key mapping.
                XposedBridge.log(TAG + ": skip flexible toggle for home task=" + top.taskId);
                return;
            }
            int windowMode = taskWindowingMode(top);
            // This ColorOS branch deliberately reports flexible tasks as
            // windowingMode=fullscreen.  Their bounds remain smaller than the
            // display's max bounds and are the authoritative state bit used by
            // FlexibleTaskController for the return-to-fullscreen transition.
            boolean alreadyFlexible = isFlexibleTask(top, windowMode);
            Class<?> atm = systemServerClass("android.app.OplusActivityTaskManager");
            Object instance = atm.getMethod("getInstance").invoke(null);
            Method toggle = atm.getMethod("toggleFlexibleWindow", android.os.IBinder.class,
                    Integer.TYPE, Boolean.TYPE, Boolean.TYPE);
            Object result = toggle.invoke(instance, null, Integer.valueOf(top.taskId),
                    Boolean.TRUE, Boolean.valueOf(!alreadyFlexible));
            XposedBridge.log(TAG + ": OEM flexible toggle task=" + top.taskId
                    + " mode=" + windowMode + " enter=" + (!alreadyFlexible)
                    + " result=" + result);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": small window failed");
            XposedBridge.log(t);
        }
    }

    private static int taskWindowingMode(ActivityManager.RunningTaskInfo task) {
        try {
            // TaskInfo.configuration is hidden from the public SDK stubs but
            // is present on this framework; reflect it for build portability.
            Object config = XposedHelpers.getObjectField(task, "configuration");
            Object windowConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
            Object mode = XposedHelpers.callMethod(windowConfig, "getWindowingMode");
            return mode instanceof Integer ? ((Integer) mode).intValue() : 1;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static boolean isFlexibleTask(ActivityManager.RunningTaskInfo task, int windowMode) {
        if (windowMode != 0 && windowMode != 1) return true;
        try {
            Object config = XposedHelpers.getObjectField(task, "configuration");
            Object windowConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
            Object bounds = XposedHelpers.callMethod(windowConfig, "getBounds");
            Object maxBounds = XposedHelpers.callMethod(windowConfig, "getMaxBounds");
            if (bounds instanceof android.graphics.Rect && maxBounds instanceof android.graphics.Rect) {
                android.graphics.Rect b = (android.graphics.Rect) bounds;
                android.graphics.Rect max = (android.graphics.Rect) maxBounds;
                return !b.isEmpty() && !max.isEmpty() && !b.equals(max);
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean isHomeTask(ActivityManager.RunningTaskInfo task) {
        try {
            if (task.topActivity != null && "com.android.launcher".equals(task.topActivity.getPackageName())) {
                return true;
            }
            return task.baseActivity != null && "com.android.launcher".equals(task.baseActivity.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Matches the stock manager's own startSplitScreen(): splitScreenForTopApp(4). */
    private static void startSplitForTopApp() {
        try {
            Class<?> service = systemServerClass("com.android.server.wm.OplusSplitScreenManagerService");
            Object instance = service.getMethod("getInstance").invoke(null);
            service.getMethod("splitScreenForTopApp", Integer.TYPE).invoke(instance, Integer.valueOf(4));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": split screen failed");
            XposedBridge.log(t);
        }
    }

    private static void runConfiguredCustomKey(Context context) {
        String action = Settings.Secure.getString(context.getContentResolver(), AI_ACTION);
        // Keep configurations written by the first build compatible.
        if (action == null || action.length() == 0) action = ACTION_APP;
        if (ACTION_NOTIFICATIONS.equals(action)) {
            invokeStatusBar(context, "expandNotificationsPanel");
        } else if (ACTION_RECENTS.equals(action)) {
            invokeStatusBar(context, "toggleRecentApps");
        } else if (ACTION_SMALL_WINDOW.equals(action)) {
            startZoomForTopApp(context);
        } else if (ACTION_SPLIT_SCREEN.equals(action)) {
            startSplitForTopApp();
        } else if (ACTION_APP.equals(action)) {
            launchConfiguredAiApp(context);
        }
    }

    /** Calls the platform StatusBar service from system_server, never an adb/shell surrogate. */
    private static void invokeStatusBar(Context context, String name) {
        try {
            Object statusBar = context.getSystemService("statusbar");
            if (statusBar == null) return;
            Method method = statusBar.getClass().getMethod(name);
            method.invoke(statusBar);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": status bar action failed: " + name);
            XposedBridge.log(t);
        }
    }

    private static void launchConfiguredAiApp(Context context) {
        try {
            String pkg = Settings.Secure.getString(context.getContentResolver(), AI_PACKAGE);
            if (pkg == null || pkg.length() == 0) return;
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // startActivityAsUser is @hide in the SDK stubs but present on the
            // system-server Context.  Reflect it to retain the foreground user
            // semantics without baking a hidden API into the APK.
            try {
                Class<?> userHandle = Class.forName("android.os.UserHandle");
                Object user = userHandle.getField("CURRENT").get(null);
                Method start = context.getClass().getMethod("startActivityAsUser", Intent.class, userHandle);
                start.invoke(context, launch, user);
            } catch (Throwable hiddenApiUnavailable) {
                context.startActivity(launch);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AI app launch failed");
            XposedBridge.log(t);
        }
    }

    private static int currentUserId() {
        try {
            Method m = ActivityManager.class.getDeclaredMethod("getCurrentUser");
            Object user = m.invoke(null);
            return user instanceof Integer ? ((Integer) user).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }


    private static void hookKeyboardSettings(ClassLoader loader) {
        if (!SETTINGS_HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.oplus.settings.feature.othersettings.input.OplusPhysicalKeyboardFragment", loader);
            XposedHelpers.findAndHookMethod(fragment, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    addAiPreference(hook.thisObject, loader);
                }
            });
            // The ColorOS fragment populates its XML hierarchy after onCreate.
            // Re-run when it is visible so a row provisionally added during
            // creation is moved into the real keyboard PreferenceCategory.
            hookInheritedResume(fragment, loader);
            hookColorOsCustomKeyPage(loader);
            hookLenovoTrackpadSettings(loader);
            XposedBridge.log(TAG + ": Settings AI-key preference hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Settings hook failed");
            XposedBridge.log(t);
        }
    }

    /**
     * ColorOS queries only its own 0x22d9 Bluetooth keyboard provider before
     * enabling the trackpad page.  The Lenovo cover is a genuine external
     * touchpad (17ef:6271) but cannot answer that proprietary firmware RPC.
     * Preserve the stock test for every other device and report SUPPORTED only
     * while this exact detected hardware is attached.
     */
    private static void hookLenovoTrackpadSettings(final ClassLoader loader) {
        try {
            final Class<?> capability = XposedHelpers.findClass(
                    "com.oplus.settings.feature.othersettings.trackpad.TrackpadCapabilityUtils", loader);
            XposedHelpers.findAndHookMethod(capability, "checkTouchpadFeature", Context.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (!hasLenovoTouchpad()) return;
                            try {
                                Class<?> status = XposedHelpers.findClass(
                                        "com.oplus.settings.feature.othersettings.trackpad."
                                                + "TrackpadCapabilityUtils$CapabilityStatus", loader);
                                hook.setResult(XposedHelpers.getStaticObjectField(status, "SUPPORTED"));
                            } catch (Throwable ignored) { }
                        }
                    });
            Class<?> page = XposedHelpers.findClass(
                    "com.oplus.settings.feature.othersettings.trackpad.TrackpadSettingsFragment", loader);
            XposedHelpers.findAndHookMethod(page, "isOfficialKeyboard", InputDevice.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            InputDevice device = (InputDevice) hook.args[0];
                            if (isLenovoKeyboard(device)) hook.setResult(Boolean.TRUE);
                        }
                    });
            XposedBridge.log(TAG + ": Lenovo detachable-trackpad capability hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Lenovo trackpad Settings hook failed");
            XposedBridge.log(t);
        }
    }

    private static boolean hasLenovoTouchpad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.getVendorId() == LENOVO_VENDOR
                    && device.getProductId() == KEYBOARD_PRODUCT
                    && (device.getSources() & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLenovoKeyboard(InputDevice device) {
        return device != null && device.getVendorId() == LENOVO_VENDOR
                && device.getProductId() == KEYBOARD_PRODUCT;
    }

    private static void hookColorOsCustomKeyPage(final ClassLoader loader) {
        try {
            Class<?> host = XposedHelpers.findClass(
                    "com.oplus.settings.feature.othersettings.input.OplusKeyboardHabitFragment", loader);
            // This fragment inherits onCreate rather than overriding it. Its
            // preference XML is complete by onResume, which is also the right
            // point to refresh the selected action after returning from the
            // app picker.
            hookCustomPageResume(host, loader);
            hookCustomAppPickerOverflow(loader);
            XposedBridge.log(TAG + ": ColorOS custom-key subpage hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ColorOS custom-key subpage hook failed");
            XposedBridge.log(t);
        }
    }

    /**
     * The app picker stays an independent custom-key page.  Its system-app
     * toggle belongs in the real Settings action-bar overflow, like other
     * ColorOS pickers, rather than being drawn over the search field.
     */
    private static void hookCustomAppPickerOverflow(final ClassLoader loader) {
        try {
            final Class<?> activity = XposedHelpers.findClass("com.android.settings.SubSettings", loader);
            XC_MethodHook createMenuHook = new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            android.app.Activity host = (android.app.Activity) hook.thisObject;
                            if (!isAppPickerPage(host)) return;
                            android.view.Menu menu = (android.view.Menu) hook.args[0];
                            if (menu.findItem(APP_PICKER_SYSTEM_APPS_MENU_ID) != null) return;
                            android.view.MenuItem item = menu.add(android.view.Menu.NONE,
                                    APP_PICKER_SYSTEM_APPS_MENU_ID, android.view.Menu.NONE,
                                    "显示系统应用");
                            item.setCheckable(true);
                            item.setChecked(Settings.Secure.getInt(host.getContentResolver(),
                                    AI_SHOW_SYSTEM_APPS, 0) != 0);
                            item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
                            XposedBridge.log(TAG + ": custom app-picker overflow added");
                        }
                    };
            XC_MethodHook itemSelectedHook = new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            android.app.Activity host = (android.app.Activity) hook.thisObject;
                            if (!isAppPickerPage(host)) return;
                            android.view.MenuItem item = (android.view.MenuItem) hook.args[0];
                            if (item == null || item.getItemId() != APP_PICKER_SYSTEM_APPS_MENU_ID) return;
                            boolean show = !item.isChecked();
                            Settings.Secure.putInt(host.getContentResolver(), AI_SHOW_SYSTEM_APPS,
                                    show ? 1 : 0);
                            item.setChecked(show);
                            // Refilter the list that is actually on screen.
                            // Recreating the activity looked equivalent but is
                            // not: the rebuilt fragment hits the "already
                            // built" guard in buildColorOsAppPicker(), so the
                            // old adapter stayed installed with its original
                            // flag and the list never changed.
                            AppPickerAdapter live = livePickerAdapter;
                            boolean refreshed = live != null && livePickerActivity == host;
                            if (refreshed) {
                                live.setShowSystemApps(show);
                            } else {
                                livePickerAdapter = null;
                                livePickerActivity = null;
                                host.recreate();
                            }
                            XposedBridge.log(TAG + ": custom app-picker system apps=" + show
                                    + " refreshed-in-place=" + refreshed
                                    + " items=" + (live == null ? -1 : live.getCount()));
                            hook.setResult(Boolean.TRUE);
                        }
                    };
            // SubSettings inherits both callbacks. findAndHookMethod() looks
            // only on the concrete class on this LSPosed build, so walk its
            // actual Activity hierarchy just like the existing fragment hook.
            boolean createInstalled = false;
            boolean selectInstalled = false;
            for (Class<?> type = activity; type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod("onCreateOptionsMenu", android.view.Menu.class);
                    XposedBridge.hookMethod(method, createMenuHook);
                    createInstalled = true;
                    break;
                } catch (NoSuchMethodException ignored) { }
            }
            for (Class<?> type = activity; type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod("onOptionsItemSelected", android.view.MenuItem.class);
                    XposedBridge.hookMethod(method, itemSelectedHook);
                    selectInstalled = true;
                    break;
                } catch (NoSuchMethodException ignored) { }
            }
            if (!createInstalled || !selectInstalled) {
                throw new NoSuchMethodException("SubSettings action-bar callback missing");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": custom app-picker overflow hook failed");
            XposedBridge.log(t);
        }
    }

    private static void hookCustomPageResume(final Class<?> host, final ClassLoader loader) {
        for (Class<?> type = host; type != null; type = type.getSuperclass()) {
            try {
                Method resume = type.getDeclaredMethod("onResume");
                XposedBridge.hookMethod(resume, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        if (host.isInstance(hook.thisObject)) {
                            if (isPenHapticPage(hook.thisObject)) {
                                buildColorOsPenHapticPage(hook.thisObject, loader);
                            } else if (isCustomKeyPage(hook.thisObject)) {
                                buildColorOsCustomKeyPage(hook.thisObject, loader);
                            }
                        }
                    }
                });
                return;
            } catch (NoSuchMethodException ignored) { }
        }
    }

    private static boolean isCustomKeyPage(Object fragment) {
        try {
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (!(activity instanceof android.app.Activity)) return false;
            return ((android.app.Activity) activity).getIntent()
                    .getBooleanExtra(CUSTOM_PAGE_EXTRA, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPenHapticPage(Object fragment) {
        try {
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            return activity instanceof android.app.Activity
                    && ((android.app.Activity) activity).getIntent()
                    .getBooleanExtra(PEN_HAPTIC_PAGE_EXTRA, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void buildColorOsPenHapticPage(final Object fragment, final ClassLoader loader) {
        try {
            final Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
            if (context == null) return;
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (activity instanceof android.app.Activity) ((android.app.Activity) activity).setTitle("全局书写振动");
            Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            if (screen == null) return;
            XposedHelpers.callMethod(screen, "removeAll");
            Object control = newSettingsWidget("com.oplus.settings.widget.preference.SettingsPreferenceCategory", context, loader);
            XposedHelpers.callMethod(control, "setTitle", "书写反馈");
            XposedHelpers.callMethod(screen, "addPreference", control);
            final boolean supportedPen = isLenovoTabPenProConnected(context);
            final boolean enabled = supportedPen && Settings.Global.getInt(
                    context.getContentResolver(), PEN_HAPTIC_ENABLED, 1) != 0;
            Object master = newSettingsWidget("com.oplus.settings.widget.preference.SettingSwitchPreference", context, loader);
            XposedHelpers.callMethod(master, "setTitle", "全局书写振动");
            XposedHelpers.callMethod(master, "setSummary", supportedPen
                    ? "书写时提供触觉反馈" : "连接 Lenovo Tab Pen Pro 后可用");
            XposedHelpers.callMethod(master, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(master, "setChecked", Boolean.valueOf(enabled));
            XposedHelpers.callMethod(master, "setEnabled", Boolean.valueOf(supportedPen));
            enableCardLayout(master);
            Class<?> change = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceChangeListener", loader);
            XposedHelpers.callMethod(master, "setOnPreferenceChangeListener", Proxy.newProxyInstance(loader,
                    new Class<?>[] { change }, new InvocationHandler() {
                @Override public Object invoke(Object p, Method m, Object[] a) {
                        if (!"onPreferenceChange".equals(m.getName())) return Boolean.TRUE;
                    if (!isLenovoTabPenProConnected(context)) return Boolean.FALSE;
                    boolean value = Boolean.TRUE.equals(a[1]);
                    Settings.Global.putInt(context.getContentResolver(), PEN_HAPTIC_ENABLED, value ? 1 : 0);
                    context.sendBroadcast(new Intent("com.aclaniakea.lenovopenbridge.haptic.COMMAND").putExtra("enabled", value));
                    postDelayed(new Runnable() { @Override public void run() { buildColorOsPenHapticPage(fragment, loader); } }, 180L);
                    return Boolean.TRUE;
                }
            }));
            XposedHelpers.callMethod(control, "addPreference", master);
            Object patternGroup = newSettingsWidget("com.oplus.settings.widget.preference.SettingsPreferenceCategory", context, loader);
            XposedHelpers.callMethod(patternGroup, "setTitle", "振动触感");
            XposedHelpers.callMethod(screen, "addPreference", patternGroup);
            final String selected = Settings.Global.getString(context.getContentResolver(), PEN_HAPTIC_PATTERN) == null
                    ? "pencil" : Settings.Global.getString(context.getContentResolver(), PEN_HAPTIC_PATTERN);
            final String[] values = { "pencil", "fountain", "ballpoint", "marker", "gear" };
            final String[] labels = { "铅笔", "钢笔", "圆珠笔", "马克笔", "齿轮" };
            final String[] summaries = {
                    "连续、轻柔的原厂书写反馈",
                    "起笔清晰，行笔节奏舒缓",
                    "短促紧实，适合快速书写",
                    "宽厚平稳，适合涂写与标记",
                    "高频交替的颗粒感反馈"
            };
            Class<?> click = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceClickListener", loader);
            for (int i = 0; i < values.length; i++) {
                final String value = values[i];
                Object row = newSettingsWidget("com.oplus.settings.widget.preference.OplusMarkPreference", context, loader);
                XposedHelpers.callMethod(row, "setTitle", labels[i]);
                XposedHelpers.callMethod(row, "setSummary", summaries[i]);
                XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
                XposedHelpers.callMethod(row, "setEnabled", Boolean.valueOf(enabled));
                XposedHelpers.callMethod(row, "setChecked", Boolean.valueOf(value.equals(selected)));
                enableCardLayout(row);
                XposedHelpers.callMethod(row, "setOnPreferenceClickListener", Proxy.newProxyInstance(loader,
                        new Class<?>[] { click }, new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) {
                        if (!"onPreferenceClick".equals(m.getName())) return Boolean.FALSE;
                        Settings.Global.putString(context.getContentResolver(), PEN_HAPTIC_PATTERN, value);
                        context.sendBroadcast(new Intent("com.aclaniakea.lenovopenbridge.haptic.COMMAND").putExtra("pattern", value));
                        postDelayed(new Runnable() { @Override public void run() { buildColorOsPenHapticPage(fragment, loader); } }, 180L);
                        return Boolean.TRUE;
                    }
                }));
                XposedHelpers.callMethod(patternGroup, "addPreference", row);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": build pen haptic page failed");
            XposedBridge.log(t);
        }
    }

    private static boolean isLenovoTabPenProConnected(Context context) {
        if (Settings.Global.getInt(context.getContentResolver(),
                "lenovo_pen_link_connected", 0) == 0) return false;
        // Model gate: the Pro's persisted HID PNP identifier is 17EF:61A1
        // (stored little-endian as "ef 17 a1 61").  Do not depend on a
        // Bluetooth alias, which users may rename and which varies by locale.
        String pnp = Settings.Global.getString(context.getContentResolver(), "lenovo_pen_pnp_id");
        if (pnp != null && pnp.toLowerCase().replaceAll("[^0-9a-f]", "")
                .contains("ef17a161")) return true;
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device != null && device.getVendorId() == 0x17ef
                        && device.getProductId() == 0x61a1) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Opt into Settings' rounded white card renderer when this COUI build exposes it. */
    private static void enableCardLayout(Object preference) {
        try {
            XposedHelpers.callMethod(preference, "setIsSupportCardUse", Boolean.TRUE);
        } catch (Throwable ignored) {
            // Older Settings builds use the same card renderer by default.
        }
    }

    private static void buildColorOsCustomKeyPage(final Object fragment, final ClassLoader loader) {
        try {
            final Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
            if (context == null) return;
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (isAppPickerPage(activity)) {
                buildColorOsAppPicker(fragment, activity, context, loader);
                return;
            }
            if (activity instanceof android.app.Activity) {
                ((android.app.Activity) activity).setTitle("自定义键");
            }
            Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            if (screen == null) return;
            XposedHelpers.callMethod(screen, "removeAll");

            Object category = newSettingsWidget(
                    "com.oplus.settings.widget.preference.SettingsPreferenceCategory",
                    context, loader);
            XposedHelpers.callMethod(category, "setTitle", "按键功能");
            XposedHelpers.callMethod(screen, "addPreference", category);

            // "打开指定应用" follows the OEM navigation-row pattern rather
            // than a mark/radio row: it opens a second Settings page.  Using
            // OplusMarkPreference here made COUI animate a transient second
            // check mark before the child page was chosen.
            final Object appRow = newColorOsPreference(context, loader);
            XposedHelpers.callMethod(appRow, "setKey", AI_PREF_KEY + "_select_app");
            XposedHelpers.callMethod(appRow, "setTitle", "打开指定应用");
            XposedHelpers.callMethod(appRow, "setPersistent", Boolean.FALSE);
            String app = configuredAppLabel(context);
            XposedHelpers.callMethod(appRow, "setSummary",
                    app.length() == 0 ? "选择要打开的应用" : app);
            Class<?> listener = XposedHelpers.findClass(
                    "androidx.preference.Preference$OnPreferenceClickListener", loader);
            InvocationHandler appCallback = new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onPreferenceClick".equals(method.getName())) openColorOsAppPicker(context);
                    return Boolean.TRUE;
                }
            };
            Object appProxy = Proxy.newProxyInstance(loader, new Class<?>[] { listener }, appCallback);
            XposedHelpers.callMethod(appRow, "setOnPreferenceClickListener", appProxy);
            XposedHelpers.callMethod(category, "addPreference", appRow);

            final String[] titles = new String[] {
                    "打开通知中心", "显示最近任务", "不执行操作"
            };
            final String[] actions = new String[] {
                    ACTION_NOTIFICATIONS, ACTION_RECENTS, ""
            };
            String selected = Settings.Secure.getString(context.getContentResolver(), AI_ACTION);
            if (selected == null) selected = "";
            for (int i = 0; i < titles.length; i++) {
                final int index = i;
                final Object row = newSettingsWidget(
                        "com.oplus.settings.widget.preference.OplusMarkPreference",
                        context, loader);
                XposedHelpers.callMethod(row, "setTitle", titles[i]);
                XposedHelpers.callMethod(row, "setKey", AI_PREF_KEY + "_action_" + i);
                XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
                try {
                    XposedHelpers.callMethod(row, "setChecked",
                            Boolean.valueOf(actions[i].equals(selected)));
                } catch (Throwable ignored) { }
                InvocationHandler callback = new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) {
                        if (!"onPreferenceClick".equals(method.getName())) return Boolean.FALSE;
                        Settings.Secure.putString(context.getContentResolver(), AI_ACTION,
                                actions[index]);
                        // COUI updates its marker after the listener returns.
                        // Rebuild after that animation transaction has fully
                        // committed, keeping this group genuinely single-choice.
                        postDelayed(new Runnable() {
                            @Override public void run() {
                                buildColorOsCustomKeyPage(fragment, loader);
                            }
                        }, 260L);
                        return Boolean.TRUE;
                    }
                };
                Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { listener }, callback);
                XposedHelpers.callMethod(row, "setOnPreferenceClickListener", proxy);
                // Keep every action in the same PreferenceCategory as the
                // app-picker row.  Adding these rows directly to the screen
                // made COUI treat each as a separate card, so the group lost
                // its continuous rounded head/body/foot shape.
                XposedHelpers.callMethod(category, "addPreference", row);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": build ColorOS custom-key page failed");
            XposedBridge.log(t);
        }
    }

    private static boolean isAppPickerPage(Object activity) {
        return activity instanceof android.app.Activity
                && PAGE_MODE_APP_PICKER.equals(((android.app.Activity) activity).getIntent()
                .getStringExtra(CUSTOM_PAGE_MODE));
    }

    /** Build the picker in Settings itself so it keeps COUI spacing, scrolling,
     * icons and the same radio-mark affordance as other ColorOS selections. */
    private static void buildColorOsAppPicker(final Object fragment, final Object activity,
            final Context context, final ClassLoader loader) {
        try {
            Object hostActivity = activity;
            if (hostActivity == null) hostActivity = XposedHelpers.callMethod(fragment, "getActivity");
            if (hostActivity instanceof android.app.Activity) {
                ((android.app.Activity) hostActivity).setTitle("选择应用");
            }
            android.view.View root = (android.view.View) XposedHelpers.callMethod(fragment, "getView");
            if (root == null || root.findViewWithTag("acl_app_picker_root") != null) return;
            android.view.View containerView = root.findViewById(android.R.id.list_container);
            if (!(containerView instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup container = (android.view.ViewGroup) containerView;
            container.removeAllViews();
            android.widget.LinearLayout page = new android.widget.LinearLayout(context);
            page.setTag("acl_app_picker_root");
            page.setOrientation(android.widget.LinearLayout.VERTICAL);
            // These are the actual Settings app-management resources: the
            // search bar and every list item retain OEM dimensions/styles.
            android.view.View search = android.view.LayoutInflater.from(context)
                    .inflate(0x7f0d0337, page, false);
            // The source XML uses a themed dimension; inside a Preference
            // container it can inherit MATCH_PARENT, so keep the OEM app-list
            // height explicitly (52dp, app_list_search_bar_height).
            final AppPickerAdapter adapter = new AppPickerAdapter(context, fragment);
            livePickerAdapter = adapter;
            livePickerActivity = hostActivity instanceof android.app.Activity
                    ? (android.app.Activity) hostActivity : null;
            // Keep the OEM search widget at its natural full width.  The
            // system-app action lives in the native title-bar overflow above,
            // so there is no second view competing for this hit target.
            page.addView(search, new android.widget.LinearLayout.LayoutParams(-1, dp(context, 52)));
            final android.widget.ListView list = new android.widget.ListView(context);
            list.setDivider(null);
            list.setAdapter(adapter);
            // Use the same pale rounded card treatment as the Settings app
            // manager.  A naked ListView here lost the grey list container
            // that makes this page read as a native ColorOS sub-page.
            android.widget.FrameLayout listCard = new android.widget.FrameLayout(context);
            listCard.setBackgroundResource(0x7f0807ab); // card_list_item_full_bg
            listCard.setClipToOutline(true);
            listCard.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
            android.widget.LinearLayout.LayoutParams cardParams =
                    new android.widget.LinearLayout.LayoutParams(-1, 0, 1f);
            cardParams.setMargins(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 16));
            page.addView(listCard, cardParams);
            container.addView(page, new android.view.ViewGroup.LayoutParams(-1, -1));
            android.widget.EditText editor = findEditText(search);
            if (editor != null) {
                editor.setHint("搜索应用");
                // The OEM resource normally receives these attributes from
                // AppManagement's parent.  This is a standalone selector, so
                // restore its input contract explicitly rather than leaving a
                // visible-but-inert EditText in the preference container.
                editor.setEnabled(true);
                editor.setClickable(true);
                editor.setFocusable(true);
                editor.setFocusableInTouchMode(true);
                editor.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    adapter.filter(s == null ? "" : s.toString());
                    XposedBridge.log(TAG + ": custom app-picker query=" + s
                            + " results=" + adapter.getCount());
                }
                @Override public void afterTextChanged(android.text.Editable e) { }
                });
            } else {
                XposedBridge.log(TAG + ": custom app-picker search editor missing");
            }
            if (hostActivity instanceof android.app.Activity) {
                // The activity creates its toolbar before the fragment has
                // replaced the list container.  Request one fresh native menu
                // pass now so the overflow item is guaranteed to be present.
                ((android.app.Activity) hostActivity).invalidateOptionsMenu();
            }
            list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override public void onItemClick(android.widget.AdapterView<?> p, android.view.View v,
                        int position, long id) {
                    ResolveInfo info = adapter.getItem(position);
                    Settings.Secure.putString(context.getContentResolver(), AI_PACKAGE,
                            info.activityInfo.packageName);
                    Settings.Secure.putString(context.getContentResolver(), AI_ACTION, ACTION_APP);
                    Object current = XposedHelpers.callMethod(fragment, "getActivity");
                    if (current instanceof android.app.Activity) ((android.app.Activity) current).finish();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": build ColorOS app picker failed");
            XposedBridge.log(t);
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static android.widget.EditText findEditText(android.view.View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (!(view instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.EditText found = findEditText(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static final class AppPickerAdapter extends android.widget.BaseAdapter {
        private final Context context; private final Object fragment;
        private final ArrayList<ResolveInfo> all = new ArrayList<ResolveInfo>();
        private final ArrayList<ResolveInfo> shown = new ArrayList<ResolveInfo>();
        private boolean showSystemApps;
        private String query = "";
        AppPickerAdapter(Context c, Object f) {
            context = c; fragment = f;
            all.addAll(c.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
            Collections.sort(all, new Comparator<ResolveInfo>() { @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(context.getPackageManager())).compareToIgnoreCase(
                        String.valueOf(b.loadLabel(context.getPackageManager()))); }});
            showSystemApps = Settings.Secure.getInt(c.getContentResolver(),
                    AI_SHOW_SYSTEM_APPS, 0) != 0;
            filter("");
        }
        void setShowSystemApps(boolean value) { showSystemApps = value; filter(query); }
        void filter(String value) { query = value == null ? "" : value; shown.clear(); String q = query.trim().toLowerCase();
            for (ResolveInfo i : all) {
                boolean system = i.activityInfo != null && i.activityInfo.applicationInfo != null
                        && (i.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !showSystemApps) continue;
                if (String.valueOf(i.loadLabel(context.getPackageManager())).toLowerCase().contains(q)) shown.add(i);
            }
            notifyDataSetChanged(); }
        @Override public int getCount() { return shown.size(); }
        @Override public ResolveInfo getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public android.view.View getView(int p, android.view.View convert, android.view.ViewGroup parent) {
            android.view.View row = convert == null ? android.view.LayoutInflater.from(context).inflate(0x7f0d033a, parent, false) : convert;
            ResolveInfo info = getItem(p); ((android.widget.ImageView) row.findViewById(0x7f0a00f5)).setImageDrawable(info.loadIcon(context.getPackageManager()));
            ((android.widget.TextView) row.findViewById(0x7f0a00fb)).setText(info.loadLabel(context.getPackageManager()));
            // The app-manager item already contains ColorOS' right-side
            // selection widget.  Reuse it so this picker has the same clear
            // current-choice affordance as the first-level custom-key menu.
            android.view.View oldCheck = row.findViewById(0x7f0a00fc);
            if (oldCheck != null) oldCheck.setVisibility(android.view.View.GONE);
            // Match OplusMarkPreference: a themed single-choice RadioButton,
            // not the app-manager's multi-select checkbox.
            android.widget.RadioButton mark = (android.widget.RadioButton) row.findViewWithTag("acl_picker_mark");
            if (mark == null && row instanceof android.widget.LinearLayout) {
                mark = new android.widget.RadioButton(context);
                mark.setTag("acl_picker_mark"); mark.setClickable(false); mark.setFocusable(false);
                android.widget.LinearLayout.LayoutParams markParams =
                        new android.widget.LinearLayout.LayoutParams(-2, -2);
                // Match Settings' list_item_right_margin (24dp).  The stock
                // app-manager checkbox has a container around it; our picker
                // adds a radio directly, so retain that visual end inset here.
                markParams.setMargins(0, 0, dp(context, 24), 0);
                ((android.widget.LinearLayout) row).addView(mark, markParams);
            }
            if (mark != null) mark.setChecked(info.activityInfo.packageName.equals(
                    Settings.Secure.getString(context.getContentResolver(), AI_PACKAGE)));
            return row;
        }
    }


    private static Object newSettingsWidget(String name, Context context, ClassLoader loader)
            throws Exception {
        Class<?> type = XposedHelpers.findClass(name, loader);
        return type.getConstructor(Context.class).newInstance(context);
    }

    private static String configuredAppLabel(Context context) {
        try {
            String pkg = Settings.Secure.getString(context.getContentResolver(), AI_PACKAGE);
            if (pkg == null || pkg.length() == 0) return "";
            return String.valueOf(context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void hookInheritedResume(final Class<?> fragment, final ClassLoader loader) {
        for (Class<?> type = fragment; type != null; type = type.getSuperclass()) {
            try {
                Method resume = type.getDeclaredMethod("onResume");
                XposedBridge.hookMethod(resume, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        if (fragment.isInstance(hook.thisObject)) addAiPreference(hook.thisObject, loader);
                    }
                });
                return;
            } catch (NoSuchMethodException ignored) { }
        }
    }

    private static void addAiPreference(final Object fragment, ClassLoader loader) {
        try {
            final Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
            if (context == null) return;
            Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            if (screen == null) return;
            Object existing = XposedHelpers.callMethod(screen, "findPreference", AI_PREF_KEY);
            Object keyboardGroup = findKeyboardPreferenceGroup(screen);
            if (existing != null) {
                moveToKeyboardGroup(existing, keyboardGroup);
                updateSummary(context, existing);
                updateKeyboardAvailability(existing);
                return;
            }
            final Object item = newColorOsPreference(context, loader);
            XposedHelpers.callMethod(item, "setKey", AI_PREF_KEY);
            XposedHelpers.callMethod(item, "setTitle", "自定义键");
            // The OEM puts the keyboard controls in a nested PreferenceGroup.
            // Adding to the root merely happens to render before that group on
            // some ColorOS builds, regardless of order.  We add to the actual
            // parent of “键盘设置 / 键盘布局” below.
            // Existing OEM keyboard entries occupy orders 0..27.  Keep the
            // custom row directly under them in that exact category.
            XposedHelpers.callMethod(item, "setOrder", Integer.valueOf(28));
            updateSummary(context, item);
            updateKeyboardAvailability(item);
            Class<?> listener = XposedHelpers.findClass(
                    "androidx.preference.Preference$OnPreferenceClickListener", loader);
            InvocationHandler callback = new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onPreferenceClick".equals(method.getName())) openCustomKeyPage(context);
                    return Boolean.TRUE;
                }
            };
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { listener }, callback);
            XposedHelpers.callMethod(item, "setOnPreferenceClickListener", proxy);
            XposedHelpers.callMethod(keyboardGroup, "addPreference", item);
            XposedBridge.log(TAG + ": Settings custom-key row added to "
                    + preferenceGroupName(keyboardGroup));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": add AI preference failed");
            XposedBridge.log(t);
        }
    }

    private static void openCustomKeyPage(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(SETTINGS, "com.android.settings.SubSettings");
            intent.putExtra(":settings:show_fragment",
                    "com.oplus.settings.feature.othersettings.input.OplusKeyboardHabitFragment");
            intent.putExtra(CUSTOM_PAGE_EXTRA, true);
            context.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": custom-key page launch failed");
            XposedBridge.log(t);
        }
    }

    private static void openColorOsAppPicker(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(SETTINGS, "com.android.settings.SubSettings");
            intent.putExtra(":settings:show_fragment",
                    "com.oplus.settings.feature.othersettings.input.OplusKeyboardHabitFragment");
            intent.putExtra(CUSTOM_PAGE_EXTRA, true);
            intent.putExtra(CUSTOM_PAGE_MODE, PAGE_MODE_APP_PICKER);
            context.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ColorOS app picker launch failed");
            XposedBridge.log(t);
        }
    }

    private static void hookColorOsAppManagementPicker(final ClassLoader loader) {
        try {
            final Class<?> host = XposedHelpers.findClass(
                    "com.android.settings.applications.manageapplications.ManageApplications", loader);
            XposedHelpers.findAndHookMethod(host, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    android.app.Activity activity = appManagementPickerActivity(hook.thisObject);
                    if (activity != null) activity.setTitle("选择应用");
                }
            });
            XposedHelpers.findAndHookMethod(host, "onClick", android.view.View.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            android.app.Activity activity = appManagementPickerActivity(hook.thisObject);
                            if (activity == null) return;
                            String packageName = appManagementClickedPackage(hook.thisObject,
                                    (android.view.View) hook.args[0]);
                            if (packageName == null || packageName.length() == 0) return;
                            Settings.Secure.putString(activity.getContentResolver(), AI_PACKAGE,
                                    packageName);
                            Settings.Secure.putString(activity.getContentResolver(), AI_ACTION,
                                    ACTION_APP);
                            XposedBridge.log(TAG + ": selected app " + packageName);
                            hook.setResult(null); // do not open app details
                            activity.finish();
                        }
                    });
            XposedBridge.log(TAG + ": native ColorOS app picker hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": native ColorOS app picker hook failed");
            XposedBridge.log(t);
        }
    }

    private static android.app.Activity appManagementPickerActivity(Object fragment) {
        try {
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (!(activity instanceof android.app.Activity)) return null;
            android.app.Activity result = (android.app.Activity) activity;
            return result.getIntent().getBooleanExtra(APP_MANAGEMENT_PICKER_EXTRA, false)
                    ? result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Same position calculation used by ManageApplications.onClick, before
     * the stock code opens an app-detail page. */
    private static String appManagementClickedPackage(Object fragment, android.view.View row) {
        try {
            Object recycler = XposedHelpers.getObjectField(fragment, "mRecyclerView");
            int adapterPosition = ((Integer) XposedHelpers.callMethod(recycler,
                    "getChildAdapterPosition", row)).intValue();
            if (adapterPosition < 0) return null;
            int listType = XposedHelpers.getIntField(fragment, "mListType");
            Object adapter = XposedHelpers.getObjectField(fragment, "mApplications");
            if (adapter == null) return null;
            int appPosition = ((Integer) XposedHelpers.callStaticMethod(adapter.getClass(),
                    "getApplicationPosition", listType, adapterPosition)).intValue();
            Object adaptor = XposedHelpers.callMethod(fragment, "getAdaptor");
            int realPosition = ((Integer) XposedHelpers.callMethod(adaptor,
                    "getChildAdapterRealPosition", appPosition)).intValue();
            int count = ((Integer) XposedHelpers.callMethod(adapter,
                    "getApplicationCount")).intValue();
            if (realPosition < 0 || realPosition >= count) return null;
            Object entry = XposedHelpers.callMethod(adapter, "getAppEntry", realPosition);
            Object info = XposedHelpers.getObjectField(entry, "info");
            return (String) XposedHelpers.getObjectField(info, "packageName");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": native picker item lookup failed");
            XposedBridge.log(t);
            return null;
        }
    }


    private static Object newColorOsPreference(Context context, ClassLoader loader) throws Exception {
        // Match the OEM "keyboard layout" rows first.  Constructor signatures
        // vary between ColorOS updates, so gracefully retain COUIPreference
        // and framework Preference as compatible fallbacks.
        String[] names = new String[] {
                "com.oplus.settings.widget.preference.SettingJumpPreference",
                "com.coui.appcompat.preference.COUIPreference",
                "androidx.preference.Preference"
        };
        Throwable last = null;
        for (String name : names) {
            try {
                Class<?> type = XposedHelpers.findClass(name, loader);
                return type.getConstructor(Context.class).newInstance(context);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("No compatible Settings preference", last);
    }

    private static void moveToKeyboardGroup(Object preference, Object targetGroup) {
        if (preference == null || targetGroup == null) return;
        try {
            Object parent = XposedHelpers.callMethod(preference, "getParent");
            if (parent != targetGroup) {
                if (parent != null) XposedHelpers.callMethod(parent, "removePreference", preference);
                XposedHelpers.callMethod(targetGroup, "addPreference", preference);
                XposedBridge.log(TAG + ": Settings custom-key row moved to "
                        + preferenceGroupName(targetGroup));
            }
            XposedHelpers.callMethod(preference, "setOrder", Integer.valueOf(28));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Settings custom-key row move failed");
            XposedBridge.log(t);
        }
    }

    /**
     * Settings XML differs between ColorOS versions: on some builds the two
     * stock keyboard rows are direct children of the screen, on others they
     * are children of a PreferenceCategory.  Locate their actual parent
     * instead of relying on the root screen's visual ordering.
     */
    private static Object findKeyboardPreferenceGroup(Object root) {
        try {
            Object exact = XposedHelpers.callMethod(root, "findPreference",
                    "keyboard_settings_preference_category");
            if (exact != null) return exact;
        } catch (Throwable ignored) { }
        Object found = findKeyboardPreferenceGroupRecursive(root, 0);
        return found != null ? found : root;
    }

    private static Object findKeyboardPreferenceGroupRecursive(Object group, int depth) {
        if (group == null || depth > 8) return null;
        try {
            Object countValue = XposedHelpers.callMethod(group, "getPreferenceCount");
            if (!(countValue instanceof Integer)) return null;
            int count = ((Integer) countValue).intValue();
            for (int i = 0; i < count; i++) {
                Object child = XposedHelpers.callMethod(group, "getPreference", Integer.valueOf(i));
                if (isStockKeyboardPreference(child)) return group;
            }
            for (int i = 0; i < count; i++) {
                Object child = XposedHelpers.callMethod(group, "getPreference", Integer.valueOf(i));
                Object nested = findKeyboardPreferenceGroupRecursive(child, depth + 1);
                if (nested != null) return nested;
            }
        } catch (Throwable ignored) {
            // A leaf Preference has no getPreferenceCount(); it is not a group.
        }
        return null;
    }

    private static boolean isStockKeyboardPreference(Object preference) {
        if (preference == null) return false;
        try {
            Object titleValue = XposedHelpers.callMethod(preference, "getTitle");
            String title = titleValue == null ? "" : String.valueOf(titleValue);
            Object keyValue = XposedHelpers.callMethod(preference, "getKey");
            String key = keyValue == null ? "" : String.valueOf(keyValue);
            return "键盘设置".equals(title) || "键盘布局".equals(title)
                    || key.contains("keyboard_settings") || key.contains("keyboard_layout");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String preferenceGroupName(Object group) {
        try {
            Object titleValue = XposedHelpers.callMethod(group, "getTitle");
            return group.getClass().getName() + " title=" + String.valueOf(titleValue);
        } catch (Throwable ignored) {
            return String.valueOf(group);
        }
    }

    private static void updateKeyboardAvailability(Object preference) {
        boolean available = false;
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device != null && device.getVendorId() == LENOVO_VENDOR
                        && device.getProductId() == KEYBOARD_PRODUCT) {
                    available = true;
                    break;
                }
            }
        } catch (Throwable ignored) { }
        XposedHelpers.callMethod(preference, "setEnabled", Boolean.valueOf(available));
        if (!available) {
            XposedHelpers.callMethod(preference, "setSummary", "请先连接 Lenovo 键盘");
        }
    }

    private static void updateSummary(Context context, Object preference) {
        String assignment = "不执行操作";
        try {
            String action = Settings.Secure.getString(context.getContentResolver(), AI_ACTION);
            String pkg = Settings.Secure.getString(context.getContentResolver(), AI_PACKAGE);
            if (ACTION_NOTIFICATIONS.equals(action)) assignment = "打开通知中心";
            else if (ACTION_RECENTS.equals(action)) assignment = "显示最近任务";
            else if (ACTION_SMALL_WINDOW.equals(action)) assignment = "当前应用小窗";
            else if (ACTION_SPLIT_SCREEN.equals(action)) assignment = "当前应用分屏";
            else if ((ACTION_APP.equals(action) || action == null || action.length() == 0)
                    && pkg != null && pkg.length() > 0) {
                CharSequence label = context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getApplicationInfo(pkg, 0));
                assignment = String.valueOf(label);
            }
        } catch (Throwable ignored) { }
        // Match “键盘布局”: the muted text explains this setting, while the
        // current selection is an assignment on the right-hand side.
        XposedHelpers.callMethod(preference, "setSummary", "为键盘上的自定义键设置快捷操作");
        try {
            XposedHelpers.callMethod(preference, "setAssignment", assignment);
        } catch (Throwable ignored) { }
    }

    private static void showAppPicker(final Context context, final Object preference) {
        final String[] actions = new String[] {
                "打开应用", "打开通知中心", "显示最近任务", "不执行操作"
        };
        showChoiceDialog(context, "自定义键", actions, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    showLaunchableAppPicker(context, preference);
                    return;
                }
                String action = which == 1 ? ACTION_NOTIFICATIONS
                        : which == 2 ? ACTION_RECENTS : "";
                Settings.Secure.putString(context.getContentResolver(), AI_ACTION, action);
                updateSummary(context, preference);
            }
        });
    }

    private static void showLaunchableAppPicker(final Context context, final Object preference) {
        try {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> raw = context.getPackageManager().queryIntentActivities(query, 0);
            final ArrayList<ResolveInfo> apps = new ArrayList<ResolveInfo>();
            for (ResolveInfo info : raw) {
                if (info.activityInfo != null && info.activityInfo.packageName != null
                        && !SETTINGS.equals(info.activityInfo.packageName)) apps.add(info);
            }
            Collections.sort(apps, new Comparator<ResolveInfo>() {
                @Override public int compare(ResolveInfo a, ResolveInfo b) {
                    return String.valueOf(a.loadLabel(context.getPackageManager()))
                            .compareToIgnoreCase(String.valueOf(b.loadLabel(context.getPackageManager())));
                }
            });
            final String[] labels = new String[apps.size() + 1];
            labels[0] = "不执行操作";
            for (int i = 0; i < apps.size(); i++) {
                labels[i + 1] = String.valueOf(apps.get(i).loadLabel(context.getPackageManager()));
            }
            showChoiceDialog(context, "选择要打开的应用", labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    String value = which == 0 ? "" : apps.get(which - 1).activityInfo.packageName;
                    Settings.Secure.putString(context.getContentResolver(), AI_PACKAGE, value);
                    Settings.Secure.putString(context.getContentResolver(), AI_ACTION,
                            value.length() == 0 ? "" : ACTION_APP);
                    updateSummary(context, preference);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": app picker failed");
            XposedBridge.log(t);
        }
    }

    /** Prefer ColorOS' COUI selector; the framework dialog retains the Settings theme as fallback. */
    private static void showChoiceDialog(Context context, String title, String[] items,
            DialogInterface.OnClickListener listener) {
        try {
            // COUI lives inside the Settings APK class loader, not the module
            // loader.  Resolving it through Class.forName(String) selected the
            // latter and silently took the platform-dialog fallback.
            Class<?> builderClass = Class.forName(
                    "com.coui.appcompat.dialog.COUIAlertDialogBuilder", true, context.getClassLoader());
            Object builder = builderClass.getConstructor(Context.class).newInstance(context);
            builderClass.getMethod("setTitle", CharSequence.class).invoke(builder, title);
            builderClass.getMethod("setItems", CharSequence[].class, DialogInterface.OnClickListener.class)
                    .invoke(builder, new Object[] { items, listener });
            builderClass.getMethod("show").invoke(builder);
        } catch (Throwable noCoui) {
            new AlertDialog.Builder(context).setTitle(title).setItems(items, listener).show();
        }
    }
}
