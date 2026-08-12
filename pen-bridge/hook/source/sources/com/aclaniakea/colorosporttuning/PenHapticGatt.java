package com.aclaniakea.colorosporttuning;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import com.aclaniakea.colorosporttuning.PenHapticGatt;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayDeque;
import java.util.UUID;

/* loaded from: classes.dex */
final class PenHapticGatt {
    private static Context app;
    private static BluetoothGattCharacteristic con;
    private static boolean continuous;
    private static byte[] continuousPayload;
    private static BluetoothGatt gatt;
    private static BluetoothGattCharacteristic impact;
    private static long lastPulse;
    private static BluetoothGattCharacteristic notify;
    private static byte[] pendingImpact;
    private static boolean ready;
    private static BluetoothGattCharacteristic request;
    private static BluetoothGattCharacteristic switcher;
    private static boolean writing;
    private static final UUID SERVICE = uuid("00000000-000f-11e1-9ab4-0002a5d5c51b");
    private static final UUID CONTINUOUS = uuid("00000006-000f-11e1-9ab4-0002a5d5c51b");
    private static final UUID IMPACT = uuid("00000008-000f-11e1-9ab4-0002a5d5c51b");
    private static final UUID REQUEST_INFO = uuid("00000002-000f-11e1-9ab4-0002a5d5c51b");
    private static final UUID INFO_NOTIFY = uuid("0000000a-000f-11e1-9ab4-0002a5d5c51b");
    private static final UUID SWITCH = uuid("0000000e-000f-11e1-9ab4-0002a5d5c51b");
    private static final UUID CCC = uuid("00002902-0000-1000-8000-00805f9b34fb");
    private static final ArrayDeque<Write> queue = new ArrayDeque<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static String address = "";
    private static final Runnable IDLE_CLOSE = new Runnable() { // from class: com.aclaniakea.colorosporttuning.PenHapticGatt$$ExternalSyntheticLambda0
        @Override // java.lang.Runnable
        public final void run() {
            PenHapticGatt.lambda$static$0();
        }
    };
    private static final BluetoothGattCallback CALLBACK = new AnonymousClass1();

    PenHapticGatt() {
    }

    static /* synthetic */ void lambda$static$0() {
        synchronized (PenHapticGatt.class) {
            if (!continuous) {
                disconnect();
            }
        }
    }

    static synchronized void connected(Context context, String str) {
    }

