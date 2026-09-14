package com.aclaniakea.lenovokeyboardbridge;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
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
    private static final String AI_PREF_KEY = "aclaniakea_lenovo_keyboard_ai_key";
    private static final String CUSTOM_PAGE_EXTRA = "aclaniakea_lenovo_keyboard_custom_page";
    // Reuse the stock ColorOS SubSettings host for the pen page as well.  The
    // renderer remains in Settings, so card grouping, switches and mark rows
    // are all real COUI widgets rather than a module-owned dialog.
    private static final String PEN_HAPTIC_PAGE_EXTRA = "aclaniakea_lenovo_pen_haptic_page";
    private static final String PEN_HAPTIC_ENABLED = "lenovo_pen_global_writing_haptic";
    private static final String PEN_HAPTIC_PATTERN = "lenovo_pen_global_writing_haptic_pattern";
    // Marks a normal Settings application-management page as our picker.
    // The page itself remains entirely OEM-owned, including search states.
    private static final String APP_MANAGEMENT_PICKER_EXTRA =
            "aclaniakea_lenovo_keyboard_app_management_picker";
    private static final String CUSTOM_PAGE_MODE = "aclaniakea_lenovo_keyboard_page_mode";
    private static final String PAGE_MODE_APP_PICKER = "app_picker";
    // SmartKeyAppsFragment reads its mode directly from Intent.flags.  100 is
    // the OEM press-key app-selection mode and gives us the complete stock
    // search/list/fast-scroll implementation.
    private static final String SMART_KEY_PICKER_EXTRA =
            "aclaniakea_lenovo_keyboard_smart_key_picker";
    private static final int SMART_KEY_APPS_MODE = 100;
    private static volatile boolean smartKeyPickerActive;
    private static volatile boolean smartKeyPickerInitializing;
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
            XposedBridge.log(TAG + ": ColorOS custom-key subpage hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ColorOS custom-key subpage hook failed");
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
                            } else if (isAppPickerPage(hook.thisObject)) {
                                buildColorOsAppPicker(hook.thisObject, loader);
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

    private static boolean isAppPickerPage(Object fragment) {
        try {
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            return activity instanceof android.app.Activity
                    && PAGE_MODE_APP_PICKER.equals(((android.app.Activity) activity).getIntent()
                    .getStringExtra(CUSTOM_PAGE_MODE));
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
                    if ("onPreferenceClick".equals(method.getName())) {
                        // App selection is the fourth member of this logical
                        // radio group.  Commit it before opening the child
                        // page so no parent action can remain selected behind
                        // (or after cancelling) the picker.
                        Settings.Secure.putString(context.getContentResolver(), AI_ACTION, ACTION_APP);
                        openColorOsAppPicker(context);
                    }
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
            final Object[] actionRows = new Object[actions.length];
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
                actionRows[index] = row;
                InvocationHandler callback = new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) {
                        if (!"onPreferenceClick".equals(method.getName())) return Boolean.FALSE;
                        Settings.Secure.putString(context.getContentResolver(), AI_ACTION,
                                actions[index]);
                        // Choosing an outer action replaces (rather than
                        // merely hides) the previous app assignment.  Without
                        // this, reopening the picker misleadingly restored a
                        // radio mark for an action that is no longer active.
                        Settings.Secure.putString(context.getContentResolver(), AI_PACKAGE, null);
                        // OplusMarkPreference does not form a radio group just
                        // because its siblings share a PreferenceCategory.
                        // Clear the other live rows immediately, before the
                        // delayed rebuild, so the old and new checks cannot
                        // coexist during the COUI click animation.
                        for (int j = 0; j < actionRows.length; j++) {
                            if (j == index || actionRows[j] == null) continue;
                            try {
                                XposedHelpers.callMethod(actionRows[j], "setChecked", Boolean.FALSE);
                            } catch (Throwable ignored) { }
                        }
                        try {
                            XposedHelpers.callMethod(actionRows[index], "setChecked", Boolean.TRUE);
                        } catch (Throwable ignored) { }
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

    /**
     * Independent picker page.  It deliberately uses the application-manager
     * search and row resources, while retaining only the custom-key result
     * action (one checked app, never an app-details launch).
     */
    private static void buildColorOsAppPicker(final Object fragment, final ClassLoader loader) {
        try {
            final Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (context == null || !(activity instanceof android.app.Activity)) return;
            final android.app.Activity host = (android.app.Activity) activity;
            host.setTitle("选择应用");
            android.view.View root = (android.view.View) XposedHelpers.callMethod(fragment, "getView");
            if (root == null || root.findViewWithTag("acl_app_picker_root") != null) return;
            android.view.View target = root.findViewById(android.R.id.list_container);
            if (!(target instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup container = (android.view.ViewGroup) target;
            container.removeAllViews();

            android.widget.LinearLayout page = new android.widget.LinearLayout(context);
            page.setTag("acl_app_picker_root");
            page.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.view.View search = android.view.LayoutInflater.from(context)
                    .inflate(0x7f0d0337, page, false); // manage_applications search bar
            page.addView(search, new android.widget.LinearLayout.LayoutParams(-1, dp(context, 52)));
            final ApplicationPickerAdapter adapter = new ApplicationPickerAdapter(context);
            final android.widget.ListView list = new android.widget.ListView(context);
            list.setDivider(null);
            list.setCacheColorHint(android.graphics.Color.TRANSPARENT);
            list.setAdapter(adapter);
            page.addView(list, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
            container.addView(page, new android.view.ViewGroup.LayoutParams(-1, -1));
            final android.view.View mask = new android.view.View(context);
            mask.setBackgroundColor(0x66000000);
            mask.setVisibility(android.view.View.GONE);
            mask.setAlpha(0f);
            try {
                android.view.ViewGroup.MarginLayoutParams maskParams =
                        new android.view.ViewGroup.MarginLayoutParams(-1, -1);
                maskParams.topMargin = dp(context, 52);
                container.addView(mask, maskParams);
            } catch (Throwable ignored) { }

            final android.widget.EditText editor = findEditText(search);
            if (editor != null) {
                editor.setHint("搜索应用");
                installPickerSearchController(search, editor, adapter, host, mask);
                editor.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                        adapter.filter(s == null ? "" : s.toString());
                    }
                    @Override public void afterTextChanged(android.text.Editable s) { }
                });
            }
            list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override public void onItemClick(android.widget.AdapterView<?> parent,
                        android.view.View view, int position, long id) {
                    android.content.pm.ResolveInfo item = adapter.getItem(position);
                    if (item == null || item.activityInfo == null) return;
                    Settings.Secure.putString(context.getContentResolver(), AI_PACKAGE,
                            item.activityInfo.packageName);
                    Settings.Secure.putString(context.getContentResolver(), AI_ACTION, ACTION_APP);
                    adapter.setChecked(item.activityInfo.packageName);
                    host.finish();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": build application picker failed");
            XposedBridge.log(t);
        }
    }

    /** Preserve the application-manager COUISearchBar state machine. */
    private static void installPickerSearchController(final android.view.View search,
            final android.widget.EditText editor, final ApplicationPickerAdapter adapter,
            final android.app.Activity host, final android.view.View mask) {
        try {
            XposedHelpers.callMethod(search, "setFunctionalButtonText", "取消");
            android.view.View.OnClickListener expand = new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View view) {
                    try {
                        XposedHelpers.callMethod(search, "changeStateWithAnimation", 1);
                        editor.requestFocus();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": picker search expand failed");
                    }
                }
            };
            search.setOnClickListener(expand);
            installPickerSearchHostAnimation(search, host, mask);
            try {
                Object cancel = XposedHelpers.callMethod(search, "getFunctionalButton");
                if (cancel instanceof android.view.View) {
                    ((android.view.View) cancel).setOnClickListener(
                            new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View view) {
                            editor.setText("");
                            adapter.filter("");
                            try {
                                XposedHelpers.callMethod(search, "changeStateWithAnimation", 0);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": picker search collapse failed");
                            }
                        }
                    });
                }
            } catch (Throwable ignored) { }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": picker search controller unavailable");
            XposedBridge.log(t);
        }
    }

    /** The application-manager host half of COUISearchBar's expand/collapse. */
    private static void installPickerSearchHostAnimation(final android.view.View search,
            final android.app.Activity activity, final android.view.View mask) {
        try {
            final android.content.res.Resources resources = activity.getResources();
            final android.view.View appBar = activity.findViewById(resources.getIdentifier(
                    "abl", "id", SETTINGS));
            final android.view.View toolbar = activity.findViewById(resources.getIdentifier(
                    "toolbar", "id", SETTINGS));
            final android.view.View content = activity.findViewById(resources.getIdentifier(
                    "content_frame", "id", SETTINGS));
            if (appBar == null || toolbar == null || content == null) return;
            Class<?> listener = search.getClass().getClassLoader().loadClass(
                    "com.coui.appcompat.searchview.COUISearchBar$OnStateChangeListener");
            Object stateListener = Proxy.newProxyInstance(search.getClass().getClassLoader(),
                    new Class<?>[] { listener }, new InvocationHandler() {
                private int naturalHeight;
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if (!"onStateChange".equals(method.getName()) || args == null
                            || args.length != 2) return null;
                    final boolean entering = ((Integer) args[1]).intValue() == 1;
                    if (naturalHeight <= 0) naturalHeight = appBar.getHeight();
                    if (naturalHeight <= 0) return null;
                    int from = appBar.getLayoutParams().height;
                    if (from <= 0) from = entering ? naturalHeight : 0;
                    android.animation.ValueAnimator animation = android.animation.ValueAnimator
                            .ofInt(from, entering ? 0 : naturalHeight);
                    animation.setDuration(250L);
                    animation.setInterpolator(new android.view.animation.PathInterpolator(
                            entering ? 0.1f : 0.3f, 0f, entering ? 0.1f : 0.9f, 1f));
                    final int full = naturalHeight;
                    animation.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator value) {
                            int height = ((Integer) value.getAnimatedValue()).intValue();
                            android.view.ViewGroup.LayoutParams params = appBar.getLayoutParams();
                            params.height = height;
                            appBar.setLayoutParams(params);
                            toolbar.setAlpha(height / (float) full);
                            content.setTranslationY(-(full - height));
                        }
                    });
                    animation.start();
                    mask.animate().cancel();
                    if (entering) {
                        mask.setVisibility(android.view.View.VISIBLE);
                        mask.animate().alpha(1f).setDuration(150L).setListener(null).start();
                    } else {
                        mask.animate().alpha(0f).setDuration(150L)
                                .setListener(new android.animation.AnimatorListenerAdapter() {
                            @Override public void onAnimationEnd(android.animation.Animator animation) {
                                mask.setVisibility(android.view.View.GONE);
                            }
                        }).start();
                    }
                    return null;
                }
            });
            search.getClass().getMethod("addOnStateChangeListener", listener).invoke(search,
                    stateListener);
            mask.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View view) {
                    XposedHelpers.callMethod(search, "changeStateWithAnimation", 0);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": picker search host animation unavailable");
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
            XposedBridge.log(TAG + ": custom application picker launch failed");
            XposedBridge.log(t);
        }
    }

    /**
     * The custom-key picker is a tagged instance of Settings' own application
     * management fragment.  No list, search view, icon, or search transition
     * is recreated by the module; only the final row click is repurposed.
     */
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
                            hook.setResult(null);
                            activity.finish();
                        }
                    });
            XposedBridge.log(TAG + ": native application picker hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": native application picker hook failed");
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

    /** Same position calculation used by ManageApplications before it opens details. */
    private static String appManagementClickedPackage(Object fragment, android.view.View row) {
        try {
            Object recycler = XposedHelpers.getObjectField(fragment, "mRecyclerView");
            int adapterPosition = ((Integer) XposedHelpers.callMethod(recycler,
                    "getChildAdapterPosition", row)).intValue();
            if (adapterPosition < 0) return null;
            int listType = XposedHelpers.getIntField(fragment, "mListType");
            Object applications = XposedHelpers.getObjectField(fragment, "mApplications");
            if (applications == null) return null;
            int appPosition = ((Integer) XposedHelpers.callStaticMethod(applications.getClass(),
                    "getApplicationPosition", listType, adapterPosition)).intValue();
            Object adapter = XposedHelpers.callMethod(fragment, "getAdaptor");
            int realPosition = ((Integer) XposedHelpers.callMethod(adapter,
                    "getChildAdapterRealPosition", appPosition)).intValue();
            int count = ((Integer) XposedHelpers.callMethod(applications,
                    "getApplicationCount")).intValue();
            if (realPosition < 0 || realPosition >= count) return null;
            Object entry = XposedHelpers.callMethod(applications, "getAppEntry", realPosition);
            Object info = XposedHelpers.getObjectField(entry, "info");
            return (String) XposedHelpers.getObjectField(info, "packageName");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": native picker item lookup failed");
            XposedBridge.log(t);
            return null;
        }
    }

    /** App-manager row resource plus a single-choice result control. */
    private static final class ApplicationPickerAdapter extends android.widget.BaseAdapter {
        private final Context context;
        private final java.util.ArrayList<android.content.pm.ResolveInfo> all =
                new java.util.ArrayList<android.content.pm.ResolveInfo>();
        private final java.util.ArrayList<android.content.pm.ResolveInfo> shown =
                new java.util.ArrayList<android.content.pm.ResolveInfo>();
        private String query = "";
        private String checked;

        ApplicationPickerAdapter(Context context) {
            this.context = context;
            all.addAll(context.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
            java.util.Collections.sort(all, new java.util.Comparator<android.content.pm.ResolveInfo>() {
                @Override public int compare(android.content.pm.ResolveInfo left,
                        android.content.pm.ResolveInfo right) {
                    return String.valueOf(left.loadLabel(context.getPackageManager()))
                            .compareToIgnoreCase(String.valueOf(
                                    right.loadLabel(context.getPackageManager())));
                }
            });
            checked = Settings.Secure.getString(context.getContentResolver(), AI_PACKAGE);
            filter("");
        }

        void filter(String value) {
            query = value == null ? "" : value;
            String needle = query.trim().toLowerCase();
            shown.clear();
            for (android.content.pm.ResolveInfo info : all) {
                String name = String.valueOf(info.loadLabel(context.getPackageManager())).toLowerCase();
                if (name.contains(needle)) shown.add(info);
            }
            notifyDataSetChanged();
        }

        void setChecked(String packageName) {
            checked = packageName;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public android.content.pm.ResolveInfo getItem(int position) {
            return position >= 0 && position < shown.size() ? shown.get(position) : null;
        }
        @Override public long getItemId(int position) { return position; }

        @Override public android.view.View getView(int position, android.view.View convert,
                android.view.ViewGroup parent) {
            android.view.View row = convert == null ? android.view.LayoutInflater.from(context)
                    .inflate(0x7f0d033a, parent, false) : convert; // manage_applications_item
            android.content.pm.ResolveInfo info = getItem(position);
            if (info == null) return row;
            android.view.View icon = row.findViewById(0x7f0a00f5);
            if (icon instanceof android.widget.ImageView) {
                ((android.widget.ImageView) icon).setImageDrawable(
                        info.loadIcon(context.getPackageManager()));
            }
            android.view.View title = row.findViewById(0x7f0a00fb);
            if (title instanceof android.widget.TextView) {
                ((android.widget.TextView) title).setText(info.loadLabel(context.getPackageManager()));
            }
            android.view.View oldCheck = row.findViewById(0x7f0a00fc);
            if (oldCheck != null) oldCheck.setVisibility(android.view.View.GONE);
            android.widget.RadioButton mark = (android.widget.RadioButton) row.findViewWithTag(
                    "acl_picker_mark");
            if (mark == null && row instanceof android.widget.LinearLayout) {
                mark = new android.widget.RadioButton(context);
                mark.setTag("acl_picker_mark");
                mark.setClickable(false);
                mark.setFocusable(false);
                android.widget.LinearLayout.LayoutParams params =
                        new android.widget.LinearLayout.LayoutParams(-2, -2);
                params.setMargins(0, 0, dp(context, 24), 0);
                ((android.widget.LinearLayout) row).addView(mark, params);
            }
            if (mark != null) {
                mark.setChecked(info.activityInfo != null
                        && info.activityInfo.packageName.equals(checked));
            }
            return row;
        }
    }

    /**
     * Reuse the OEM picker intact.  It normally persists an assignment for a
     * physical OPlus smart key; while our tagged instance is active, suppress
     * only that persistence and mirror its selected package to the Lenovo key
     * setting.  The stock picker still owns every view and interaction.
     */
    private static void hookStockSmartKeyAppPicker(final ClassLoader loader) {
        try {
            final Class<?> picker = XposedHelpers.findClass(
                    "com.oplus.settings.feature.smartkey.SmartKeyAppsFragment", loader);
            final Class<?> utils = XposedHelpers.findClass(
                    "com.oplus.settings.feature.smartkey.SmartKeyUtils", loader);
            final Class<?> searchAdapter = XposedHelpers.findClass(
                    "com.oplus.settings.feature.smartkey.SmartKeyAppsSearchAdapter", loader);
            final Class<?> customImageMarkPreference = XposedHelpers.findClass(
                    "com.oplus.settings.feature.smartkey.CustomImageMarkPreference", loader);
            final Class<?> preferenceViewHolder = XposedHelpers.findClass(
                    "androidx.preference.PreferenceViewHolder", loader);
            XposedHelpers.findAndHookMethod(customImageMarkPreference, "onBindViewHolder",
                    preferenceViewHolder, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            if (!smartKeyPickerActive) return;
                            Object view = XposedHelpers.callMethod(hook.args[0], "findViewById",
                                    Integer.valueOf(android.R.id.icon));
                            if (!(view instanceof android.widget.ImageView)) return;
                            android.widget.ImageView icon = (android.widget.ImageView) view;
                            resizeAppManagerIcon(icon);
                        }
                    });
            XposedHelpers.findAndHookMethod(searchAdapter, "onBindViewHolder",
                    XposedHelpers.findClass(
                            "com.oplus.settings.feature.smartkey.SmartKeyAppsSearchAdapter$AppsViewHolder",
                            loader), int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            if (!smartKeyPickerActive) return;
                            Object item = XposedHelpers.callMethod(hook.args[0], "getMItem");
                            if (item instanceof android.view.View) {
                                installSearchCardFeedback((android.view.View) item);
                            }
                            Object image = XposedHelpers.callMethod(hook.args[0], "getMImageView");
                            if (!(image instanceof android.widget.ImageView)) return;
                            android.widget.ImageView icon = (android.widget.ImageView) image;
                            android.view.ViewGroup.LayoutParams params = icon.getLayoutParams();
                            if (params == null) return;
                            resizeAppManagerIcon(icon);
                        }
                    });
            XposedHelpers.findAndHookMethod(picker, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!isStockSmartKeyPicker(hook.thisObject)) return;
                    // SmartKeyAppsFragment calls SmartKeyUtils.initData() from
                    // its super-visible onCreate path.  Make that original
                    // path read the Lenovo assignment from the outset.
                    smartKeyPickerActive = true;
                    smartKeyPickerInitializing = true;
                }
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (!isStockSmartKeyPicker(hook.thisObject)) return;
                    smartKeyPickerInitializing = false;
                }
            });
            XposedHelpers.findAndHookMethod(picker, "onDestroy", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (isStockSmartKeyPicker(hook.thisObject)) smartKeyPickerActive = false;
                }
            });
            XposedHelpers.findAndHookMethod(picker, "onPreferenceChange",
                    XposedHelpers.findClass("androidx.preference.Preference", loader), Object.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (!isStockSmartKeyPicker(hook.thisObject)
                                    || !Boolean.TRUE.equals(hook.args[1])) return;
                            Object preference = hook.args[0];
                            String pkg = (String) XposedHelpers.callMethod(preference, "getKey");
                            CharSequence title = (CharSequence) XposedHelpers.callMethod(preference,
                                    "getTitle");
                            saveStockPickerSelection((Context) XposedHelpers.callMethod(hook.thisObject,
                                    "getContext"), pkg, title);
                        }
                    });
            // appsSearchResultItemClick only binds a row and attaches this
            // Kotlin-generated listener. Hook the actual click so the Lenovo
            // assignment follows the stock search result selection.
            XposedHelpers.findAndHookMethod(searchAdapter,
                    "appsSearchResultItemClick$lambda$0", searchAdapter, int.class,
                    android.view.View.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (!smartKeyPickerActive) return;
                            Object adapter = hook.args[0];
                            int position = ((Integer) hook.args[1]).intValue();
                            Object item = XposedHelpers.callMethod(adapter, "getItem",
                                    Integer.valueOf(position));
                            if (item == null) return;
                            String pkg = (String) XposedHelpers.callMethod(item, "getPkgName");
                            String label = (String) XposedHelpers.callMethod(item, "getName");
                            saveStockPickerSelection((Context) XposedHelpers.getObjectField(
                                    adapter, "mContext"), pkg, label);
                        }

                    });
            XposedHelpers.findAndHookMethod(utils, "getTouchPowerValue", Context.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (smartKeyPickerInitializing) hook.setResult("8");
                        }
                    });
            XposedHelpers.findAndHookMethod(utils, "getTouchAppCache", Context.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (!smartKeyPickerInitializing) return;
                            Context context = (Context) hook.args[0];
                            String pkg = Settings.Secure.getString(context.getContentResolver(),
                                    AI_PACKAGE);
                            if (pkg == null || pkg.length() == 0) return;
                            hook.setResult(configuredAppLabel(context) + "_PKG_" + pkg);
                        }
                    });
            XposedHelpers.findAndHookMethod(utils, "setSettingData", Context.class, int.class,
                    String.class, String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            if (smartKeyPickerActive) hook.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + ": stock SmartKey app picker bridge installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": stock SmartKey app picker bridge failed");
            XposedBridge.log(t);
        }
    }

    private static boolean isStockSmartKeyPicker(Object fragment) {
        try {
            Object activity = XposedHelpers.callMethod(fragment, "getActivity");
            if (!(activity instanceof android.app.Activity)) return false;
            android.app.Activity result = (android.app.Activity) activity;
            return result.getIntent().getBooleanExtra(SMART_KEY_PICKER_EXTRA, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void saveStockPickerSelection(Context context, String pkg, CharSequence label) {
        if (context == null || pkg == null || pkg.length() == 0) return;
        Settings.Secure.putString(context.getContentResolver(), AI_PACKAGE, pkg);
        Settings.Secure.putString(context.getContentResolver(), AI_ACTION, ACTION_APP);
        XposedBridge.log(TAG + ": stock picker selected " + pkg + " (" + label + ")");
    }

    private static void installSearchCardFeedback(final android.view.View item) {
        try {
            // The adapter's automatic COUI feedback is the defective path on
            // this tablet.  Drive the same two stock animation methods from
            // the exact view that receives the adapter click instead.
            XposedHelpers.callMethod(item, "setBackgroundAnimationEnabled", Boolean.FALSE);
            item.setOnTouchListener(new android.view.View.OnTouchListener() {
                @Override public boolean onTouch(android.view.View view,
                        android.view.MotionEvent event) {
                    if (!smartKeyPickerActive) return false;
                    try {
                        switch (event.getActionMasked()) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                XposedHelpers.callMethod(item, "startAppearAnimation");
                                break;
                            case android.view.MotionEvent.ACTION_UP:
                            case android.view.MotionEvent.ACTION_CANCEL:
                                XposedHelpers.callMethod(item, "startDisAppearAnimationOrNot");
                                break;
                            default:
                                break;
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": SmartKey card feedback failed");
                        XposedBridge.log(t);
                    }
                    return false;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SmartKey search-card setup failed");
            XposedBridge.log(t);
        }
    }

    private static void resizeAppManagerIcon(android.widget.ImageView icon) {
        try {
            android.view.ViewGroup.LayoutParams params = icon.getLayoutParams();
            if (params == null) return;
            int size = (int) (36.0f * icon.getResources().getDisplayMetrics().density + 0.5f);
            params.width = size;
            params.height = size;
            icon.setLayoutParams(params);
            // The old application-management row uses enum value 3 (FIT_CENTER).
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SmartKey icon resize failed");
            XposedBridge.log(t);
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

}
