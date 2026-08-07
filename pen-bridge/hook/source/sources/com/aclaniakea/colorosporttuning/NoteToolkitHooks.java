package com.aclaniakea.colorosporttuning;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;

/* loaded from: classes.dex */
final class NoteToolkitHooks {
    private static final String TOOLKIT = "com.oplusos.vfxsdk.doodleengine.toolkit.Toolkit";
    private static final ArrayList<WeakReference<Object>> active = new ArrayList<>();
    private static boolean receiver;

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.1
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                Context context = HookUtils.context(methodHookParam.thisObject);
                if (context != null) {
                    NoteToolkitHooks.register(context);
                }
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, "android.app.Application", "onCreate", xC_MethodHook);
        HookUtils.hookAll(loadPackageParam.classLoader, "com.nearme.note.MyApplication", "onCreate", xC_MethodHook);
        XC_MethodHook xC_MethodHook2 = new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.2
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                NoteToolkitHooks.remember(methodHookParam.thisObject);
                Context context = methodHookParam.thisObject instanceof View ? ((View) methodHookParam.thisObject).getContext() : HookUtils.context(methodHookParam.thisObject);
                if (context != null) {
                    NoteToolkitHooks.register(context);
                }
            }
        };
        HookUtils.hookAll(loadPackageParam.classLoader, TOOLKIT, "onAttachedToWindow", xC_MethodHook2);
        HookUtils.hookAll(loadPackageParam.classLoader, TOOLKIT, "onResume$paint_intermediate_release", xC_MethodHook2);
        HookUtils.hookAll(loadPackageParam.classLoader, TOOLKIT, "onDetachedFromWindow", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.3
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                NoteToolkitHooks.forget(methodHookParam.thisObject);
            }
        });
        String[] strArr = {"receiverSingleClick", "receiverDoubleClick"};
        for (int i = 0; i < 2; i++) {
            final String str = strArr[i];
            HookUtils.hookAll(loadPackageParam.classLoader, TOOLKIT, str, new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.4
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    Context context = methodHookParam.thisObject instanceof View ? ((View) methodHookParam.thisObject).getContext() : null;
                    int iMode = NoteToolkitHooks.mode(context, str.contains("Double"), methodHookParam.args);
                    if (iMode < 1 || iMode > 3 || !NoteToolkitHooks.dispatch(iMode)) {
                        return;
                    }
                    methodHookParam.setResult((Object) null);
                    NoteToolkitHooks.haptic(context);
                }
            });
        }
        HookUtils.log("Note/Screenshot hooks installed in " + loadPackageParam.packageName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void register(Context context) {
        if (receiver) {
            return;
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.oplus.ipemanager.action.PENCIL_SINGLE_CLICK");
        intentFilter.addAction("com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK");
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { // from class: com.aclaniakea.colorosporttuning.NoteToolkitHooks.5
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if (NoteToolkitHooks.dispatch(NoteToolkitHooks.mode(context2, "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK".equals(intent.getAction()), intent.getExtras() == null ? null : new Object[]{Integer.valueOf(intent.getIntExtra("action", 0))}))) {
                    NoteToolkitHooks.haptic(context2);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.getApplicationContext().registerReceiver(broadcastReceiver, intentFilter, 2);
            } else {
                context.getApplicationContext().registerReceiver(broadcastReceiver, intentFilter);
            }
            receiver = true;
        } catch (Throwable th) {
            HookUtils.log("toolkit receiver: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int mode(Context context, boolean z, Object[] objArr) {
        int iIntValue = 0;
        if (objArr != null && objArr.length > 0) {
            Object obj = objArr[0];
            if (obj instanceof Number) {
                iIntValue = ((Number) obj).intValue();
            }
        }
        if (iIntValue >= 1 && iIntValue <= 3) {
            return iIntValue;
        }
        if (context == null) {
            return z ? 2 : 1;
        }
        return Settings.Global.getInt(context.getContentResolver(), z ? "ipe_pencil_double_click" : "ipe_pencil_single_click", z ? 2 : 1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void remember(Object obj) {
        forget(obj);
        active.add(new WeakReference<>(obj));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void forget(Object obj) {
        Iterator<WeakReference<Object>> it = active.iterator();
        while (it.hasNext()) {
            Object obj2 = it.next().get();
            if (obj2 == null || obj2 == obj) {
                it.remove();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized boolean dispatch(int i) {
        boolean zTrigger;
        Iterator<WeakReference<Object>> it = active.iterator();
        zTrigger = false;
        while (it.hasNext()) {
            Object obj = it.next().get();
            if (obj == null) {
                it.remove();
            } else {
                zTrigger |= trigger(obj, i);
            }
        }
        return zTrigger;
    }

    private static boolean trigger(Object obj, int i) throws SecurityException {
        String[] strArr = {"triggerStylusClick", "onStylusClick", "switchPenMode"};
        for (int i2 = 0; i2 < 3; i2++) {
            String str = strArr[i2];
            for (Method method : obj.getClass().getMethods()) {
                if (method.getName().equals(str)) {
                    try {
                        method.setAccessible(true);
                        if (method.getParameterTypes().length == 1) {
                            method.invoke(obj, HookUtils.adapt(method.getParameterTypes()[0], Integer.valueOf(i)));
                        } else if (method.getParameterTypes().length == 0) {
                            method.invoke(obj, new Object[0]);
                        }
                        return true;
                    } catch (Throwable unused) {
                    }
                }
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void haptic(Context context) {
        if (context == null) {
            return;
        }
        PenHapticGatt.brush(context, HookUtils.state(context).address);
    }

    private NoteToolkitHooks() {
    }
}
