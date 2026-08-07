package com.aclaniakea.colorosporttuning;

import android.content.Context;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes.dex */
final class LenovoConsumerGestureReader implements Runnable {
    private static volatile boolean nativeReady;
    private static volatile boolean running;
    private final Context context;

    private static native int nativeGrab(int i, int i2);

    private LenovoConsumerGestureReader(Context context) {
        this.context = context;
    }

    static synchronized void start(Context context) {
        if (running) {
            return;
        }
        if (loadNative(context)) {
            running = true;
            Thread thread = new Thread(new LenovoConsumerGestureReader(context.getApplicationContext()), "LenovoPenConsumerGesture");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override // java.lang.Runnable
    public void run() throws InterruptedException {
        long j;
        FileInputStream fileInputStream;
        int iFd;
        int iNativeGrab;
        while (running) {
            String strFindNode = findNode();
            if (strFindNode == null) {
                j = 1500;
            } else {
                HookUtils.log("consumer gesture reader: " + strFindNode);
                try {
                    fileInputStream = new FileInputStream(strFindNode);
                    try {
                        iFd = fd(fileInputStream.getFD());
                        iNativeGrab = nativeGrab(iFd, 1);
                    } finally {
                    }
                } catch (Throwable th) {
                    HookUtils.log("consumer gesture reader retry: " + th);
                    j = 1000;
                }
                if (iNativeGrab != 0) {
                    throw new IllegalStateException("EVIOCGRAB=" + iNativeGrab);
                }
                HookUtils.log("consumer gesture EVIOCGRAB active");
                try {
                    readEvents(fileInputStream);
                    fileInputStream.close();
                } finally {
                    nativeGrab(iFd, 0);
                }
            }
            sleep(j);
        }
    }

    private void readEvents(FileInputStream fileInputStream) throws Exception {
        byte[] bArr = new byte[24];
        while (true) {
            int i = 0;
            while (running) {
                int i2 = 0;
                while (i2 < 24) {
                    int i3 = fileInputStream.read(bArr, i2, 24 - i2);
                    if (i3 < 0) {
                        throw new EOFException();
                    }
                    i2 += i3;
                }
                ByteBuffer byteBufferOrder = ByteBuffer.wrap(bArr).order(ByteOrder.nativeOrder());
                int i4 = byteBufferOrder.getShort(16) & 65535;
                int i5 = 65535 & byteBufferOrder.getShort(18);
                int i6 = byteBufferOrder.getInt(20);
                if (i4 == 4 && i5 == 4) {
                    i = i6;
                } else if (i4 != 1 || i5 != 240 || i6 != 0 || i == 0) {
                }
            }
            return;
            SystemStylusHooks.onConsumerGesture(this.context, i);
        }
    }

    private static String findNode() {
        for (int i = 0; i < 32; i++) {
            File file = new File("/sys/class/input/event" + i + "/device/name");
            if (file.isFile()) {
                try {
                    BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
                    try {
                        String line = bufferedReader.readLine();
                        if (line != null && line.toLowerCase().contains("lenovo tab pen pro consumer control")) {
                            String str = "/dev/input/event" + i;
                            bufferedReader.close();
                            return str;
                        }
                        bufferedReader.close();
                    } finally {
                    }
                } catch (Throwable unused) {
                    continue;
                }
            }
        }
        return null;
    }

    private static synchronized boolean loadNative(Context context) {
        if (nativeReady) {
            return true;
        }
        try {
            String str = context.createPackageContext("com.aclaniakea.lenovopenbridge", 0).getApplicationInfo().nativeLibraryDir + "/libpeninput.so";
            System.load(str);
            nativeReady = true;
            HookUtils.log("native pen input loaded: " + str);
            return true;
        } catch (Throwable th) {
            HookUtils.log("native pen input load failed: " + th);
            return false;
        }
    }

    private static int fd(FileDescriptor fileDescriptor) throws Exception {
        String[] strArr = {"descriptor", "fd"};
        for (int i = 0; i < 2; i++) {
            try {
                Field declaredField = FileDescriptor.class.getDeclaredField(strArr[i]);
                declaredField.setAccessible(true);
                return declaredField.getInt(fileDescriptor);
            } catch (NoSuchFieldException unused) {
            }
        }
        throw new NoSuchFieldException("FileDescriptor fd");
    }

    private static void sleep(long j) throws InterruptedException {
        try {
            Thread.sleep(j);
        } catch (InterruptedException unused) {
            Thread.currentThread().interrupt();
        }
    }

    private LenovoConsumerGestureReader() {
        this.context = null;
    }
}
