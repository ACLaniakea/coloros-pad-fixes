package com.aclaniakea.colorosporttuning;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/* loaded from: classes.dex */
final class OemGattProtocolHooks {
    private static final String BATTERY = "180937bc-2a69-11ec-8d3d-0242ac130003";
    private static final String BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb";
    private static final String BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb";
    private static final String CCC = "00002902-0000-1000-8000-00805f9b34fb";
    private static final String CHARGE = "1809396a-2a69-11ec-8d3d-0242ac130003";
    private static final String CHARGE_STATUS = "00002a1a-0000-1000-8000-00805f9b34fb";
    private static final String DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb";
    private static final String DOUBLE_CLICK = "180938ac-2a69-11ec-8d3d-0242ac130003";
    private static final String DOUBLE_CLICK_SERVICE = "18093398-2a69-11ec-8d3d-0242ac130003";
    private static final String FIRMWARE = "00002a28-0000-1000-8000-00805f9b34fb";
    private static final String FIRMWARE_REVISION = "00002a26-0000-1000-8000-00805f9b34fb";
    private static final String HARDWARE_REVISION = "00002a27-0000-1000-8000-00805f9b34fb";
    private static final String HID_REPORT = "00002a4d-0000-1000-8000-00805f9b34fb";
    private static final String HID_SERVICE = "00001812-0000-1000-8000-00805f9b34fb";
    private static final String INTERFACE = "18093046-2a69-11ec-8d3d-0242ac130003";
    private static final String LENOVO_COMMAND_NOTIFY = "00000006-020f-11e1-9ab4-0002a5d5c51b";
    private static final String LENOVO_COMMAND_SERVICE = "00000000-020f-11e1-9ab4-0002a5d5c51b";
    private static final String LENOVO_DFU_NOTIFY = "0000fe41-cc7a-482a-984a-7f2ed5b3e512";
    private static final String LENOVO_DFU_NOTIFY_ALT = "0000fe42-cc7a-482a-984a-7f2ed5b3e512";
    private static final String LENOVO_DFU_SERVICE = "0000fe40-cc7a-482a-984a-7f2ed5b3e512";
    private static final String LENOVO_EXTENSION_NOTIFY = "00000001-699b-404a-a48e-6254941b956b";
    private static final String LENOVO_EXTENSION_NOTIFY_ALT = "00000005-699b-404a-a48e-6254941b956b";
    private static final String LENOVO_EXTENSION_SERVICE = "00000000-699b-404a-a48e-6254941b956b";
    private static final String LENOVO_HAPTIC_CONTINUOUS = "00000006-000f-11e1-9ab4-0002a5d5c51b";
    private static final String LENOVO_HAPTIC_IMPACT = "00000008-000f-11e1-9ab4-0002a5d5c51b";
    private static final String LENOVO_HAPTIC_REQUEST = "00000002-000f-11e1-9ab4-0002a5d5c51b";
    private static final String LENOVO_HAPTIC_SERVICE = "00000000-000f-11e1-9ab4-0002a5d5c51b";
    private static final String LENOVO_HAPTIC_SWITCH = "0000000e-000f-11e1-9ab4-0002a5d5c51b";
    private static final String LENOVO_INFO_NOTIFY = "0000000a-000f-11e1-9ab4-0002a5d5c51b";
    private static final String LOG = "180932da-2a69-11ec-8d3d-0242ac130003";
    private static final String MANUFACTURER = "00002a29-0000-1000-8000-00805f9b34fb";
    private static final String PNP_ID = "00002a50-0000-1000-8000-00805f9b34fb";
    private static final String S0 = "com.oplus.ipemanager.btadsorb.ble.s0";
    private static final String SENSITIVITY = "58480001-5a6a-1122-3300-004095112200";
    private static final String SERIAL_NUMBER = "00002a25-0000-1000-8000-00805f9b34fb";
    private static volatile boolean installed;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<BluetoothGatt, Session> SESSIONS = new IdentityHashMap();
    private static final Map<Object, Session> MANAGER_SESSIONS = new IdentityHashMap();

