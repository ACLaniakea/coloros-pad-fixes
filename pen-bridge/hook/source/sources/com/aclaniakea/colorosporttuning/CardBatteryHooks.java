package com.aclaniakea.colorosporttuning;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes.dex */
final class CardBatteryHooks {
    private static final int ID_BATTERY_TEXT = 0x7f09009e;
    private static final int ID_BATTERY_VIEW = 0x7f09009f;
    private static final int ID_SINGLE_BATTERY = 0x7f0903b6;
    private static final int ID_STATUS_CONTAINER = 0x7f0903e9;
    private static final int ID_STATUS_TEXT = 0x7f0903eb;
    private static ClassLoader appLoader;
    private static final Object[] lastValues = new Object[3];
    private static boolean pollerStarted;
    private static volatile Activity topActivity;

    static void install(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        appLoader = loadPackageParam.classLoader;
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData", "getBatteryMain", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.CardBatteryHooks.1
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    Object obj = methodHookParam.thisObject;
                    if (obj == null) {
                        return;
                    }
                    String strName = String.valueOf(HookUtils.call(obj, "getName")).toLowerCase();
                    if (!strName.contains("lenovo") || !strName.contains("pen")) {
                        return;
                    }
                    Context context = HookUtils.context(obj);
                    if (context == null) {
                        return;
                    }
                    PenState penState = HookUtils.state(context);
                    if (penState == null) {
                        return;
                    }
                    methodHookParam.setResult(buildBatteryInfo(penState.battery, penState.charging != 0));
                } catch (Throwable th) {
                    HookUtils.log("CardBatteryHooks battery: " + th);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData", "getDisplayState", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.CardBatteryHooks.2
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    Object obj = methodHookParam.thisObject;
                    if (obj == null) {
                        return;
                    }
                    String strName = String.valueOf(HookUtils.call(obj, "getName")).toLowerCase();
                    if (!strName.contains("lenovo") || !strName.contains("pen")) {
                        return;
                    }
                    Context context = HookUtils.context(obj);
                    if (context == null) {
                        return;
                    }
                    PenState penState = HookUtils.state(context);
                    if (penState == null) {
                        return;
                    }
                    Object result = methodHookParam.getResult();
                    if (result == null) {
                        return;
                    }
                    Object indicatorType = HookUtils.call(result, "getIndicatorType");
                    Object indicatorLightColor = HookUtils.call(result, "getIndicatorLightColor");
                    Object connectStateCategory = HookUtils.call(result, "getConnectStateCategory");
                    Object statusIconType = HookUtils.call(result, "getStatusIconType");
                    Class<?> clsSubTitle = Class.forName("com.oplus.mydevices.domain.entities.cards.SubTitleDisplayMode", false, appLoader);
                    Enum<?> subTitleMode = Enum.valueOf(clsSubTitle.asSubclass(Enum.class), penState.connected ? "BATTERY_ONLY" : "TITLE_ONLY");
                    Class<?> clsDisplay = result.getClass();
                    Constructor<?> constructor = clsDisplay.getConstructor(indicatorType.getClass(), indicatorLightColor.getClass(), clsSubTitle, connectStateCategory.getClass(), statusIconType.getClass());
                    methodHookParam.setResult(constructor.newInstance(indicatorType, indicatorLightColor, subTitleMode, connectStateCategory, statusIconType));
                } catch (Throwable th) {
                    HookUtils.log("CardBatteryHooks state: " + th);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView", "setBatteryInfo", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.CardBatteryHooks.3
            @Override
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                try {
                    if (methodHookParam.args == null || methodHookParam.args.length == 0 || methodHookParam.args[0] == null) {
                        return;
                    }
                    Object batteryInfo = methodHookParam.args[0];
                    Context context = HookUtils.context(methodHookParam.thisObject);
                    if (context == null) {
                        return;
                    }
                    PenState penState = HookUtils.state(context);
                    if (penState == null || penState.battery < 0) {
                        return;
                    }
                    Class<?> cls = batteryInfo.getClass();
                    Field fieldValue = cls.getDeclaredField("value");
                    fieldValue.setAccessible(true);
                    if (fieldValue.getInt(batteryInfo) != penState.battery) {
                        return;
                    }
                    fieldValue.setInt(batteryInfo, penState.battery);
                    Field fieldCharge = cls.getDeclaredField("charge");
                    fieldCharge.setAccessible(true);
                    fieldCharge.setBoolean(batteryInfo, penState.charging != 0);
                    fixCardDisplay(methodHookParam.thisObject, penState);
                } catch (Throwable th) {
                    HookUtils.log("CardBatteryHooks setBatteryInfo: " + th);
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.deviceui.devicecard.DeviceCardHomeActivity", "onResume", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.CardBatteryHooks.4
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                if (!(methodHookParam.thisObject instanceof Activity)) {
                    CardBatteryHooks.topActivity = (Activity) methodHookParam.thisObject;
                }
            }
        });
        HookUtils.hookAll(loadPackageParam.classLoader, "com.oplus.mydevices.deviceui.devicecard.DeviceCardHomeActivity", "onPause", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.CardBatteryHooks.5
            @Override
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                if (methodHookParam.thisObject == CardBatteryHooks.topActivity) {
                    CardBatteryHooks.topActivity = null;
                }
            }
        });
        if (!pollerStarted) {
            pollerStarted = true;
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() { // from class: com.aclaniakea.colorosporttuning.CardBatteryHooks.6
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        CardBatteryHooks.pollOnce();
                    } catch (Throwable th) {
                        HookUtils.log("CardBatteryHooks poll: " + th);
                    }
                    handler.postDelayed(this, 400L);
                }
            });
        }
        HookUtils.log("CardBatteryHooks installed");
    }

    private static Object buildBatteryInfo(int i, boolean z) throws Exception {
        Class<?> clsInfo = Class.forName("com.oplus.mydevices.domain.entities.device.BatteryInfo", false, appLoader);
        Class<?> clsType = Class.forName("com.oplus.mydevices.domain.entities.device.BatteryType", false, appLoader);
        Enum<?> single = Enum.valueOf(clsType.asSubclass(Enum.class), "SINGLE");
        Constructor<?> constructor = clsInfo.getConstructor(clsType, Integer.TYPE, Boolean.TYPE);
        return constructor.newInstance(single, Integer.valueOf(i < 0 ? 0 : i), Boolean.valueOf(z));
    }

    private static void collectTextViews(View view, String str, List<View> list) {
        if (view == null) {
            return;
        }
        if (view instanceof TextView) {
            CharSequence charSequence = ((TextView) view).getText();
            if (charSequence != null && str.equals(charSequence.toString())) {
                list.add(view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                collectTextViews(viewGroup.getChildAt(i), str, list);
            }
        }
    }

    private static View findCardRoot(View view) {
        while (view != null) {
            if (view.findViewById(0x7f0903b6) != null) {
                return view;
            }
            ViewParent viewParent = view.getParent();
            if (!(viewParent instanceof View)) {
                break;
            }
            view = (View) viewParent;
        }
        return null;
    }

    private static void fixCardDisplay(Object obj, PenState penState) {
        try {
            if (!(obj instanceof View)) {
                return;
            }
            View view = findCardRoot((View) obj);
            if (view == null) {
                return;
            }
            boolean zConnected = penState.connected;
            View singleBattery = view.findViewById(0x7f0903b6);
            View statusContainer = view.findViewById(0x7f0903e9);
            View statusText = view.findViewById(0x7f0903eb);
            View batteryText = view.findViewById(0x7f09009e);
            if (singleBattery != null) {
                singleBattery.setVisibility(zConnected ? 0 : 8);
            }
            if (statusContainer != null) {
                statusContainer.setVisibility(zConnected ? 8 : 0);
            }
            if (statusText instanceof TextView) {
                ((TextView) statusText).setText(zConnected ? "" : "未连接");
            }
            if ((batteryText instanceof TextView) && penState.battery >= 0) {
                ((TextView) batteryText).setText(String.valueOf(penState.battery));
            }
        } catch (Throwable th) {
            HookUtils.log("CardBatteryHooks fixCard: " + th);
        }
    }

    private static void pollOnce() {
        Context context = HookUtils.context(null);
        if (context == null) {
            return;
        }
        int charging = 0;
        try {
            charging = Settings.Global.getInt(context.getContentResolver(), "ipe_pencil_charging_state", 0);
        } catch (Throwable unused) {
        }
        int battery = -1;
        try {
            battery = Settings.Global.getInt(context.getContentResolver(), "ipe_pencil_battery_level", -1);
        } catch (Throwable unused2) {
        }
        int connected = 1;
        try {
            connected = Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_link_connected", 1);
        } catch (Throwable unused3) {
        }
        boolean changed = lastValues[0] == null || !lastValues[0].equals(Integer.valueOf(charging)) || !lastValues[1].equals(Integer.valueOf(battery)) || !lastValues[2].equals(Integer.valueOf(connected));
        Activity activity = topActivity;
        if (activity != null) {
            refreshPenCard(activity, charging, battery, connected);
        }
        if (changed) {
            lastValues[0] = Integer.valueOf(charging);
            lastValues[1] = Integer.valueOf(battery);
            lastValues[2] = Integer.valueOf(connected);
        }
    }

    private static void refreshPenCard(Activity activity, int charging, int battery, int connected) {
        try {
            View decorView = activity.getWindow().getDecorView();
            if (decorView == null) {
                return;
            }
            List<View> list = new ArrayList<>();
            collectTextViews(decorView, "Lenovo Tab Pen Pro", list);
            boolean zFound = false;
            for (View view : list) {
                View cardRoot = findCardRoot(view);
                if (cardRoot == null) {
                    continue;
                }
                zFound = true;
                boolean zConnected = connected == 1;
                View singleBattery = cardRoot.findViewById(0x7f0903b6);
                View statusContainer = cardRoot.findViewById(0x7f0903e9);
                View statusText = cardRoot.findViewById(0x7f0903eb);
                View batteryText = cardRoot.findViewById(0x7f09009e);
                View batteryView = cardRoot.findViewById(0x7f09009f);
                if (singleBattery != null) {
                    singleBattery.setVisibility(zConnected ? 0 : 8);
                }
                if (statusContainer != null) {
                    statusContainer.setVisibility(zConnected ? 8 : 0);
                }
                if (statusText instanceof TextView) {
                    ((TextView) statusText).setText(zConnected ? "" : "未连接");
                }
                if ((batteryText instanceof TextView) && battery >= 0) {
                    ((TextView) batteryText).setText(String.valueOf(battery));
                }
                if (batteryView != null) {
                    try {
                        Object batteryInfo = buildBatteryInfo(battery, charging != 0);
                        Class<?> clsLottie = Class.forName("com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView", false, appLoader);
                        Class<?> clsInfo = Class.forName("com.oplus.mydevices.domain.entities.device.BatteryInfo", false, appLoader);
                        Method method = clsLottie.getMethod("setBatteryInfo", clsInfo);
                        method.invoke(batteryView, batteryInfo);
                    } catch (Throwable th) {
                        HookUtils.log("CardBatteryHooks lottie: " + th);
                    }
                }
            }
            HookUtils.log("CardBatteryHooks card refreshed connected=" + connected + " charging=" + charging + " level=" + battery + " found=" + zFound);
        } catch (Throwable th2) {
            HookUtils.log("CardBatteryHooks refresh failed: " + th2);
        }
    }

    private CardBatteryHooks() {
    }
}
