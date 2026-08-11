package com.aclaniakea.colorosporttuning;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;

/* loaded from: classes.dex */
final class SystemStylusHooks {
    private static final int HID_HOST_PROFILE = 4;
    private static final String PEN1_HALL = "/sys/devices/virtual/factory/interface/hw_info/pen1_hall";
    private static final String PEN2_HALL = "/sys/devices/virtual/factory/interface/hw_info/pen2_hall";
    private static int hallCandidateSamples;
    private static boolean hallReadFailed;
    private static boolean hapticControlReady;
    private static boolean hidConnectPending;
    private static boolean initialized;
    private static Object inputMonitor;
    private static Object inputReceiver;
    private static boolean kernelWakeReady;
    private static boolean kernelWakeDisabledLogged;
    private static long lastButtonAt;
    private static int lastButtons;
    private static long lastLong;
    private static long lastOemPresentAt;
    private static long lastTapUp;
    private static boolean longLatched;
    private static boolean magneticListenerReady;
    private static boolean monitorReady;
    private static Runnable pendingTap;
    private static boolean refreshActive;
    private static Context refreshContext;
    /*
     * The vendor panel resumes before OplusRefreshRateService finishes
     * rebuilding its votes. Replaying the pen state from SCREEN_ON here
     * used to race that restore and could leave the panel with no frame.
     */
    private static Context screenReplayContext;
    private static boolean stateReceiverReady;
    private static long suppressNvtHotplugUntil;
    private static long suppressPenKeysUntil;
    private static boolean touchscreenHapticsReady;
    private static boolean writing;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean screenOn = true;
    private static int lastPenHall = -1;
    private static int hallCandidate = -1;
    private static final HashSet<Integer> nvtDeviceIds = new HashSet<>();
    private static final Runnable STOP_WRITING = new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda15
        @Override // java.lang.Runnable
        public final void run() {
            SystemStylusHooks.stopWriting();
        }
    };
    private static final Runnable RELEASE_REFRESH = new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda16
        @Override // java.lang.Runnable
        public final void run() {
            Context context = SystemStylusHooks.refreshContext;
            if (context != null) {
                SystemStylusHooks.setRefreshActive(context, false);
            }
        }
    };
    private static final Runnable EXPIRE_LONG = new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda16
        @Override // java.lang.Runnable
        public final void run() {
            SystemStylusHooks.lambda$static$0();
        }
    };
    private static final Runnable HID_CONNECT_TIMEOUT = new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda17
        @Override // java.lang.Runnable
        public final void run() {
            SystemStylusHooks.lambda$static$1();
        }
    };
    private static final Runnable POLL_PEN_HALL = new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks.1
        @Override // java.lang.Runnable
        public void run() {
            Context context = SystemStylusHooks.refreshContext;
            if (context != null) {
                SystemStylusHooks.pollPenHall(context);
            }
            SystemStylusHooks.main.postDelayed(this, 250L);
        }
    };
    private static final Runnable SCREEN_ON_REPLAY = new Runnable() {
        @Override
        public void run() {
            Context context = SystemStylusHooks.screenReplayContext;
            if (context != null && SystemStylusHooks.screenOn && SystemStylusHooks.lastPenHall >= 0) {
                SystemStylusHooks.applyPenHall(context, SystemStylusHooks.lastPenHall, false);
                HookUtils.log("screen-on pen state replayed after panel settle");
            }
        }
    };
    private static int lastOemPresent = -1;
    private static int lastLoggedOemPresent = -2;

    static /* synthetic */ void lambda$static$0() {
        synchronized (SystemStylusHooks.class) {
            if (longLatched) {
                longLatched = false;
                HookUtils.log("stylus long action latch expired");
            }
        }
    }

    static /* synthetic */ void lambda$static$1() {
        synchronized (SystemStylusHooks.class) {
            if (hidConnectPending) {
                hidConnectPending = false;
                HookUtils.log("boot pen HID Host callback timed out; allowing the next retry");
            }
        }
    }

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        HookUtils.hookAll(loadPackageParam.classLoader, "com.android.server.policy.PhoneWindowManager", "interceptKeyBeforeQueueing", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks.2
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                KeyEvent keyEvent;
                Object[] objArr = methodHookParam.args;
                int length = objArr.length;
                int i = 0;
                while (true) {
                    if (i >= length) {
                        keyEvent = null;
                        break;
                    }
                    Object obj = objArr[i];
                    if (obj instanceof KeyEvent) {
                        keyEvent = (KeyEvent) obj;
                        break;
                    }
                    i++;
                }
                if (keyEvent != null && SystemStylusHooks.isPen(keyEvent.getDevice()) && SystemStylusHooks.handle(HookUtils.context(methodHookParam.thisObject), keyEvent)) {
                    methodHookParam.setResult(0);
                }
            }
        });
        XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks.3
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context != null) {
                    SystemStylusHooks.init(context);
                }
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "com.android.server.SystemServer", "startOtherServices", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.android.server.SystemServer", "run", xC_MethodHook);
        HookUtils.log("system_server stylus hooks installed");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean handle(final Context context, KeyEvent keyEvent) {
        if (context == null) {
            return false;
        }
        int keyCode = keyEvent.getKeyCode();
        int scanCode = keyEvent.getScanCode();
        String lowerCase = (keyEvent.getDevice() == null || keyEvent.getDevice().getName() == null) ? "" : keyEvent.getDevice().getName().toLowerCase();
        if (SystemClock.uptimeMillis() < suppressPenKeysUntil && isPen(keyEvent.getDevice())) {
            HookUtils.log("suppressed pen key during magnetic transition code=" + keyCode + " scan=" + scanCode);
            return true;
        }
        if (lowerCase.contains("lenovo tab pen pro consumer control")) {
            if (keyEvent.getRepeatCount() <= 0 && keyEvent.getAction() == 1) {
                if (keyCode == 131) {
                    HookUtils.log("mapped touch strip: swipe down -> native 767");
                    injectNativePenKey(context, 767);
                } else if (keyCode == 132) {
                    HookUtils.log("mapped touch strip: swipe up -> native 768");
                    injectNativePenKey(context, 768);
                } else if (keyCode == 133) {
                    HookUtils.log("mapped touch strip: double tap -> native 769");
                    injectNativePenKey(context, 769);
                } else {
                    HookUtils.log("unmapped consumer key code=" + keyCode + " scan=" + scanCode);
                }
            }
            return true;
        }
        char c = (keyCode == 131 || keyCode == 188 || scanCode == 240 || scanCode == 272) ? (char) 1 : (keyCode == 132 || keyCode == 189 || scanCode == 273) ? (char) 2 : ((keyCode >= 133 && keyCode <= 135) || keyCode == 190 || scanCode == 274) ? (char) 3 : (char) 0;
        if (c == 0) {
            return false;
        }
        if (keyEvent.getRepeatCount() > 0) {
            return true;
        }
        if (c == 1 && keyEvent.getAction() == 1) {
            tap(context);
            return true;
        }
        if (c == 2 && keyEvent.getAction() == 1) {
            click(context, true);
            return true;
        }
        if (c == 3 && keyEvent.getAction() == 1 && SystemClock.uptimeMillis() - lastLong > 900) {
            lastLong = SystemClock.uptimeMillis();
            haptic(context);
            button(context, "down");
            main.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda19
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.button(context, "up");
                }
            }, 120L);
        }
        return true;
    }

    private static void click(Context context, boolean z) {
        haptic(context);
        int i = Settings.Global.getInt(context.getContentResolver(), z ? "ipe_pencil_double_click" : "ipe_pencil_single_click", z ? 1 : HID_HOST_PROFILE);
        if (i == 0) {
            return;
        }
        String str = z ? "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK" : "com.oplus.ipemanager.action.PENCIL_SINGLE_CLICK";
        for (String str2 : i == HID_HOST_PROFILE ? new String[]{"com.oplus.healthservice"} : new String[]{"com.coloros.note", "com.oplus.screenshot"}) {
            sendAll(context, new Intent(str).setPackage(str2).putExtra("action", i).addFlags(268435456), i == HID_HOST_PROFILE ? "com.oplus.ipemanager.permission.receiver.DOUBLE_CLICK" : null);
        }
    }

    static /* synthetic */ void lambda$tap$3(Context context) {
        synchronized (SystemStylusHooks.class) {
            pendingTap = null;
            click(context, false);
        }
    }

    private static synchronized void tap(final Context context) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        Runnable runnable = pendingTap;
        if (runnable == null || jUptimeMillis - lastTapUp >= 320) {
            lastTapUp = jUptimeMillis;
            Runnable runnable2 = new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.lambda$tap$3(context);
                }
            };
            pendingTap = runnable2;
            main.postDelayed(runnable2, 280L);
            return;
        }
        main.removeCallbacks(runnable);
        pendingTap = null;
        lastTapUp = 0L;
        click(context, true);
    }

    private static synchronized void longAction(Context context) {
        if (SystemClock.uptimeMillis() - lastLong < 700) {
            return;
        }
        lastLong = SystemClock.uptimeMillis();
        haptic(context);
        if (longLatched) {
            HookUtils.log("stylus long action already armed");
            return;
        }
        button(context, "down");
        longLatched = true;
        Handler handler = main;
        Runnable runnable = EXPIRE_LONG;
        handler.removeCallbacks(runnable);
        handler.postDelayed(runnable, 30000L);
        HookUtils.log("stylus long action armed for next pen stroke");
    }

    private static synchronized void consumeLongLatch() {
        if (longLatched) {
            longLatched = false;
            main.removeCallbacks(EXPIRE_LONG);
            HookUtils.log("stylus long action consumed by pen down");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void releaseLong(Context context) {
        if (longLatched) {
            main.removeCallbacks(EXPIRE_LONG);
            button(context, "cancel");
            longLatched = false;
            HookUtils.log("stylus long action released by pen/screen state");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void button(Context context, String str) {
        HookUtils.log("stylus long button status=" + str);
        sendAll(context, new Intent("com.oplus.ipemanager.action.STYLUS_BUTTON_STATE_CHANGED").setPackage("com.oplus.healthservice").putExtra("stylus_button_status", str).addFlags(268435456), null);
    }

    private static void haptic(Context context) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService("vibrator");
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(12L, -1));
            }
        } catch (Throwable unused) {
        }
        PenHapticGatt.pulse(context, HookUtils.state(context).address);
    }

    private static void sendAll(Context context, Intent intent, String str) {
        try {
            try {
                Class<?> cls = Class.forName("android.os.UserHandle");
                Context.class.getMethod("sendBroadcastAsUser", Intent.class, cls, String.class).invoke(context, intent, cls.getField("ALL").get(null), str);
            } catch (Throwable unused) {
                if (str == null) {
                    context.sendBroadcast(intent);
                } else {
                    context.sendBroadcast(intent, str);
                }
            }
        } catch (Throwable unused2) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isPen(InputDevice inputDevice) {
        if (inputDevice == null) {
            return false;
        }
        if (inputDevice.getVendorId() == 6127) {
            for (int i : PenBridgeConstants.LENOVO_PRODUCTS) {
                if (inputDevice.getProductId() == i) {
                    return true;
                }
            }
        }
        String lowerCase = inputDevice.getName() == null ? "" : inputDevice.getName().toLowerCase();
        if (lowerCase.contains("nvtcapacitivepen") && (inputDevice.getSources() & 16386) != 0) {
            return true;
        }
        for (String str : PenBridgeConstants.LENOVO_NAMES) {
            if (lowerCase.contains(str)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void init(final Context context) {
        if (!initialized) {
            initialized = true;
            refreshContext = context;
            HookUtils.invalidateHardwareBattery(context);
            HookUtils.invalidateOemCharging(context);
            enableKernelPenWake();
            LenovoPenUEventBridge.start(context);
            registerTouchscreen(context);
            registerHapticControl(context);
            registerStateSync(context);
            registerMagneticAttachListener(context);
            LenovoConsumerGestureReader.start(context);
            Handler handler = main;
            Runnable runnable = POLL_PEN_HALL;
            handler.removeCallbacks(runnable);
            handler.post(runnable);
            handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda8
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.restorePenAfterBoot(context, 0);
                }
            }, 2000L);
            handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda9
                @Override // java.lang.Runnable
                public final void run() {
                    LenovoPenUEventBridge.wakeOemForCurrentPen(context);
                }
            }, 3500L);
            handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda10
                @Override // java.lang.Runnable
                public final void run() {
                    LenovoPenUEventBridge.wakeOemForCurrentPen(context);
                }
            }, 10000L);
            handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda11
                @Override // java.lang.Runnable
                public final void run() {
                    LenovoPenUEventBridge.wakeOemForCurrentPen(context);
                }
            }, 20000L);
            handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda12
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.syncColorOsPenState(context);
                }
            }, 1000L);
            handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda13
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.syncColorOsPenState(context);
                }
            }, 5000L);
            handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda14
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.syncColorOsPenState(context);
                }
            }, 15000L);
        }
        if (!monitorReady) {
            registerMonitor(context);
        }
    }

    static /* synthetic */ void lambda$onKernelPenAvailable$11(Context context) {
        enableKernelPenWake();
        restorePenAfterBoot(context, 0);
    }

    static void onKernelPenAvailable(final Context context) {
        main.post(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                SystemStylusHooks.lambda$onKernelPenAvailable$11(context);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized boolean enableKernelPenWake() {
        // These proc nodes are vendor DSI/panel controls.  system_server is
        // not allowed to access them on this ROM.  Leave ownership entirely
        // to the vendor driver instead of generating repeated AVC denials.
        if (!kernelWakeDisabledLogged) {
            HookUtils.log("Lenovo kernel pen wake left to vendor driver");
            kernelWakeDisabledLogged = true;
        }
        kernelWakeReady = false;
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void restorePenAfterBoot(final Context context, final int i) {
        // The Root service owns the bounded boot CONNECT_PENCIL and HID
        // sequence. The runtime switch keeps this legacy system_server retry
        // path disabled so it cannot become a second connection owner.
        if (context == null || Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_root_owns_boot", 1) == 1) {
            HookUtils.log("system_server pen restore suppressed; Root owns boot recovery");
            return;
        }
        boolean z;
        try {
            if (HookUtils.disconnectRequested(context)) {
                HookUtils.log("boot pen restore skipped: settings disconnect is latched");
                return;
            }
            BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
            if (defaultAdapter != null && defaultAdapter.getState() == 12) {
                ensureTouchscreenHaptics();
                InputManager inputManager = (InputManager) context.getSystemService("input");
                if (inputManager != null) {
                    for (int i2 : inputManager.getInputDeviceIds()) {
                        InputDevice inputDevice = inputManager.getInputDevice(i2);
                        if (((inputDevice == null || inputDevice.getName() == null) ? "" : inputDevice.getName().toLowerCase()).contains("lenovo tab pen") && (inputDevice.getSources() & 8451) != 0) {
                            z = true;
                            break;
                        }
                    }
                    z = false;
                } else {
                    z = false;
                }
                BluetoothDevice bluetoothDevice = null;
                for (BluetoothDevice bluetoothDevice2 : defaultAdapter.getBondedDevices()) {
                    String lowerCase = bluetoothDevice2.getName() == null ? "" : bluetoothDevice2.getName().toLowerCase();
                    String[] strArr = PenBridgeConstants.LENOVO_NAMES;
                    int length = strArr.length;
                    int i3 = 0;
                    while (true) {
                        if (i3 >= length) {
                            break;
                        }
                        if (lowerCase.contains(strArr[i3])) {
                            bluetoothDevice = bluetoothDevice2;
                            break;
                        }
                        i3++;
                    }
                    if (bluetoothDevice != null) {
                        break;
                    }
                }
                if (bluetoothDevice == null) {
                    HookUtils.log("boot pen restore: no bonded Lenovo pen");
                    return;
                }
                if (!z) {
                    requestHidReconnect(context, defaultAdapter, bluetoothDevice, i);
                } else {
                    HookUtils.log("boot pen restore: HID input already ready");
                }
                if (z || i >= 20) {
                    return;
                }
                main.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda6
                    @Override // java.lang.Runnable
                    public final void run() {
                        SystemStylusHooks.restorePenAfterBoot(context, i + 1);
                    }
                }, 3000L);
                return;
            }
            if (i < 5) {
                main.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda5
                    @Override // java.lang.Runnable
                    public final void run() {
                        SystemStylusHooks.restorePenAfterBoot(context, i + 1);
                    }
                }, 2000L);
            }
        } catch (Throwable th) {
            HookUtils.log("boot pen restore: " + th);
            if (i < 20) {
                main.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda7
                    @Override // java.lang.Runnable
                    public final void run() {
                        SystemStylusHooks.restorePenAfterBoot(context, i + 1);
                    }
                }, 3000L);
            }
        }
    }

    private static synchronized void requestHidReconnect(Context context, final BluetoothAdapter bluetoothAdapter, final BluetoothDevice bluetoothDevice, final int i) {
        if (hidConnectPending) {
            return;
        }
        hidConnectPending = true;
        Handler handler = main;
        Runnable runnable = HID_CONNECT_TIMEOUT;
        handler.removeCallbacks(runnable);
        handler.postDelayed(runnable, 7000L);
        try {
            if (!bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks.4
                /* JADX WARN: Removed duplicated region for block: B:19:0x004e  */
                @Override // android.bluetooth.BluetoothProfile.ServiceListener
                public void onServiceConnected(int i2, BluetoothProfile bluetoothProfile) {
                    int iState = 0;
                    try {
                        Method methodGetState = null;
                        for (Method method : bluetoothProfile.getClass().getMethods()) {
                            if ("getConnectionState".equals(method.getName()) && method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == BluetoothDevice.class) {
                                methodGetState = method;
                                break;
                            }
                        }
                        if (methodGetState != null) {
                            methodGetState.setAccessible(true);
                            Object objState = methodGetState.invoke(bluetoothProfile, bluetoothDevice);
                            if (objState instanceof Number) {
                                iState = ((Number) objState).intValue();
                            }
                        }
                        if (iState == 2) {
                            HookUtils.log("boot pen HID Host already connected");
                        } else if (iState == 1) {
                            HookUtils.log("boot pen HID Host still connecting attempt=" + i);
                        } else {
                            Method methodConnect = null;
                            for (Method method2 : bluetoothProfile.getClass().getMethods()) {
                                if ("connect".equals(method2.getName()) && method2.getParameterTypes().length == 1 && method2.getParameterTypes()[0] == BluetoothDevice.class) {
                                    methodConnect = method2;
                                    break;
                                }
                            }
                            if (methodConnect == null) {
                                throw new NoSuchMethodException("BluetoothHidHost.connect");
                            }
                            methodConnect.setAccessible(true);
                            Object objResult = methodConnect.invoke(bluetoothProfile, bluetoothDevice);
                            HookUtils.log("boot pen HID Host connect requested attempt=" + i + " result=" + objResult);
                        }
                    } catch (Throwable th) {
                        HookUtils.log("boot pen HID Host connect failed: " + th);
                    }
                    try {
                        bluetoothAdapter.closeProfileProxy(HID_HOST_PROFILE, bluetoothProfile);
                    } catch (Throwable unused) {
                    }
                    synchronized (SystemStylusHooks.class) {
                        SystemStylusHooks.main.removeCallbacks(SystemStylusHooks.HID_CONNECT_TIMEOUT);
                        boolean unused2 = SystemStylusHooks.hidConnectPending = false;
                    }
                }

                @Override // android.bluetooth.BluetoothProfile.ServiceListener
                public void onServiceDisconnected(int i2) {
                    synchronized (SystemStylusHooks.class) {
                        SystemStylusHooks.main.removeCallbacks(SystemStylusHooks.HID_CONNECT_TIMEOUT);
                        boolean unused = SystemStylusHooks.hidConnectPending = false;
                    }
                }
            }, HID_HOST_PROFILE)) {
                handler.removeCallbacks(runnable);
                hidConnectPending = false;
                HookUtils.log("boot pen HID Host profile unavailable attempt=" + i);
            }
        } catch (Throwable th) {
            main.removeCallbacks(HID_CONNECT_TIMEOUT);
            hidConnectPending = false;
            HookUtils.log("boot pen HID Host request failed: " + th);
        }
    }

    private static synchronized void registerMagneticAttachListener(Context context) {
        InputManager inputManager;
        if (magneticListenerReady) {
            return;
        }
        try {
            inputManager = (InputManager) context.getSystemService("input");
        } catch (Throwable th) {
            HookUtils.log("NVT magnetic listener: " + th);
            return;
        }
        if (inputManager == null) {
            return;
        }
        for (int i : inputManager.getInputDeviceIds()) {
            if (isNvtPen(inputManager.getInputDevice(i))) {
                nvtDeviceIds.add(Integer.valueOf(i));
            }
        }
        inputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks.5
            @Override // android.hardware.input.InputManager.InputDeviceListener
            public void onInputDeviceChanged(int i2) {
            }

            @Override // android.hardware.input.InputManager.InputDeviceListener
            public void onInputDeviceAdded(int i2) {
                if (SystemStylusHooks.isNvtPen(inputManager.getInputDevice(i2))) {
                    synchronized (SystemStylusHooks.class) {
                        SystemStylusHooks.nvtDeviceIds.add(Integer.valueOf(i2));
                        long unused = SystemStylusHooks.suppressPenKeysUntil = Math.max(SystemStylusHooks.suppressPenKeysUntil, SystemClock.uptimeMillis() + 900);
                    }
                }
            }

            @Override // android.hardware.input.InputManager.InputDeviceListener
            public void onInputDeviceRemoved(int i2) {
                synchronized (SystemStylusHooks.class) {
                    if (SystemStylusHooks.nvtDeviceIds.remove(Integer.valueOf(i2))) {
                        long unused = SystemStylusHooks.suppressPenKeysUntil = Math.max(SystemStylusHooks.suppressPenKeysUntil, SystemClock.uptimeMillis() + 1200);
                    }
                }
            }
        }, main);
        magneticListenerReady = true;
        HookUtils.log("NVT transition suppressor registered");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isNvtPen(InputDevice inputDevice) {
        return (inputDevice == null || inputDevice.getName() == null || !"NVTCapacitivePen".equalsIgnoreCase(inputDevice.getName()) || (inputDevice.getSources() & 16386) == 0) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void pollPenHall(Context context) {
        int i;
        int i3 = readInt(PEN1_HALL, -1);
        int i4 = readInt(PEN2_HALL, -1);
        if (i3 < 0 && i4 < 0) {
            if (hallReadFailed) {
                return;
            }
            hallReadFailed = true;
            HookUtils.log("Lenovo pen hall nodes are not readable");
            return;
        }
        hallReadFailed = false;
        int oemWirelessPenPresent = readOemWirelessPenPresent();
        if (oemWirelessPenPresent == 0 || oemWirelessPenPresent == 1) {
            i = oemWirelessPenPresent == 1 ? 0 : 1;
        } else if (i3 == 0 && i4 == 1) {
            i = 1;
        } else if (i3 == 1 && i4 == 1) {
            i = 0;
        } else {
            return;
        }
        if (i != hallCandidate) {
            hallCandidate = i;
            hallCandidateSamples = 1;
            HookUtils.log("Lenovo pen hall raw=" + i3 + "," + i4);
            return;
        }
        int i5 = hallCandidateSamples;
        if (i5 < 3) {
            hallCandidateSamples = i5 + 1;
        }
        int i2 = lastPenHall;
        if (hallCandidateSamples < 3 || i == i2) {
            return;
        }
        boolean z = i2 >= 0;
        lastPenHall = i;
        HookUtils.log("Lenovo pen hall stable raw=" + i3 + "," + i4);
        applyPenHall(context, i, z);
    }

    private static int readInt(String str, int i) {
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader(str));
            try {
                String line = bufferedReader.readLine();
                int i2 = line == null ? i : Integer.parseInt(line.trim());
                bufferedReader.close();
                return i2;
            } finally {
            }
        } catch (Throwable unused) {
            return i;
        }
    }

    private static int readOemWirelessPenPresent() {
        long jUptimeMillis = SystemClock.uptimeMillis();
        if (jUptimeMillis - lastOemPresentAt < 250) {
            return lastOemPresent;
        }
        lastOemPresentAt = jUptimeMillis;
        try {
            Object objNewInstance = Class.forName("android.os.OplusBatteryManager").getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
            Method method = objNewInstance.getClass().getMethod("getWirelessPenPresent", new Class[0]);
            method.setAccessible(true);
            Object objInvoke = method.invoke(objNewInstance, new Object[0]);
            int iIntValue = objInvoke instanceof Number ? ((Number) objInvoke).intValue() : -1;
            if (iIntValue != 0 && iIntValue != 1) {
                iIntValue = -1;
            }
            lastOemPresent = iIntValue;
            if (iIntValue != lastLoggedOemPresent) {
                lastLoggedOemPresent = iIntValue;
                HookUtils.log("Lenovo OEM wireless pen present=" + iIntValue);
            }
            return iIntValue;
        } catch (Throwable unused) {
            lastOemPresent = -1;
            return -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void applyPenHall(final Context context, int i, boolean z) {
        boolean z2 = i == 0;
        suppressPenKeysUntil = Math.max(suppressPenKeysUntil, SystemClock.uptimeMillis() + 1200);
        if (z2) {
            releaseLong(context);
            PenHapticGatt.disconnect();
            setPenTouchpadEnabled(context, false);
            setRefreshActive(context, false);
            if (z) {
                main.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda20
                    @Override // java.lang.Runnable
                    public final void run() {
                        SystemStylusHooks.showDockCapsule(context, 0);
                    }
                }, 350L);
            }
        } else {
            setPenTouchpadEnabled(context, true);
            setRefreshActive(context, false);
        }
        PenBridgeReceiver.publishPhysicalEdge(context, z2);
        main.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda21
            @Override // java.lang.Runnable
            public final void run() {
                SystemStylusHooks.syncColorOsPenState(context);
            }
        }, 80L);
        HookUtils.log("Lenovo pen hall state=" + i + " (" + (z2 ? "docked" : "undocked") + ")");
    }

    /** 吸附时禁用 NVTCapacitivePen 触控板输入（防误触），取下时恢复。 */
    private static void setPenTouchpadEnabled(Context context, boolean enabled) {
        try {
            InputManager inputManager = (InputManager) context.getSystemService("input");
            if (inputManager == null) {
                return;
            }
            Method mEnable = null;
            Method mDisable = null;
            Method m2 = null;
            Method m3 = null;
            try {
                mEnable = InputManager.class.getMethod("enableInputDevice", int.class);
            } catch (Throwable t) {
                // ignore
            }
            try {
                mDisable = InputManager.class.getMethod("disableInputDevice", int.class);
            } catch (Throwable t) {
                // ignore
            }
            try {
                m2 = InputManager.class.getMethod("setInputDeviceEnabled", int.class, boolean.class);
            } catch (Throwable t) {
                // ignore
            }
            try {
                m3 = InputManager.class.getMethod("setInputDeviceEnabled", int.class, int.class, boolean.class);
            } catch (Throwable t) {
                // ignore
            }
            if (mEnable == null && mDisable == null && m2 == null && m3 == null) {
                HookUtils.log("NVT touchpad API missing");
                return;
            }
            synchronized (SystemStylusHooks.class) {
                for (Integer id : nvtDeviceIds) {
                    try {
                        if (enabled) {
                            if (mEnable != null) {
                                mEnable.invoke(inputManager, id);
                            } else if (m2 != null) {
                                m2.invoke(inputManager, id, true);
                            } else {
                                m3.invoke(inputManager, id, 0, true);
                            }
                        } else if (mDisable != null) {
                            mDisable.invoke(inputManager, id);
                        } else if (m2 != null) {
                            m2.invoke(inputManager, id, false);
                        } else {
                            m3.invoke(inputManager, id, 0, false);
                        }
                    } catch (Throwable t) {
                        HookUtils.log("NVT touchpad device " + id + " set failed: " + t);
                    }
                }
            }
            HookUtils.log("NVT pen touchpad " + (enabled ? "enabled" : "disabled") + " devices=" + nvtDeviceIds.size());
        } catch (Throwable t) {
            HookUtils.log("NVT touchpad control failed: " + t);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void showDockCapsule(final Context context, final int i) {
        if (lastPenHall != 0) {
            return;
        }
        int iBatteryForCapsule = HookUtils.batteryForCapsule(context);
        if (iBatteryForCapsule >= 0) {
            PenState penStateState = HookUtils.state(context);
            sendAll(context, new Intent("com.aclaniakea.lenovopenbridge.action.SHOW_PENCIL_CAPSULE").setPackage("com.oplus.ipemanager").putExtra("battery_level", iBatteryForCapsule).putExtra("charging_state", penStateState.charging).putExtra("chargingState", penStateState.charging).putExtra("charging", penStateState.charging).putExtra("present", penStateState.connected ? "1" : "0").putExtra("macAddr", penStateState.macNoColon()).putExtra("source", "lenovo_pen_hall_validated"), null);
            HookUtils.log("validated Hall magnetic capsule requested: battery=" + iBatteryForCapsule + " charging=" + penStateState.charging);
        } else if (i < 10) {
            main.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.showDockCapsule(context, i + 1);
                }
            }, 500L);
        } else {
            HookUtils.log("magnetic capsule skipped: battery still unknown after boot");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Type inference failed for: r7v1, types: [boolean, int] */
    /* JADX WARN: Type inference failed for: r7v3 */
    /* JADX WARN: Type inference failed for: r7v4 */
    /* JADX WARN: Type inference failed for: r7v5 */
    public static synchronized void setRefreshActive(Context context, boolean z) {
        refreshContext = context;
        if (z && HookUtils.disconnectRequested(context)) {
            z = false;
        }
        if (refreshActive == z && Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_refresh_active", -1) == (z ? 1 : 0)) {
            return;
        }
        refreshActive = z;
        try {
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_refresh_active", z ? 1 : 0);
            HookUtils.log("adaptive pencil 120 Hz ".concat(z ? "enabled" : "released"));
        } catch (Throwable th) {
            HookUtils.log("adaptive pencil refresh: " + th);
        }
    }

    /* renamed from: com.aclaniakea.colorosporttuning.SystemStylusHooks$6, reason: invalid class name */
    static class AnonymousClass6 extends BroadcastReceiver {
        final /* synthetic */ Context val$c;

        AnonymousClass6(Context context) {
            this.val$c = context;
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if ("android.intent.action.USER_UNLOCKED".equals(action)) {
                SystemStylusHooks.enableKernelPenWake();
                Handler handler2 = SystemStylusHooks.main;
                final Context context2 = this.val$c;
                handler2.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$6$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        LenovoPenUEventBridge.wakeOemForCurrentPen(context2);
                    }
                }, 350L);
                HookUtils.log("user unlocked: replaying OEM pen boot wake");
            } else if ("android.intent.action.SCREEN_OFF".equals(action)) {
                boolean unused = SystemStylusHooks.screenOn = false;
                SystemStylusHooks.main.removeCallbacks(SystemStylusHooks.SCREEN_ON_REPLAY);
                SystemStylusHooks.screenReplayContext = null;
                SystemStylusHooks.releaseLong(this.val$c);
                SystemStylusHooks.setRefreshActive(this.val$c, false);
            } else if ("android.intent.action.SCREEN_ON".equals(action)) {
                boolean unused2 = SystemStylusHooks.screenOn = true;
                SystemStylusHooks.screenReplayContext = this.val$c;
                SystemStylusHooks.main.removeCallbacks(SystemStylusHooks.SCREEN_ON_REPLAY);
                SystemStylusHooks.main.postDelayed(SystemStylusHooks.SCREEN_ON_REPLAY, 1200L);
                HookUtils.log("screen-on pen state replay delayed until panel settles");
            } else if ("com.aclaniakea.lenovopenbridge.action.RECONNECT_PEN".equals(action)) {
                    try {
                        Settings.Global.putInt(this.val$c.getContentResolver(), "lenovo_pen_disconnect_requested", 0);
                        Settings.Global.putInt(this.val$c.getContentResolver(), "lenovo_pen_user_disconnect_requested", 0);
                    } catch (Throwable unused3) {
                    }
                    SystemStylusHooks.enableKernelPenWake();
                    HookUtils.log("root pen reconnect requested");
                    Handler handler3 = SystemStylusHooks.main;
                    final Context context3 = this.val$c;
                    handler3.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$6$$ExternalSyntheticLambda1
                        @Override // java.lang.Runnable
                        public final void run() {
                            SystemStylusHooks.restorePenAfterBoot(context3, 0);
                        }
                    }, 250L);
            } else if ("android.bluetooth.adapter.action.STATE_CHANGED".equals(action)) {
                SystemStylusHooks.releaseLong(this.val$c);
                if (intent.getIntExtra("android.bluetooth.adapter.extra.STATE", Integer.MIN_VALUE) == 12) {
                    SystemStylusHooks.enableKernelPenWake();
                    Handler handler4 = SystemStylusHooks.main;
                    final Context context4 = this.val$c;
                    handler4.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$6$$ExternalSyntheticLambda2
                            @Override // java.lang.Runnable
                            public final void run() {
                                LenovoPenUEventBridge.wakeOemForCurrentPen(context4);
                            }
                        }, 500L);
                    Handler handler5 = SystemStylusHooks.main;
                    final Context context5 = this.val$c;
                    handler5.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$6$$ExternalSyntheticLambda3
                            @Override // java.lang.Runnable
                            public final void run() {
                                SystemStylusHooks.restorePenAfterBoot(context5, 0);
                            }
                        }, 1200L);
                }
            } else if ("android.bluetooth.device.action.ACL_DISCONNECTED".equals(action)) {
                SystemStylusHooks.releaseLong(this.val$c);
            }
            Handler handler6 = SystemStylusHooks.main;
            final Context context6 = this.val$c;
            handler6.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$6$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.syncColorOsPenState(context6);
                }
            }, 600L);
        }
    }

    private static void registerStateSync(Context context) {
        if (stateReceiverReady) {
            return;
        }
        stateReceiverReady = true;
        AnonymousClass6 anonymousClass6 = new AnonymousClass6(context);
        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("android.intent.action.USER_UNLOCKED");
            intentFilter.addAction("android.intent.action.SCREEN_ON");
            intentFilter.addAction("android.intent.action.SCREEN_OFF");
            intentFilter.addAction("com.aclaniakea.lenovopenbridge.action.RECONNECT_PEN");
            intentFilter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
            intentFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
            intentFilter.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
            intentFilter.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(anonymousClass6, intentFilter, 2);
            } else {
                context.registerReceiver(anonymousClass6, intentFilter);
            }
        } catch (Throwable th) {
            HookUtils.log("pen state receiver: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void syncColorOsPenState(Context context) {
        try {
            PenBridgeReceiver.publishCurrentHardwareState(context, "hardware_snapshot");
            PenState penStateState = HookUtils.state(context);
            HookUtils.log("ColorOS pen state synced: connected=" + penStateState.connected + " battery=" + penStateState.battery + " charging=" + penStateState.charging);
        } catch (Throwable th) {
            HookUtils.log("ColorOS pen state sync: " + th);
        }
    }

    private static void registerTouchscreen(Context context) {
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks.7
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("com.aclaniakea.lenovopenbridge.haptic.TOUCHSCREEN".equals(intent.getAction())) {
                    SystemStylusHooks.setTouchscreen(intent.getBooleanExtra("enabled", true));
                }
            }
        };
        try {
            IntentFilter intentFilter = new IntentFilter("com.aclaniakea.lenovopenbridge.haptic.TOUCHSCREEN");
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(broadcastReceiver, intentFilter, 2);
            } else {
                context.registerReceiver(broadcastReceiver, intentFilter);
            }
        } catch (Throwable th) {
            HookUtils.log("touchscreen receiver: " + th);
        }
    }

    private static synchronized void registerHapticControl(final Context context) {
        if (hapticControlReady) {
            return;
        }
        try {
            BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks.8
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    if (intent == null || !"com.aclaniakea.lenovopenbridge.haptic.COMMAND".equals(intent.getAction())) {
                        return;
                    }
                    boolean booleanExtra = intent.getBooleanExtra("enabled", Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_global_writing_haptic", 1) != 0);
                    PenHapticGatt.setWritingEnabled(context, HookUtils.state(context).address, booleanExtra);
                    if (!booleanExtra) {
                        SystemStylusHooks.stopWriting();
                    }
                    HookUtils.log("writing haptic runtime control=" + booleanExtra);
                }
            };
            IntentFilter intentFilter = new IntentFilter("com.aclaniakea.lenovopenbridge.haptic.COMMAND");
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(broadcastReceiver, intentFilter, 2);
            } else {
                context.registerReceiver(broadcastReceiver, intentFilter);
            }
            hapticControlReady = true;
            if (Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_global_writing_haptic", 1) != 0) {
                PenHapticGatt.setWritingEnabled(context, HookUtils.state(context).address, true);
            }
        } catch (Throwable th) {
            HookUtils.log("haptic control receiver: " + th);
        }
    }

    static boolean setTouchscreen(boolean z) {
        Parcel parcelObtain = Parcel.obtain();
        Parcel parcelObtain2 = Parcel.obtain();
        try {
            suppressNvtHotplugUntil = SystemClock.uptimeMillis() + 1200;
            suppressPenKeysUntil = Math.max(suppressPenKeysUntil, SystemClock.uptimeMillis() + 1200);
            IBinder iBinder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "vendor.lenovo.hardware.touchscreen.ITouchscreen/default");
            if (iBinder != null) {
                parcelObtain.writeInterfaceToken("vendor.lenovo.hardware.touchscreen.ITouchscreen");
                parcelObtain.writeInt(z ? 1 : 0);
                boolean zTransact = iBinder.transact(14, parcelObtain, parcelObtain2, 0);
                if (zTransact) {
                    try {
                        parcelObtain2.readException();
                    } catch (Throwable unused) {
                    }
                }
                return zTransact;
            }
            return false;
        } catch (Throwable th) {
            HookUtils.log("touchscreen binder: " + th);
            return false;
        } finally {
            parcelObtain.recycle();
            parcelObtain2.recycle();
        }
    }

    static synchronized boolean ensureTouchscreenHaptics() {
        if (touchscreenHapticsReady) {
            return true;
        }
        boolean touchscreen = setTouchscreen(true);
        if (touchscreen) {
            touchscreenHapticsReady = true;
            HookUtils.log("Lenovo touchscreen haptics initialized once");
        }
        return touchscreen;
    }

    private static void registerMonitor(final Context context) {
        try {
            Object systemService = context.getSystemService("input");
            Object objInvoke = systemService.getClass().getMethod("monitorGestureInput", String.class, Integer.TYPE).invoke(systemService, "LenovoPenGlobalHaptic", 0);
            inputMonitor = objInvoke;
            Object objInvoke2 = objInvoke.getClass().getMethod("getInputChannel", new Class[0]).invoke(inputMonitor, new Object[0]);
            Class<?> cls = Class.forName("android.view.BatchedInputEventReceiver$SimpleBatchedInputEventReceiver");
            Class<?> cls2 = Class.forName("android.view.InputChannel");
            Class<?> cls3 = Class.forName("android.view.BatchedInputEventReceiver$SimpleBatchedInputEventReceiver$InputEventListener");
            inputReceiver = cls.getConstructor(cls2, Looper.class, Choreographer.class, cls3).newInstance(objInvoke2, Looper.getMainLooper(), Choreographer.getInstance(), Proxy.newProxyInstance(cls3.getClassLoader(), new Class[]{cls3}, new InvocationHandler() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda3
                @Override // java.lang.reflect.InvocationHandler
                public final Object invoke(Object obj, Method method, Object[] objArr) throws Throwable {
                    return SystemStylusHooks.lambda$registerMonitor$18(context, obj, method, objArr);
                }
            }));
            monitorReady = true;
            HookUtils.log("global stylus input monitor registered");
        } catch (Throwable th) {
            HookUtils.log("global input monitor unavailable: " + th);
        }
    }

    static /* synthetic */ Object lambda$registerMonitor$18(Context context, Object obj, Method method, Object[] objArr) throws Throwable {
        if ("onInputEvent".equals(method.getName()) && objArr != null && objArr.length > 0) {
            Object obj2 = objArr[0];
            if (obj2 instanceof MotionEvent) {
                return Boolean.valueOf(onMotion(context, (MotionEvent) obj2));
            }
        }
        return false;
    }

    private static boolean onMotion(Context context, MotionEvent motionEvent) {
        InputDevice device = motionEvent.getDevice();
        if (((device == null || device.getName() == null) ? "" : device.getName().toLowerCase()).contains("lenovo tab pen") && (device.getSources() & 8194) != 0) {
            return false;
        }
        for (int i = 0; i < motionEvent.getPointerCount(); i++) {
            int toolType = motionEvent.getToolType(i);
            if (toolType == 2 || toolType == HID_HOST_PROFILE) {
                if (!HookUtils.state(context).connected || HookUtils.disconnectRequested(context)) {
                    lastButtons = 0;
                    if (writing) {
                        stopWriting();
                    }
                    return false;
                }
                int buttonState = motionEvent.getButtonState();
                int i2 = (~lastButtons) & buttonState;
                lastButtons = buttonState;
                long jUptimeMillis = SystemClock.uptimeMillis();
                if (i2 != 0 && jUptimeMillis - lastButtonAt > 250) {
                    if ((i2 & 32) != 0) {
                        lastButtonAt = jUptimeMillis;
                        click(context, false);
                    } else if ((i2 & 64) != 0) {
                        lastButtonAt = jUptimeMillis;
                        click(context, true);
                    }
                }
                int actionMasked = motionEvent.getActionMasked();
                if (Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_global_writing_haptic", 1) == 0) {
                    if (actionMasked == 0 || (actionMasked == 2 && motionEvent.getPressure() > 0.0f)) {
                        setRefreshActive(context, true);
                        main.removeCallbacks(RELEASE_REFRESH);
                    } else if (actionMasked == 1 || actionMasked == 3) {
                        main.postDelayed(RELEASE_REFRESH, 1000L);
                    }
                    if (writing) {
                        stopWriting();
                    }
                    return false;
                }
                if (actionMasked == 0 || (actionMasked == 2 && motionEvent.getPressure() > 0.0f)) {
                    setRefreshActive(context, true);
                    main.removeCallbacks(RELEASE_REFRESH);
                    if (actionMasked == 0) {
                        consumeLongLatch();
                    }
                    main.removeCallbacks(STOP_WRITING);
                    if (!writing) {
                        writing = true;
                        PenHapticGatt.startWriting(context, HookUtils.state(context).address, motionEvent.getToolType(0));
                        HookUtils.log("start writing haptic pressure=" + motionEvent.getPressure());
                    }
                } else if (actionMasked == 1 || actionMasked == 3 || writing) {
                    if (actionMasked == 1 || actionMasked == 3) {
                        main.postDelayed(RELEASE_REFRESH, 1000L);
                    }
                    scheduleStopWriting();
                }
                return false;
            }
        }
        return false;
    }

    private static synchronized void scheduleStopWriting() {
        Handler handler = main;
        Runnable runnable = STOP_WRITING;
        handler.removeCallbacks(runnable);
        handler.postDelayed(runnable, 220L);
    }

    static /* synthetic */ void lambda$onConsumerGesture$19(int i, Context context) {
        if (SystemClock.uptimeMillis() < suppressPenKeysUntil) {
            HookUtils.log("suppressed touch strip event during magnetic transition usage=0x" + Integer.toHexString(i));
        }
        switch (i) {
            case 787969:
                HookUtils.log("grabbed touch strip: double tap -> double action");
                click(context, true);
                break;
            case 787986:
                HookUtils.log("grabbed touch strip: swipe down -> single action");
                click(context, false);
                break;
            case 787987:
                HookUtils.log("grabbed touch strip: swipe up -> long action");
                longAction(context);
                break;
            default:
                HookUtils.log("grabbed touch strip: unknown usage=0x" + Integer.toHexString(i));
                break;
        }
    }

    static void onConsumerGesture(final Context context, final int i) {
        main.post(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda18
            @Override // java.lang.Runnable
            public final void run() {
                SystemStylusHooks.lambda$onConsumerGesture$19(i, context);
            }
        });
    }

    private static void injectNativePenKey(final Context context, final int i) {
        if (context == null) {
            return;
        }
        final long jUptimeMillis = SystemClock.uptimeMillis();
        if (inject(context, new KeyEvent(jUptimeMillis, jUptimeMillis, 0, i, 0, 0, -1, 0, 72, 257))) {
            main.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.SystemStylusHooks$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    SystemStylusHooks.lambda$injectNativePenKey$20(jUptimeMillis, i, context);
                }
            }, 60L);
        } else {
            HookUtils.log("native pen key down failed code=" + i);
        }
    }

    static /* synthetic */ void lambda$injectNativePenKey$20(long j, int i, Context context) {
        if (inject(context, new KeyEvent(j, SystemClock.uptimeMillis(), 1, i, 0, 0, -1, 0, 72, 257))) {
            return;
        }
        HookUtils.log("native pen key up failed code=" + i);
    }

    private static boolean inject(Context context, InputEvent inputEvent) {
        try {
            Object systemService = context.getSystemService("input");
            if (systemService == null) {
                return false;
            }
            for (Method method : systemService.getClass().getMethods()) {
                if ("injectInputEvent".equals(method.getName()) && method.getParameterTypes().length == 2) {
                    method.setAccessible(true);
                    Object objInvoke = method.invoke(systemService, inputEvent, 0);
                    if (objInvoke instanceof Boolean) {
                        if (!((Boolean) objInvoke).booleanValue()) {
                            return false;
                        }
                    }
                    return true;
                }
            }
            return false;
        } catch (Throwable th) {
            HookUtils.log("native pen key injection: " + th);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void stopWriting() {
        main.removeCallbacks(STOP_WRITING);
        if (writing) {
            writing = false;
            PenHapticGatt.stopWriting();
            HookUtils.log("stop writing haptic");
        }
    }

    private SystemStylusHooks() {
    }
}
