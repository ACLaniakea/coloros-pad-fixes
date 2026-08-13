package com.aclaniakea.penhidctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Short-lived root-started controller for the system HID Host profile.
 * It intentionally does not synthesize pen input, battery, or connection
 * broadcasts; the Bluetooth stack remains the source of truth.
 */
public final class PenHidService extends Service {
    private static final String TAG = "PenHidCtl";
    private static final int HID_HOST = 4;
    private static final int POLICY_FORBIDDEN = 0;
    private static final int POLICY_ALLOWED = 100;

    private Handler handler;
    private BluetoothAdapter adapter;
    private BluetoothProfile profile;
    private Notification notification;
    private boolean stopped;
    private int startId;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(getMainLooper());
        createForegroundNotification();
    }

    private void createForegroundNotification() {
        try {
            String channelId = "penhid";
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        channelId, "Pen HID Control", NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            notification = new Notification.Builder(this, channelId)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Pen HID")
                    .setContentText("Controlling pen Bluetooth link")
                    .setOngoing(true)
                    .build();
        } catch (Throwable error) {
            Log.w(TAG, "foreground notification unavailable", error);
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int id) {
        startId = id;
        final String action = intent == null ? null : intent.getStringExtra("action");
        final String mac = intent == null ? null : intent.getStringExtra("mac");
        Log.i(TAG, "service start action=" + action + " mac=" + mac);
        // The caller (root shell) starts this as a foreground service because
        // the ROM blocks background starts. Become a real FGS immediately so
        // the system does not kill us after the 5 s startForeground timeout.
        try {
            if (notification != null) {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
                } else {
                    startForeground(1, notification);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "startForeground failed", error);
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? BluetoothAdapter.getDefaultAdapter() : manager.getAdapter();
        if (adapter == null || mac == null || mac.length() == 0) {
            stopNow("adapter/device unavailable");
            return START_NOT_STICKY;
        }

        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(mac);
        } catch (Throwable error) {
            stopNow("invalid device " + error);
            return START_NOT_STICKY;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopNow("profile timeout");
            }
        }, 8000L);

        try {
            if (!adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profileId, BluetoothProfile proxy) {
                    profile = proxy;
                    boolean connect = "connect".equalsIgnoreCase(action);
                    if (connect) {
                        invoke(proxy, "setConnectionPolicy", device, POLICY_ALLOWED);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                invoke(proxy, "connect", device);
                                stopNow("connect requested");
                            }
                        }, 250L);
                    } else {
                        // Drop the current HID link first, then forbid the
                        // profile from immediately reconnecting it.
                        invoke(proxy, "disconnect", device);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                invoke(proxy, "setConnectionPolicy", device, POLICY_FORBIDDEN);
                                stopNow("disconnect requested");
                            }
                        }, 150L);
                    }
                }

                @Override
                public void onServiceDisconnected(int profileId) {
                    stopNow("profile service disconnected");
                }
            }, HID_HOST)) {
                stopNow("HID Host profile unavailable");
            }
        } catch (Throwable error) {
            stopNow("profile request failed " + error);
        }
        return START_NOT_STICKY;
    }

    private static void invoke(Object target, String name, Object... args) {
        if (target == null) {
            return;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != args.length) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(target, args);
                    Log.i(TAG, name + " result=" + result);
                    return;
                } catch (Throwable error) {
                    Log.e(TAG, name + " failed", error);
                    return;
                }
            }
        }
        Log.w(TAG, name + " method unavailable");
    }

    private synchronized void stopNow(String reason) {
        if (stopped) {
            return;
        }
        stopped = true;
        Log.i(TAG, reason);
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (adapter != null && profile != null) {
            try {
                adapter.closeProfileProxy(HID_HOST, profile);
            } catch (Throwable ignored) {
            }
        }
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        stopNow("service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