    static synchronized void pulse(Context context, String str) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        if (jUptimeMillis - lastPulse < 45) {
            return;
        }
        lastPulse = jUptimeMillis;
        byte[] bArr = {1, 5, 1, 0, 0, 0};
        if (sendOemControl(context, str, "impact", bArr)) {
            return;
        }
        ensure(context, str);
        BluetoothGattCharacteristic bluetoothGattCharacteristic = impact;
        if (bluetoothGattCharacteristic != null) {
            enqueue(bluetoothGattCharacteristic, bArr);
        } else {
            pendingImpact = bArr;
        }
        closeLater(1800L);
    }

    static synchronized void brush(Context context, String str) {
        byte[] bArr = {2, 5, 1, 0, 0, 0};
        if (sendOemControl(context, str, "brush", bArr)) {
            return;
        }
        ensure(context, str);
        BluetoothGattCharacteristic bluetoothGattCharacteristic = impact;
        if (bluetoothGattCharacteristic != null) {
            enqueue(bluetoothGattCharacteristic, bArr);
        } else {
            pendingImpact = bArr;
        }
        closeLater(1800L);
    }

    static synchronized void startWriting(Context context, String str, int i) {
        continuous = true;
        continuousPayload = new byte[]{32, 5, 1, (byte) (i == 4 ? 0 : 1)};
        handler.removeCallbacks(IDLE_CLOSE);
        if (sendOemControl(context, str, "continuous", continuousPayload)) {
            return;
        }
        ensure(context, str);
        BluetoothGattCharacteristic bluetoothGattCharacteristic = con;
        if (bluetoothGattCharacteristic != null) {
            enqueue(bluetoothGattCharacteristic, continuousPayload);
        }
    }

    static synchronized void stopWriting() {
        continuous = false;
        continuousPayload = null;
        byte[] bArr = {0, 0, 0, 0};
        if (sendOemControl(app, address, "stop", bArr)) {
            return;
        }
        BluetoothGattCharacteristic bluetoothGattCharacteristic = con;
        if (bluetoothGattCharacteristic != null) {
            enqueue(bluetoothGattCharacteristic, bArr);
        }
        closeLater(5000L);
    }

    static synchronized void setWritingEnabled(Context context, String str, boolean z) {
        if (z) {
            try {
                SystemStylusHooks.ensureTouchscreenHaptics();
            } catch (Throwable th) {
                throw th;
            }
        }
        if (z) {
            if (sendOemControl(context, str, "switch", new byte[]{1})) {
                return;
            }
            ensure(context, str);
            closeLater(15000L);
        } else {
            continuous = false;
            continuousPayload = null;
            byte[] bArr = {0, 0, 0, 0};
            if (sendOemControl(context, str, "stop", bArr)) {
                return;
            }
            BluetoothGattCharacteristic bluetoothGattCharacteristic = con;
            if (bluetoothGattCharacteristic != null) {
                enqueue(bluetoothGattCharacteristic, bArr);
            }
            closeLater(900L);
        }
    }

    static synchronized void disconnect() {
        handler.removeCallbacks(IDLE_CLOSE);
        continuous = false;
        continuousPayload = null;
        pendingImpact = null;
        resetTransport();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void ensure(Context context, String str) {
        if (context == null || str == null || str.trim().isEmpty()) {
            return;
        }
        app = context.getApplicationContext();
        handler.removeCallbacks(IDLE_CLOSE);
        if (gatt == null || !str.equalsIgnoreCase(address)) {
            resetTransport();
            address = str;
            try {
                gatt = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(str).connectGatt(app, false, CALLBACK, 2);
            } catch (Throwable th) {
                log("connect", th);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void resetTransport() {
        queue.clear();
        ready = false;
        writing = false;
        switcher = null;
        notify = null;
        request = null;
        impact = null;
        con = null;
        BluetoothGatt bluetoothGatt = gatt;
        gatt = null;
        address = "";
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
            } catch (Throwable unused) {
            }
            try {
                bluetoothGatt.close();
            } catch (Throwable unused2) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void enqueue(BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] bArr) {
        if (bluetoothGattCharacteristic == null) {
            return;
        }
        queue.add(new Write(bluetoothGattCharacteristic, null, bArr));
        drain();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void enqueue(BluetoothGattDescriptor bluetoothGattDescriptor, byte[] bArr) {
        if (bluetoothGattDescriptor == null) {
            return;
        }
        queue.add(new Write(null, bluetoothGattDescriptor, bArr));
        drain();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void drain() {
        boolean zWriteDescriptor;
        if (ready && !writing && gatt != null) {
            Write writePoll = queue.poll();
            if (writePoll == null) {
                return;
            }
            writing = true;
            try {
                if (writePoll.ch != null) {
                    writePoll.ch.setValue(writePoll.value);
                    zWriteDescriptor = gatt.writeCharacteristic(writePoll.ch);
                } else {
                    writePoll.desc.setValue(writePoll.value);
                    zWriteDescriptor = gatt.writeDescriptor(writePoll.desc);
                }
            } catch (Throwable th) {
                log("write", th);
                zWriteDescriptor = false;
            }
            if (!zWriteDescriptor) {
                writing = false;
                handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.PenHapticGatt$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        PenHapticGatt.drain();
                    }
                }, 25L);
            }
        }
    }

    /* renamed from: com.aclaniakea.colorosporttuning.PenHapticGatt$1, reason: invalid class name */
    static class AnonymousClass1 extends BluetoothGattCallback {
        AnonymousClass1() {
        }

        static /* synthetic */ void lambda$onConnectionStateChange$0(String str) {
            synchronized (PenHapticGatt.class) {
                PenHapticGatt.ensure(PenHapticGatt.app, str);
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int i, int i2) {
            synchronized (PenHapticGatt.class) {
                if (i2 == 2) {
                    if (bluetoothGatt == PenHapticGatt.gatt) {
                        bluetoothGatt.discoverServices();
                    }
                } else if (bluetoothGatt == PenHapticGatt.gatt) {
                    final String str = PenHapticGatt.address;
                    PenHapticGatt.resetTransport();
                    if (PenHapticGatt.continuous && PenHapticGatt.app != null && !str.isEmpty()) {
                        PenHapticGatt.handler.postDelayed(new Runnable() { // from class: com.aclaniakea.colorosporttuning.PenHapticGatt$1$$ExternalSyntheticLambda0
                            @Override // java.lang.Runnable
                            public final void run() {
                                PenHapticGatt.AnonymousClass1.lambda$onConnectionStateChange$0(str);
                            }
                        }, 500L);
                    }
                }
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int i) {
            synchronized (PenHapticGatt.class) {
                if (bluetoothGatt == PenHapticGatt.gatt) {
                    BluetoothGattService service = bluetoothGatt.getService(PenHapticGatt.SERVICE);
                    if (service == null) {
                        PenHapticGatt.log("haptic service missing", null);
                    } else {
                        BluetoothGattCharacteristic unused = PenHapticGatt.con = service.getCharacteristic(PenHapticGatt.CONTINUOUS);
                        BluetoothGattCharacteristic unused2 = PenHapticGatt.impact = service.getCharacteristic(PenHapticGatt.IMPACT);
                        BluetoothGattCharacteristic unused3 = PenHapticGatt.request = service.getCharacteristic(PenHapticGatt.REQUEST_INFO);
                        BluetoothGattCharacteristic unused4 = PenHapticGatt.notify = service.getCharacteristic(PenHapticGatt.INFO_NOTIFY);
                        BluetoothGattCharacteristic unused5 = PenHapticGatt.switcher = service.getCharacteristic(PenHapticGatt.SWITCH);
                        boolean unused6 = PenHapticGatt.ready = true;
                        if (PenHapticGatt.notify != null) {
                            try {
                                bluetoothGatt.setCharacteristicNotification(PenHapticGatt.notify, true);
                            } catch (Throwable unused7) {
                            }
                            PenHapticGatt.enqueue(PenHapticGatt.notify.getDescriptor(PenHapticGatt.CCC), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        }
                        PenHapticGatt.enqueue(PenHapticGatt.switcher, new byte[]{1});
                        PenHapticGatt.enqueue(PenHapticGatt.request, new byte[]{1});
                        if (PenHapticGatt.pendingImpact != null && PenHapticGatt.impact != null) {
                            PenHapticGatt.enqueue(PenHapticGatt.impact, PenHapticGatt.pendingImpact);
                            byte[] unused8 = PenHapticGatt.pendingImpact = null;
                        }
                        if (PenHapticGatt.continuous && PenHapticGatt.continuousPayload != null && PenHapticGatt.con != null) {
                            PenHapticGatt.enqueue(PenHapticGatt.con, PenHapticGatt.continuousPayload);
                        }
                        PenHapticGatt.drain();
                    }
                }
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int i) {
            if (bluetoothGatt == PenHapticGatt.gatt) {
                PenHapticGatt.done();
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int i) {
            if (bluetoothGatt == PenHapticGatt.gatt) {
                PenHapticGatt.done();
            }
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
            PenHapticGatt.log("info " + PenHapticGatt.bytes(bluetoothGattCharacteristic.getValue()), null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized void done() {
        writing = false;
        drain();
    }

    private static synchronized void closeLater(long j) {
        Handler handler2 = handler;
        Runnable runnable = IDLE_CLOSE;
        handler2.removeCallbacks(runnable);
        handler2.postDelayed(runnable, j);
    }

    private static boolean sendOemControl(Context context, String str, String str2, byte[] bArr) {
        if (context != null && str2 != null) {
            try {
                app = context.getApplicationContext();
                if (str != null && !str.trim().isEmpty()) {
                    address = str;
                }
                if (Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_oem_control_ready", 0) != 1) {
                    return false;
                }
                Intent intentPutExtra = new Intent("com.aclaniakea.lenovopenbridge.action.OEM_PEN_CONTROL")
                        .setPackage("com.oplus.ipemanager")
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra("op", str2);
                if (str == null) {
                    str = "";
                }
                context.sendBroadcast(intentPutExtra.putExtra("mac", str).putExtra("payload", bArr));
                log("OEM forward op=" + str2 + " value=" + bytes(bArr), null);
                return true;
            } catch (Throwable th) {
                log("OEM forward " + str2, th);
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String bytes(byte[] bArr) {
        if (bArr == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bArr) {
            sb.append(String.format("%02X", Byte.valueOf(b)));
        }
        return sb.toString();
    }

    private static UUID uuid(String str) {
        return UUID.fromString(str);
    }

    private static final class Write {
        final BluetoothGattCharacteristic ch;
        final BluetoothGattDescriptor desc;
        final byte[] value;

        Write(BluetoothGattCharacteristic bluetoothGattCharacteristic, BluetoothGattDescriptor bluetoothGattDescriptor, byte[] bArr) {
            this.ch = bluetoothGattCharacteristic;
            this.desc = bluetoothGattDescriptor;
            this.value = bArr;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void log(String str, Throwable th) {
        try {
            XposedBridge.log("LenovoPenBridge GATT " + str + (th == null ? "" : ": " + th));
        } catch (Throwable unused) {
        }
    }
}