    static void install(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (installed) {
            return;
        }
        installed = true;
        String[] strArr = {S0, "com.oplus.ipemanager.btadsorb.ble.n", "com.oplus.ipemanager.btadsorb.ble.t"};
        int iHookAll = 0;
        for (int i = 0; i < 3; i++) {
            final String str = strArr[i];
            iHookAll = iHookAll + HookUtils.hookAll(loadPackageParam.classLoader, str, "e", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.1
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    BluetoothGattCharacteristic bluetoothGattCharacteristicCharacteristicArg = OemGattProtocolHooks.characteristicArg(methodHookParam.args);
                    if (bluetoothGattCharacteristicCharacteristicArg != null) {
                        OemGattProtocolHooks.onCharacteristic(methodHookParam.thisObject, bluetoothGattCharacteristicCharacteristicArg, "notify");
                    }
                }
            }) + HookUtils.hookAll(loadPackageParam.classLoader, str, "k", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.2
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (methodHookParam.args == null || methodHookParam.args.length <= 0 || !(methodHookParam.args[0] instanceof BluetoothGatt)) {
                        return;
                    }
                    OemGattProtocolHooks.onServicesDiscovered(methodHookParam.thisObject, (BluetoothGatt) methodHookParam.args[0]);
                }
            }) + HookUtils.hookAll(loadPackageParam.classLoader, str, "f", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.3
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    BluetoothGattCharacteristic bluetoothGattCharacteristicCharacteristicArg = OemGattProtocolHooks.characteristicArg(methodHookParam.args);
                    if (bluetoothGattCharacteristicCharacteristicArg != null) {
                        OemGattProtocolHooks.onCharacteristic(methodHookParam.thisObject, bluetoothGattCharacteristicCharacteristicArg, "read");
                        OemGattProtocolHooks.onReadCompleted(methodHookParam.thisObject, bluetoothGattCharacteristicCharacteristicArg, OemGattProtocolHooks.statusArg(methodHookParam.args));
                    }
                }
            }) + HookUtils.hookAll(loadPackageParam.classLoader, str, "g", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.4
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    OemGattProtocolHooks.onWriteCompleted(methodHookParam.thisObject, OemGattProtocolHooks.characteristicArg(methodHookParam.args), OemGattProtocolHooks.statusArg(methodHookParam.args));
                }
            }) + HookUtils.hookAll(loadPackageParam.classLoader, str, "h", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.5
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    OemGattProtocolHooks.onDescriptorCompleted(methodHookParam.thisObject, OemGattProtocolHooks.statusArg(methodHookParam.args));
                }
            }) + HookUtils.hookAll(loadPackageParam.classLoader, str, "j", new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.6
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws SecurityException {
                    OemGattProtocolHooks.onDisconnected(methodHookParam.thisObject, str);
                }
            });
            if (S0.equals(str)) {
                iHookAll = iHookAll + HookUtils.hookAll(loadPackageParam.classLoader, str, "N", originalCommandHook(1)) + HookUtils.hookAll(loadPackageParam.classLoader, str, "u0", originalCommandHook(2)) + HookUtils.hookAll(loadPackageParam.classLoader, str, "s0", originalCommandHook(3)) + HookUtils.hookAll(loadPackageParam.classLoader, str, "t0", originalCommandHook(4)) + HookUtils.hookAll(loadPackageParam.classLoader, str, "M", originalCommandHook(5)) + HookUtils.hookAll(loadPackageParam.classLoader, str, "E0", originalCommandHook(6));
            }
        }
        HookUtils.log("IPe OEM GATT observers installed callbacks=" + iHookAll + " managers=3");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void onServicesDiscovered(Object obj, BluetoothGatt bluetoothGatt) {
        int i;
        int i2;
        int i3;
        try {
            List<BluetoothGattService> services = bluetoothGatt.getServices();
            String address = "";
            try {
                if (bluetoothGatt.getDevice() != null) {
                    address = bluetoothGatt.getDevice().getAddress();
                }
            } catch (Throwable unused) {
            }
            StringBuilder sb = new StringBuilder();
            if (services != null) {
                i = 0;
                i2 = 0;
                i3 = 0;
                for (BluetoothGattService bluetoothGattService : services) {
                    if (bluetoothGattService != null) {
                        i++;
                        String strUuid = uuid(bluetoothGattService.getUuid());
                        if (sb.length() > 0) {
                            sb.append(';');
                        }
                        sb.append(strUuid).append(':');
                        List<BluetoothGattCharacteristic> characteristics = bluetoothGattService.getCharacteristics();
                        if (characteristics != null) {
                            for (BluetoothGattCharacteristic bluetoothGattCharacteristic : characteristics) {
                                if (bluetoothGattCharacteristic != null) {
                                    i2++;
                                    String strUuid2 = uuid(bluetoothGattCharacteristic.getUuid());
                                    int properties = bluetoothGattCharacteristic.getProperties();
                                    if ((properties & 16) != 0 || (properties & 32) != 0) {
                                        i3++;
                                    }
                                    sb.append(strUuid2).append('[').append(properties).append(']').append(',');
                                    for (BluetoothGattDescriptor bluetoothGattDescriptor : bluetoothGattCharacteristic.getDescriptors()) {
                                        if (bluetoothGattDescriptor != null) {
                                            sb.append("d:").append(uuid(bluetoothGattDescriptor.getUuid())).append(',');
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                i = 0;
                i2 = 0;
                i3 = 0;
            }
            Context context = HookUtils.context(obj);
            if (context != null && acceptManager(context, obj)) {
                if (!HookUtils.disconnectRequested(context)) {
                    HookUtils.setLinkConnected(context, true);
                }
                if (context != null) {
                    try {
                        Settings.Global.putString(context.getContentResolver(), "lenovo_pen_oem_gatt_services", sb.toString());
                    } catch (Throwable unused2) {
                    }
                }
                HookUtils.log("IPe OEM s0 services discovered address=" + address + " services=" + i + " characteristics=" + i2 + " notify=" + i3 + " known=" + knownServiceSummary(services));
                if (!HookUtils.disconnectRequested(context)) {
                    try {
                        Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_disconnect_requested", 0);
                    } catch (Throwable unused3) {
                    }
                } else {
                    HookUtils.log("IPe OEM s0 service discovery kept settings disconnect latch");
                }
                final Session sessionSessionFor = sessionFor(obj, bluetoothGatt);
                if (!sessionSessionFor.configured) {
                    sessionSessionFor.configured = true;
                    queueLenovoOperations(sessionSessionFor, services);
                    HANDLER.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.7
                        @Override // java.lang.Runnable
                        public void run() {
                            OemGattProtocolHooks.drain(sessionSessionFor);
                        }
                    }, 500L);
                }
                setOemControlReady(context, hasCharacteristic(services, LENOVO_HAPTIC_IMPACT) || hasCharacteristic(services, LENOVO_HAPTIC_CONTINUOUS));
                // 开机时 setWritingEnabled 会在 s0 GATT 尚未 ready 前广播 switch，
                // 此时没有活动 session，会被 handleControl 丢弃，导致第一次连接后
                // 书写触觉未真正打开。这里在 s0 服务发现后重新补发一次 switch。
                if (!HookUtils.disconnectRequested(context) &&
                        Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_global_writing_haptic", 1) != 0) {
                    BluetoothGattCharacteristic switchChar = findCharacteristic(sessionSessionFor, LENOVO_HAPTIC_SWITCH);
                    if (switchChar != null) {
                        enqueueWrite(sessionSessionFor, switchChar, new byte[]{1});
                        HookUtils.log("IPe OEM haptic switch re-applied after s0 ready");
                    }
                }
                return;
            }
            HookUtils.log("IPe OEM GATT service set ignored for non-pen manager address=" + address);
        } catch (Throwable th) {
            HookUtils.log("IPe OEM s0 service discovery observe: " + th);
        }
    }

    private static void queueLenovoOperations(Session session, List<BluetoothGattService> list) {
        List<BluetoothGattCharacteristic> characteristics;
        if (session == null || list == null) {
            return;
        }
        for (BluetoothGattService bluetoothGattService : list) {
            if (bluetoothGattService != null && (characteristics = bluetoothGattService.getCharacteristics()) != null) {
                for (BluetoothGattCharacteristic bluetoothGattCharacteristic : characteristics) {
                    if (bluetoothGattCharacteristic != null) {
                        String strUuid = uuid(bluetoothGattCharacteristic.getUuid());
                        if (isLenovoNotification(strUuid)) {
                            addNotification(session, bluetoothGattCharacteristic);
                        }
                        if (isLenovoRead(strUuid)) {
                            addRead(session, bluetoothGattCharacteristic);
                        }
                    }
                }
            }
        }
        HookUtils.log("Lenovo OEM GATT queue notifications=" + session.notifications + " reads=" + session.reads + " address=" + sessionAddress(session.gatt));
    }

    private static boolean isLenovoNotification(String str) {
        return BATTERY_LEVEL.equals(str) || CHARGE_STATUS.equals(str) || LENOVO_INFO_NOTIFY.equals(str) || LENOVO_COMMAND_NOTIFY.equals(str) || LENOVO_EXTENSION_NOTIFY.equals(str) || LENOVO_EXTENSION_NOTIFY_ALT.equals(str) || LENOVO_DFU_NOTIFY.equals(str) || LENOVO_DFU_NOTIFY_ALT.equals(str) || HID_REPORT.equals(str);
    }

    private static boolean isLenovoRead(String str) {
        return BATTERY_LEVEL.equals(str) || CHARGE_STATUS.equals(str) || FIRMWARE.equals(str) || FIRMWARE_REVISION.equals(str) || MANUFACTURER.equals(str) || SERIAL_NUMBER.equals(str) || HARDWARE_REVISION.equals(str) || PNP_ID.equals(str) || LENOVO_HAPTIC_REQUEST.equals(str) || LENOVO_HAPTIC_IMPACT.equals(str) || "00000002-020f-11e1-9ab4-0002a5d5c51b".equals(str) || "00000004-020f-11e1-9ab4-0002a5d5c51b".equals(str) || LENOVO_DFU_NOTIFY.equals(str);
    }

    private static void addNotification(Session session, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        BluetoothGattDescriptor descriptor;
        byte[] bArr;
        String strUuid = uuid(bluetoothGattCharacteristic.getUuid());
        if (session.notificationIds.contains(strUuid)) {
            return;
        }
        int properties = bluetoothGattCharacteristic.getProperties();
        boolean z = (properties & 16) != 0;
        boolean z2 = (properties & 32) != 0;
        if (z || z2) {
            try {
                descriptor = bluetoothGattCharacteristic.getDescriptor(UUID.fromString(CCC));
            } catch (Throwable unused) {
                descriptor = null;
            }
            if (descriptor == null) {
                HookUtils.log("Lenovo OEM GATT notification missing CCC uuid=" + strUuid);
                return;
            }
            session.notificationIds.add(strUuid);
            ArrayDeque<Operation> arrayDeque = session.operations;
            if (z2 && !z) {
                bArr = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            } else {
                bArr = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            }
            arrayDeque.addLast(new Operation(0, bluetoothGattCharacteristic, descriptor, bArr));
            session.notifications++;
        }
    }

    private static void addRead(Session session, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        String strUuid = uuid(bluetoothGattCharacteristic.getUuid());
        if (session.readIds.contains(strUuid) || (bluetoothGattCharacteristic.getProperties() & 2) == 0) {
            return;
        }
        session.readIds.add(strUuid);
        session.operations.addLast(new Operation(1, bluetoothGattCharacteristic, null, null));
        session.reads++;
    }

    private static boolean enqueueWrite(final Session session, BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] bArr) {
        if (session == null || bluetoothGattCharacteristic == null || bArr == null || bArr.length == 0) {
            return false;
        }
        synchronized (session) {
            session.operations.addLast(new Operation(2, bluetoothGattCharacteristic, null, null, (byte[]) bArr.clone()));
        }
        HANDLER.post(new Runnable() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.8
            @Override // java.lang.Runnable
            public void run() {
                OemGattProtocolHooks.drain(session);
            }
        });
        return true;
    }

    private static BluetoothGattCharacteristic findCharacteristic(Session session, String str) {
        List<BluetoothGattCharacteristic> characteristics;
        if (session != null && str != null) {
            try {
                List<BluetoothGattService> services = session.gatt.getServices();
                if (services != null) {
                    for (BluetoothGattService bluetoothGattService : services) {
                        if (bluetoothGattService != null && (characteristics = bluetoothGattService.getCharacteristics()) != null) {
                            for (BluetoothGattCharacteristic bluetoothGattCharacteristic : characteristics) {
                                if (bluetoothGattCharacteristic != null && str.equals(uuid(bluetoothGattCharacteristic.getUuid()))) {
                                    return bluetoothGattCharacteristic;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable th) {
                HookUtils.log("Lenovo OEM GATT find characteristic " + str + ": " + th);
            }
        }
        return null;
    }

    private static boolean hasCharacteristic(List<BluetoothGattService> list, String str) {
        List<BluetoothGattCharacteristic> characteristics;
        if (list != null && str != null) {
            for (BluetoothGattService bluetoothGattService : list) {
                if (bluetoothGattService != null && (characteristics = bluetoothGattService.getCharacteristics()) != null) {
                    for (BluetoothGattCharacteristic bluetoothGattCharacteristic : characteristics) {
                        if (bluetoothGattCharacteristic != null && str.equals(uuid(bluetoothGattCharacteristic.getUuid()))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void setOemControlReady(Context context, boolean z) {
        if (context == null) {
            return;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_control_ready", z ? 1 : 0);
        } catch (Throwable unused) {
        }
        HookUtils.log("OEM GATT control transport ".concat(z ? "ready" : "unavailable"));
    }

    static boolean handleControl(Context context, Intent intent) {
        BluetoothGattCharacteristic bluetoothGattCharacteristicFindCharacteristic;
        if (context == null || intent == null) {
            return false;
        }
        if (!"com.aclaniakea.lenovopenbridge.action.OEM_PEN_CONTROL".equals(intent.getAction())) {
            return false;
        }
        String stringExtra = intent.getStringExtra("op");
        String stringExtra2 = intent.getStringExtra("mac");
        byte[] byteArrayExtra = intent.getByteArrayExtra("payload");
        Session sessionFindSession = findSession(stringExtra2);
        if (sessionFindSession == null) {
            HookUtils.log("OEM control dropped: no active s0 session op=" + stringExtra + " address=" + stringExtra2);
            try {
                Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_control_ready", 0);
            } catch (Throwable unused) {
            }
            return false;
        }
        if (sessionFindSession.gatt == null) {
            try {
                Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_control_ready", 0);
            } catch (Throwable unused) {
            }
            HookUtils.log("OEM control transport unusable; fall back to direct GATT op=" + stringExtra + " address=" + stringExtra2);
            return false;
        }
        if ("impact".equals(stringExtra) || "brush".equals(stringExtra)) {
            bluetoothGattCharacteristicFindCharacteristic = findCharacteristic(sessionFindSession, LENOVO_HAPTIC_IMPACT);
        } else if ("continuous".equals(stringExtra) || "stop".equals(stringExtra)) {
            bluetoothGattCharacteristicFindCharacteristic = findCharacteristic(sessionFindSession, LENOVO_HAPTIC_CONTINUOUS);
        } else if ("switch".equals(stringExtra)) {
            bluetoothGattCharacteristicFindCharacteristic = findCharacteristic(sessionFindSession, LENOVO_HAPTIC_SWITCH);
        } else {
            bluetoothGattCharacteristicFindCharacteristic = "request".equals(stringExtra) ? findCharacteristic(sessionFindSession, LENOVO_HAPTIC_REQUEST) : null;
        }
        if (bluetoothGattCharacteristicFindCharacteristic == null) {
            HookUtils.log("OEM control unsupported on current pen op=" + stringExtra + " service=00000000-000f-11e1-9ab4-0002a5d5c51b");
            return false;
        }
        if (byteArrayExtra == null || byteArrayExtra.length == 0) {
            if ("impact".equals(stringExtra)) {
                byteArrayExtra = new byte[]{1, 5, 1, 0, 0, 0};
            } else if ("brush".equals(stringExtra)) {
                byteArrayExtra = new byte[]{2, 5, 1, 0, 0, 0};
            } else if ("stop".equals(stringExtra)) {
                byteArrayExtra = new byte[]{0, 0, 0, 0};
            } else if ("switch".equals(stringExtra) || "request".equals(stringExtra)) {
                byteArrayExtra = new byte[]{1};
            }
        }
        boolean zEnqueueWrite = enqueueWrite(sessionFindSession, bluetoothGattCharacteristicFindCharacteristic, byteArrayExtra);
        if (zEnqueueWrite) {
            HookUtils.log("OEM control queued op=" + stringExtra + " uuid=" + uuid(bluetoothGattCharacteristicFindCharacteristic.getUuid()) + " value=" + hex(byteArrayExtra) + " address=" + sessionAddress(sessionFindSession.gatt));
        }
        return zEnqueueWrite;
    }

    private static Session findSession(String str) {
        String strNormalizeAddress = normalizeAddress(str);
        Map<BluetoothGatt, Session> map = SESSIONS;
        synchronized (map) {
            if (strNormalizeAddress.length() > 0) {
                for (Session session : map.values()) {
                    if (session != null && strNormalizeAddress.equals(normalizeAddress(sessionAddress(session.gatt)))) {
                        return session;
                    }
                }
            }
            for (Session session2 : SESSIONS.values()) {
                if (session2 != null) {
                    return session2;
                }
            }
            return null;
        }
    }

    private static XC_MethodHook originalCommandHook(final int i) {
        return new XC_MethodHook() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.9
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws SecurityException {
                Object obj = methodHookParam.thisObject;
                if (obj == null && i == 1 && methodHookParam.args != null && methodHookParam.args.length > 0) {
                    obj = methodHookParam.args[0];
                }
                byte[] bArrOriginalPayload = OemGattProtocolHooks.originalPayload(obj, i, methodHookParam.args);
                if (bArrOriginalPayload == null || !OemGattProtocolHooks.routeOriginalCommand(obj, i, bArrOriginalPayload)) {
                    return;
                }
                methodHookParam.setResult((Object) null);
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static byte[] originalPayload(Object obj, int i, Object[] objArr) throws SecurityException {
        Context context = HookUtils.context(obj);
        int iIntArg = intArg(objArr, i == 1 ? 1 : 0, 0);
        switch (i) {
            case 1:
                int iLocalInt = localInt(obj, context, "sp_key_unic_write_vibration_type", 0);
                localPut(obj, context, "sp_key_unic_written_feedback_level", iIntArg);
                return new byte[]{44, -108, 2, (byte) iLocalInt, (byte) (iIntArg + 1)};
            case 2:
                Object objCall = HookUtils.call(obj, "T", new Object[0]);
                return new byte[]{44, -108, 2, (byte) localInt(obj, context, "sp_key_unic_write_vibration_type", 0), (byte) (iIntArg == 1 ? (objCall instanceof Number ? ((Number) objCall).intValue() : localInt(obj, context, "sp_key_unic_written_feedback_level", 4)) + 1 : 0)};
            case 3:
                invokeStatic(obj, context, "h3.t", "o0", new Class[]{Context.class, Integer.TYPE}, new Object[]{context, Integer.valueOf(iIntArg)});
                return new byte[]{44, -102, (byte) (iIntArg + 1)};
            case 4:
                return new byte[]{44, -104, 1, (byte) iIntArg};
            case 5:
                return new byte[]{44, -103, 2, 0, (byte) iIntArg};
            case 6:
                return new byte[]{44, -110, 1, 2, -1, 2};
            default:
                return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean routeOriginalCommand(Object obj, int i, byte[] bArr) {
        Session session;
        BluetoothGattCharacteristic bluetoothGattCharacteristicFindCharacteristic;
        synchronized (SESSIONS) {
            session = MANAGER_SESSIONS.get(obj);
        }
        if (session == null) {
            return false;
        }
        if (i == 6) {
            bluetoothGattCharacteristicFindCharacteristic = findCharacteristic(session, LENOVO_HAPTIC_IMPACT);
            if (bluetoothGattCharacteristicFindCharacteristic != null) {
                bArr = new byte[]{1, 5, 1, 0, 0, 0};
            }
        } else {
            bluetoothGattCharacteristicFindCharacteristic = null;
        }
        if (bluetoothGattCharacteristicFindCharacteristic == null) {
            bluetoothGattCharacteristicFindCharacteristic = findCharacteristic(session, INTERFACE);
        }
        if (bluetoothGattCharacteristicFindCharacteristic == null) {
            if (i == 6) {
                HookUtils.log("OEM startVibration: no Lenovo impact or ColorOS INTERFACE characteristic");
            }
            return false;
        }
        boolean zEnqueueWrite = enqueueWrite(session, bluetoothGattCharacteristicFindCharacteristic, bArr);
        if (zEnqueueWrite) {
            HookUtils.log("OEM original command queued command=" + i + " uuid=" + uuid(bluetoothGattCharacteristicFindCharacteristic.getUuid()) + " value=" + hex(bArr));
        }
        return zEnqueueWrite;
    }

    private static int localInt(Object obj, Context context, String str, int i) {
        if (context != null && str != null) {
            try {
                for (Method method : Class.forName("y1.b", false, classLoader(obj, context)).getDeclaredMethods()) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if ("c".equals(method.getName()) && parameterTypes.length == 3 && parameterTypes[0] == Context.class && parameterTypes[1] == Integer.TYPE && parameterTypes[2] == String.class) {
                        method.setAccessible(true);
                        Object objInvoke = method.invoke(null, context, Integer.valueOf(i), str);
                        if (objInvoke instanceof Number) {
                            return ((Number) objInvoke).intValue();
                        }
                    }
                }
            } catch (Throwable unused) {
            }
        }
        return i;
    }

    private static void localPut(Object obj, Context context, String str, int i) {
        if (context == null || str == null) {
            return;
        }
        try {
            for (Method method : Class.forName("y1.b", false, classLoader(obj, context)).getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if ("g".equals(method.getName()) && parameterTypes.length == 3 && parameterTypes[0] == Context.class && parameterTypes[1] == Integer.TYPE && parameterTypes[2] == String.class) {
                    method.setAccessible(true);
                    method.invoke(null, context, Integer.valueOf(i), str);
                    return;
                }
            }
        } catch (Throwable unused) {
        }
    }

    private static void invokeStatic(Object obj, Context context, String str, String str2, Class<?>[] clsArr, Object[] objArr) {
        if (context == null) {
            return;
        }
        try {
            Method declaredMethod = Class.forName(str, false, classLoader(obj, context)).getDeclaredMethod(str2, clsArr);
            declaredMethod.setAccessible(true);
            declaredMethod.invoke(null, objArr);
        } catch (Throwable unused) {
        }
    }

    private static ClassLoader classLoader(Object obj, Context context) {
        if (obj != null) {
            try {
                if (obj.getClass().getClassLoader() != null) {
                    return obj.getClass().getClassLoader();
                }
            } catch (Throwable unused) {
            }
        }
        if (context != null) {
            try {
                if (context.getClass().getClassLoader() != null) {
                    return context.getClass().getClassLoader();
                }
            } catch (Throwable unused2) {
            }
        }
        return OemGattProtocolHooks.class.getClassLoader();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    public static void drain(final Session session) {
        boolean zWriteSuccess = false;
        if (session == null) {
            return;
        }
        synchronized (session) {
            if (!session.busy && !session.operations.isEmpty() && session.gatt != null) {
                Operation operationPeekFirst = session.operations.peekFirst();
                try {
                    if (operationPeekFirst.kind == 0) {
                        session.gatt.setCharacteristicNotification(operationPeekFirst.characteristic, true);
                        operationPeekFirst.descriptor.setValue(operationPeekFirst.descriptorValue);
                        zWriteSuccess = session.gatt.writeDescriptor(operationPeekFirst.descriptor);
                    } else if (operationPeekFirst.kind == 1) {
                        zWriteSuccess = session.gatt.readCharacteristic(operationPeekFirst.characteristic);
                    } else {
                        operationPeekFirst.characteristic.setValue(operationPeekFirst.value);
                        zWriteSuccess = session.gatt.writeCharacteristic(operationPeekFirst.characteristic);
                    }
                } catch (Throwable th) {
                    HookUtils.log("Lenovo OEM GATT start " + operationPeekFirst.describe() + ": " + th);
                    zWriteSuccess = false;
                }
                if (zWriteSuccess) {
                    session.busy = true;
                    session.active = operationPeekFirst;
                    HookUtils.log("Lenovo OEM GATT started " + operationPeekFirst.describe());
                } else {
                    operationPeekFirst.retries++;
                    if (operationPeekFirst.retries <= 5) {
                        HANDLER.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.10
                            @Override // java.lang.Runnable
                            public void run() {
                                OemGattProtocolHooks.drain(session);
                            }
                        }, 250L);
                    } else {
                        session.operations.pollFirst();
                        HookUtils.log("Lenovo OEM GATT dropped " + operationPeekFirst.describe() + " after retries");
                        HANDLER.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.11
                            @Override // java.lang.Runnable
                            public void run() {
                                OemGattProtocolHooks.drain(session);
                            }
                        }, 25L);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void onReadCompleted(Object obj, BluetoothGattCharacteristic bluetoothGattCharacteristic, String str) {
        completeOperation(obj, 1, bluetoothGattCharacteristic, str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void onWriteCompleted(Object obj, BluetoothGattCharacteristic bluetoothGattCharacteristic, String str) {
        if (completeOperation(obj, 2, bluetoothGattCharacteristic, str) || !acceptManager(HookUtils.context(obj), obj)) {
            return;
        }
        HookUtils.log("Lenovo OEM GATT write callback status=" + str + (bluetoothGattCharacteristic == null ? "" : " uuid=" + uuid(bluetoothGattCharacteristic.getUuid())));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void onDescriptorCompleted(Object obj, String str) {
        completeOperation(obj, 0, null, str);
    }

    private static boolean completeOperation(Object obj, int i, BluetoothGattCharacteristic bluetoothGattCharacteristic, String str) {
        final Session session;
        synchronized (SESSIONS) {
            session = MANAGER_SESSIONS.get(obj);
        }
        boolean z = false;
        if (session == null) {
            return false;
        }
        synchronized (session) {
            Operation operation = session.active;
            if (session.busy && operation != null && operation.kind == i) {
                if (bluetoothGattCharacteristic != null && operation.characteristic != bluetoothGattCharacteristic && !uuid(operation.characteristic.getUuid()).equals(uuid(bluetoothGattCharacteristic.getUuid()))) {
                    return false;
                }
                session.active = null;
                session.busy = false;
                if (!session.operations.isEmpty() && session.operations.peekFirst() == operation) {
                    session.operations.pollFirst();
                }
                int status = parseStatus(str);
                if (status != 0 && operation.retries < 5) {
                    operation.retries++;
                    session.operations.addFirst(operation);
                    z = true;
                }
                HookUtils.log("Lenovo OEM GATT completed " + operation.describe() + " status=" + str + (status != 0 ? " retry=" + operation.retries : ""));
                HANDLER.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.OemGattProtocolHooks.12
                    @Override // java.lang.Runnable
                    public void run() {
                        OemGattProtocolHooks.drain(session);
                    }
                }, z ? 250L : 25L);
                return true;
            }
            return false;
        }
    }

    private static int parseStatus(String str) {
        if (str != null && !"none".equals(str)) {
            try {
                return Integer.parseInt(str);
            } catch (Throwable unused) {
            }
        }
        return 0;
    }

    private static Session sessionFor(Object obj, BluetoothGatt bluetoothGatt) {
        Session session;
        if (bluetoothGatt == null) {
            return null;
        }
        Map<BluetoothGatt, Session> map = SESSIONS;
        synchronized (map) {
            session = map.get(bluetoothGatt);
            if (session == null) {
                session = new Session(bluetoothGatt);
                map.put(bluetoothGatt, session);
            }
            if (obj != null) {
                MANAGER_SESSIONS.put(obj, session);
            }
        }
        return session;
    }

    private static boolean acceptManager(Context context, Object obj) {
        String str;
        if (context == null || obj == null) {
            return false;
        }
        if (S0.equals(obj.getClass().getName())) {
            return true;
        }
        String strManagerAddress = managerAddress(obj);
        try {
            str = HookUtils.state(context).address;
        } catch (Throwable unused) {
            str = "";
        }
        if (strManagerAddress.length() > 0 && str != null && str.length() > 0) {
            return normalizeAddress(strManagerAddress).equals(normalizeAddress(str));
        }
        return penName(obj);
    }

    private static boolean penName(Object obj) {
        String lowerCase = managerName(obj).toLowerCase(Locale.US);
        return lowerCase.contains("lenovo") || lowerCase.contains("precision pen") || lowerCase.contains("tab pen") || lowerCase.contains("xiaoxin") || lowerCase.contains("pencil") || lowerCase.contains("stylus");
    }

    private static String managerAddress(Object obj) {
        Object objFieldValue = fieldValue(obj, "f1906l");
        if (objFieldValue instanceof BluetoothDevice) {
            return safeAddress((BluetoothDevice) objFieldValue);
        }
        Object objFieldValue2 = fieldValue(obj, "f1905k");
        if (objFieldValue2 instanceof BluetoothGatt) {
            try {
                return safeAddress(((BluetoothGatt) objFieldValue2).getDevice());
            } catch (Throwable unused) {
            }
        }
        Object objFieldValue3 = fieldValue(fieldValue(obj, "f1915u"), "a");
        if (objFieldValue3 != null) {
            String strValueOf = String.valueOf(objFieldValue3);
            return strValueOf.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") ? strValueOf : "";
        }
        return "";
    }

    private static String managerName(Object obj) {
        Object objFieldValue = fieldValue(obj, "f1906l");
        if (objFieldValue instanceof BluetoothDevice) {
            try {
                return ((BluetoothDevice) objFieldValue).getName() == null ? "" : ((BluetoothDevice) objFieldValue).getName();
            } catch (Throwable unused) {
            }
        }
        return "";
    }

    private static String safeAddress(BluetoothDevice bluetoothDevice) {
        if (bluetoothDevice != null) {
            try {
                if (bluetoothDevice.getAddress() != null) {
                    return bluetoothDevice.getAddress();
                }
            } catch (Throwable unused) {
            }
        }
        return "";
    }

    private static String sessionAddress(BluetoothGatt bluetoothGatt) {
        if (bluetoothGatt != null) {
            try {
                if (bluetoothGatt.getDevice() != null) {
                    return bluetoothGatt.getDevice().getAddress();
                }
            } catch (Throwable unused) {
            }
        }
        return "";
    }

    private static String normalizeAddress(String str) {
        return str == null ? "" : str.replace(":", "").replace("-", "").toLowerCase(Locale.US);
    }

    private static Object fieldValue(Object obj, String str) {
        if (obj != null && str != null) {
            for (Class<?> superclass = obj.getClass(); superclass != null; superclass = superclass.getSuperclass()) {
                try {
                    Field declaredField = superclass.getDeclaredField(str);
                    declaredField.setAccessible(true);
                    return declaredField.get(obj);
                } catch (Throwable unused) {
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void onDisconnected(Object obj, String str) throws SecurityException {
        Context context = HookUtils.context(obj);
        Map<BluetoothGatt, Session> map = SESSIONS;
        synchronized (map) {
            Session sessionRemove = MANAGER_SESSIONS.remove(obj);
            if (sessionRemove != null && !hasManagerSession(sessionRemove)) {
                map.remove(sessionRemove.gatt);
            }
        }
        if (context != null && S0.equals(str)) {
            setOemControlReady(context, false);
            HookUtils.setLinkConnected(context, false);
            IpeManagerHooks.publishHardwareDisconnected(context, obj, "ipe_ble_gatt_disconnected");
            HookUtils.log("IPe OEM s0 GATT disconnected; hardware samples invalidated");
            return;
        }
        if (context == null || !acceptManager(context, obj)) {
            return;
        }
        HookUtils.log("Lenovo OEM GATT manager disconnected class=" + str);
    }

    private static boolean hasManagerSession(Session session) {
        Iterator<Session> it = MANAGER_SESSIONS.values().iterator();
        while (it.hasNext()) {
            if (it.next() == session) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void onCharacteristic(Object obj, BluetoothGattCharacteristic bluetoothGattCharacteristic, String str) {
        try {
            byte[] value = bluetoothGattCharacteristic.getValue();
            if (value != null && value.length != 0) {
                String strUuid = uuid(bluetoothGattCharacteristic.getUuid());
                Context context = HookUtils.context(obj);
                if (context != null && acceptManager(context, obj)) {
                    HookUtils.log("IPe OEM s0 " + str + " uuid=" + strUuid + " value=" + hex(value));
                    if (BATTERY_LEVEL.equals(strUuid)) {
                        int iU8 = u8(value, 0);
                        if (iU8 <= 100) {
                            IpeManagerHooks.publishHardwareBattery(context, obj, iU8);
                            return;
                        } else {
                            HookUtils.log("Lenovo battery level out of range=" + iU8);
                            return;
                        }
                    }
                    if (CHARGE_STATUS.equals(strUuid)) {
                        publishLenovoCharge(context, obj, u8(value, 0));
                        return;
                    }
                    if (BATTERY.equals(strUuid)) {
                        int iU82 = u8(value, 0);
                        if (iU82 <= 100) {
                            IpeManagerHooks.publishHardwareBattery(context, obj, iU82);
                            return;
                        } else {
                            HookUtils.log("IPe OEM battery byte out of range=" + iU82);
                            return;
                        }
                    }
                    if (CHARGE.equals(strUuid)) {
                        publishCharge(context, obj, u8(value, 0), "direct");
                        return;
                    }
                    if (INTERFACE.equals(strUuid)) {
                        parseInterface(context, obj, value);
                        return;
                    }
                    if (FIRMWARE.equals(strUuid)) {
                        String strAscii = ascii(value);
                        if (strAscii.length() > 0) {
                            IpeManagerHooks.publishHardwareMetadata(context, obj, null, strAscii, null, null, "ipe_oem_device_info");
                            return;
                        }
                        return;
                    }
                    if (FIRMWARE_REVISION.equals(strUuid)) {
                        String strAscii2 = ascii(value);
                        if (strAscii2.length() > 0) {
                            IpeManagerHooks.publishHardwareMetadata(context, obj, null, strAscii2, null, null, "lenovo_device_info");
                            return;
                        }
                        return;
                    }
                    if (SERIAL_NUMBER.equals(strUuid)) {
                        String strAscii3 = ascii(value);
                        if (strAscii3.length() > 0) {
                            IpeManagerHooks.publishHardwareMetadata(context, obj, null, null, null, strAscii3, "lenovo_device_info");
                            return;
                        }
                        return;
                    }
                    if (HARDWARE_REVISION.equals(strUuid)) {
                        String strAscii4 = ascii(value);
                        if (strAscii4.length() > 0) {
                            IpeManagerHooks.publishHardwareMetadata(context, obj, null, null, strAscii4, null, "lenovo_device_info");
                            return;
                        }
                        return;
                    }
                    try {
                        if (MANUFACTURER.equals(strUuid)) {
                            String strAscii5 = ascii(value);
                            if (strAscii5.length() > 0) {
                                Settings.Global.putString(context.getContentResolver(), "lenovo_pen_manufacturer", strAscii5);
                                return;
                            }
                            return;
                        }
                        if (PNP_ID.equals(strUuid)) {
                            Settings.Global.putString(context.getContentResolver(), "lenovo_pen_pnp_id", hex(value));
                            return;
                        }
                        if (HID_REPORT.equals(strUuid)) {
                            HookUtils.log("Lenovo HID report=" + hex(value));
                            return;
                        }
                        if (!LENOVO_INFO_NOTIFY.equals(strUuid) && !LENOVO_COMMAND_NOTIFY.equals(strUuid) && !LENOVO_EXTENSION_NOTIFY.equals(strUuid) && !LENOVO_EXTENSION_NOTIFY_ALT.equals(strUuid) && !LENOVO_DFU_NOTIFY.equals(strUuid) && !LENOVO_DFU_NOTIFY_ALT.equals(strUuid)) {
                            if (DOUBLE_CLICK.equals(strUuid)) {
                                HookUtils.log("IPe OEM pencil button packet=" + u8(value, 0));
                                return;
                            } else {
                                if (LOG.equals(strUuid) || SENSITIVITY.equals(strUuid)) {
                                    HookUtils.log("IPe OEM auxiliary notification uuid=" + strUuid);
                                    return;
                                }
                                return;
                            }
                        }
                        parseLenovoTelemetry(context, obj, strUuid, value);
                    } catch (Throwable unused) {
                    }
                }
            }
        } catch (Throwable th) {
            HookUtils.log("IPe OEM characteristic observe: " + th);
        }
    }

    private static void parseInterface(Context context, Object obj, byte[] bArr) throws SecurityException {
        if (starts(bArr, 44, 8, 1, 1)) {
            if (bArr.length > 4) {
                int iU8 = u8(bArr, 4);
                if (iU8 <= 100) {
                    IpeManagerHooks.publishHardwareBattery(context, obj, iU8);
                    return;
                } else {
                    HookUtils.log("IPe OEM interface battery out of range=" + iU8);
                    return;
                }
            }
            return;
        }
        if (starts(bArr, 44, 1, 1, 1)) {
            if (bArr.length > 4) {
                publishCharge(context, obj, u8(bArr, 4), "interface");
                return;
            }
            return;
        }
        if (starts(bArr, 44, 6, 1)) {
            String strLengthPrefixed = lengthPrefixed(bArr);
            if (strLengthPrefixed.length() > 0) {
                IpeManagerHooks.publishHardwareMetadata(context, obj, null, strLengthPrefixed, null, null, "ipe_oem_interface");
                return;
            }
            return;
        }
        if (starts(bArr, 44, 83, 1)) {
            String strLengthPrefixed2 = lengthPrefixed(bArr);
            if (strLengthPrefixed2.length() > 0) {
                IpeManagerHooks.publishHardwareMetadata(context, obj, null, null, null, strLengthPrefixed2, "ipe_oem_interface");
                return;
            }
            return;
        }
        if (starts(bArr, 44, 37, 1, 3)) {
            String strLengthPrefixed3 = lengthPrefixed(bArr);
            if (strLengthPrefixed3.length() > 0) {
                IpeManagerHooks.publishHardwareMetadata(context, obj, null, null, strLengthPrefixed3, null, "ipe_oem_interface");
                return;
            }
            return;
        }
        if (starts(bArr, 44, 38, 1)) {
            String strLengthPrefixed4 = lengthPrefixed(bArr);
            if (strLengthPrefixed4.length() > 0) {
                IpeManagerHooks.publishHardwareMetadata(context, obj, strLengthPrefixed4, null, null, null, "ipe_oem_interface");
                return;
            }
            return;
        }
        if (starts(bArr, 44, 144, 1, 1)) {
            if (bArr.length > 4) {
                try {
                    Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_pencil_status", u8(bArr, 4));
                } catch (Throwable unused) {
                }
            }
            HookUtils.log("IPe OEM pencil status packet=" + hex(bArr));
        } else if (starts(bArr, 44, 149, 1, 1, 1) || starts(bArr, 44, 150, 1, 0) || starts(bArr, 44, 151, 1, 0) || starts(bArr, 44, 152, 1, 0) || starts(bArr, 44, 153, 2, 0, 1) || starts(bArr, 44, 146, 1, 2)) {
            HookUtils.log("IPe OEM command notification branch=" + interfaceBranch(bArr));
        } else {
            HookUtils.log("IPe OEM interface unknown branch=" + hexPrefix(bArr, 8));
        }
    }

    private static void publishCharge(Context context, Object obj, int i, String str) throws SecurityException {
        int i2 = 0;
        int i3 = (i == 32 || i == 1) ? 1 : 0;
        if (i == 32) {
            i2 = 1;
        } else if (i == 34) {
            i2 = 2;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_charge_state_code", i2);
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_charge_raw", i & 255);
        } catch (Throwable unused) {
        }
        IpeManagerHooks.publishHardwareCharging(context, obj, i3, i);
        HookUtils.log("IPe OEM " + str + " charge raw=" + i + " state=" + i2 + " charging=" + i3);
    }

    private static void publishLenovoCharge(Context context, Object obj, int i) throws SecurityException {
        int i2 = (i >> 4) & 3;
        int i3 = i2 == 3 ? 1 : 0;
        try {
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_charge_state_code", i2);
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_oem_charge_raw", i & 255);
        } catch (Throwable unused) {
        }
        IpeManagerHooks.publishHardwareCharging(context, obj, i3, i);
        HookUtils.log("Lenovo 2A1A charge raw=" + i + " state=" + i2 + " charging=" + i3);
    }

    private static void parseLenovoTelemetry(Context context, Object obj, String str, byte[] bArr) throws SecurityException {
        if (starts(bArr, 44, 8, 1, 1) || starts(bArr, 44, 1, 1, 1) || starts(bArr, 44, 6, 1) || starts(bArr, 44, 83, 1) || starts(bArr, 44, 37, 1, 3) || starts(bArr, 44, 38, 1) || starts(bArr, 44, 144, 1, 1)) {
            parseInterface(context, obj, bArr);
        } else {
            HookUtils.log("Lenovo telemetry branch uuid=" + str + " value=" + hex(bArr));
        }
    }

    private static String lengthPrefixed(byte[] bArr) {
        int iU8;
        if (bArr == null || bArr.length < 5 || (iU8 = u8(bArr, 3)) <= 0 || iU8 > 64 || iU8 + 4 > bArr.length) {
            return "";
        }
        byte[] bArr2 = new byte[iU8];
        System.arraycopy(bArr, 4, bArr2, 0, iU8);
        return ascii(bArr2);
    }

    private static String ascii(byte[] bArr) {
        if (bArr == null || bArr.length == 0) {
            return "";
        }
        int length = bArr.length;
        while (length > 0) {
            byte b = bArr[length - 1];
            if (b != 0 && b != -1) {
                break;
            }
            length--;
        }
        if (length == 0) {
            return "";
        }
        for (int i = 0; i < length; i++) {
            int i2 = bArr[i] & 255;
            if (i2 < 32 || i2 > 126) {
                return "";
            }
        }
        return new String(bArr, 0, length, UTF8).trim();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static BluetoothGattCharacteristic characteristicArg(Object[] objArr) {
        if (objArr == null) {
            return null;
        }
        for (Object obj : objArr) {
            if (obj instanceof BluetoothGattCharacteristic) {
                return (BluetoothGattCharacteristic) obj;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String statusArg(Object[] objArr) {
        if (objArr != null) {
            for (Object obj : objArr) {
                if ((obj instanceof Integer) || (obj instanceof Long) || (obj instanceof Short) || (obj instanceof Byte)) {
                    return String.valueOf(obj);
                }
            }
            return "none";
        }
        return "none";
    }

    private static int intArg(Object[] objArr, int i, int i2) {
        if (objArr != null && i >= 0 && i < objArr.length) {
            Object obj = objArr[i];
            if (obj instanceof Number) {
                return ((Number) obj).intValue();
            }
        }
        return i2;
    }

    private static String knownServiceSummary(List<BluetoothGattService> list) {
        if (list == null) {
            return "none";
        }
        boolean z = false;
        boolean z2 = false;
        boolean z3 = false;
        boolean z4 = false;
        boolean z5 = false;
        boolean z6 = false;
        boolean z7 = false;
        boolean z8 = false;
        boolean z9 = false;
        boolean z10 = false;
        boolean z11 = false;
        for (BluetoothGattService bluetoothGattService : list) {
            if (bluetoothGattService != null) {
                String strUuid = uuid(bluetoothGattService.getUuid());
                if (DEVICE_INFO_SERVICE.equals(strUuid)) {
                    z5 = true;
                }
                if (BATTERY_SERVICE.equals(strUuid)) {
                    z6 = true;
                }
                if (LENOVO_HAPTIC_SERVICE.equals(strUuid)) {
                    z7 = true;
                }
                if (LENOVO_COMMAND_SERVICE.equals(strUuid)) {
                    z8 = true;
                }
                if (LENOVO_EXTENSION_SERVICE.equals(strUuid)) {
                    z9 = true;
                }
                if (LENOVO_DFU_SERVICE.equals(strUuid)) {
                    z10 = true;
                }
                if (HID_SERVICE.equals(strUuid)) {
                    z11 = true;
                }
                if (DOUBLE_CLICK_SERVICE.equals(strUuid)) {
                    z4 = true;
                }
                List<BluetoothGattCharacteristic> characteristics = bluetoothGattService.getCharacteristics();
                if (characteristics != null) {
                    for (BluetoothGattCharacteristic bluetoothGattCharacteristic : characteristics) {
                        if (bluetoothGattCharacteristic != null) {
                            String strUuid2 = uuid(bluetoothGattCharacteristic.getUuid());
                            if (INTERFACE.equals(strUuid2)) {
                                z = true;
                            }
                            if (BATTERY.equals(strUuid2)) {
                                z2 = true;
                            }
                            if (CHARGE.equals(strUuid2)) {
                                z3 = true;
                            }
                            if (DOUBLE_CLICK.equals(strUuid2)) {
                                z4 = true;
                            }
                        }
                    }
                }
            }
        }
        return "interface=" + z + ",battery=" + z2 + ",charge=" + z3 + ",button=" + z4 + ",deviceInfo=" + z5 + ",standardBattery=" + z6 + ",lenovoInfo=" + z7 + ",lenovoCommand=" + z8 + ",extension=" + z9 + ",dfu=" + z10 + ",hid=" + z11;
    }

    private static String interfaceBranch(byte[] bArr) {
        return starts(bArr, 44, 149, 1, 1, 1) ? "reboot" : starts(bArr, 44, 150, 1, 0) ? "scroll" : starts(bArr, 44, 151, 1, 0) ? "laser" : starts(bArr, 44, 152, 1, 0) ? "laser_aux" : starts(bArr, 44, 153, 2, 0, 1) ? "ring" : "vibration";
    }

    private static boolean starts(byte[] bArr, int... iArr) {
        if (bArr == null || iArr == null || bArr.length < iArr.length) {
            return false;
        }
        for (int i = 0; i < iArr.length; i++) {
            if ((bArr[i] & 255) != iArr[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u8(byte[] bArr, int i) {
        return bArr[i] & 255;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String uuid(Object obj) {
        return obj == null ? "" : String.valueOf(obj).toLowerCase(Locale.US);
    }

    private static String hex(byte[] bArr) {
        return hexPrefix(bArr, 48);
    }

    private static String hexPrefix(byte[] bArr, int i) {
        if (bArr == null) {
            return "";
        }
        int iMin = Math.min(bArr.length, Math.max(0, i));
        StringBuilder sb = new StringBuilder((iMin * 3) + 8);
        for (int i2 = 0; i2 < iMin; i2++) {
            if (i2 > 0) {
                sb.append(' ');
            }
            int i3 = bArr[i2] & 255;
            if (i3 < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(i3));
        }
        if (bArr.length > iMin) {
            sb.append(" ...");
        }
        return sb.toString();
    }

    private static final class Session {
        Operation active;
        boolean busy;
        boolean configured;
        final BluetoothGatt gatt;
        int notifications;
        int reads;
        final ArrayDeque<Operation> operations = new ArrayDeque<>();
        final ArrayList<String> notificationIds = new ArrayList<>();
        final ArrayList<String> readIds = new ArrayList<>();

        Session(BluetoothGatt bluetoothGatt) {
            this.gatt = bluetoothGatt;
        }
    }

    private static final class Operation {
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final byte[] descriptorValue;
        final int kind;
        int retries;
        final byte[] value;

        Operation(int i, BluetoothGattCharacteristic bluetoothGattCharacteristic, BluetoothGattDescriptor bluetoothGattDescriptor, byte[] bArr) {
            this(i, bluetoothGattCharacteristic, bluetoothGattDescriptor, bArr, null);
        }

        Operation(int i, BluetoothGattCharacteristic bluetoothGattCharacteristic, BluetoothGattDescriptor bluetoothGattDescriptor, byte[] bArr, byte[] bArr2) {
            this.kind = i;
            this.characteristic = bluetoothGattCharacteristic;
            this.descriptor = bluetoothGattDescriptor;
            this.descriptorValue = bArr;
            this.value = bArr2;
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            int i = this.kind;
            StringBuilder sbAppend = sb.append(i == 0 ? "notify " : i == 1 ? "read " : "write ");
            BluetoothGattCharacteristic bluetoothGattCharacteristic = this.characteristic;
            return sbAppend.append(OemGattProtocolHooks.uuid(bluetoothGattCharacteristic == null ? null : bluetoothGattCharacteristic.getUuid())).toString();
        }
    }

    private OemGattProtocolHooks() {
    }
}
