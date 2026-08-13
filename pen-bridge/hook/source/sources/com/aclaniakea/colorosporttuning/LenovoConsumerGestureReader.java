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
    public void run() {
        while (running) {
            String strFindNode = findNode();
            if (strFindNode == null) {
                sleep(1500L);
            } else {
                HookUtils.log("consumer gesture reader: " + strFindNode);
                try {
                    FileInputStream fileInputStream = new FileInputStream(strFindNode);
                    try {
                        int iFd = fd(fileInputStream.getFD());
                        int iNativeGrab = nativeGrab(iFd, 1);
                        if (iNativeGrab != 0) {
                            throw new IllegalStateException("EVIOCGRAB=" + iNativeGrab);
                        }
                        HookUtils.log("consumer gesture EVIOCGRAB active");
                        try {
                            readEvents(fileInputStream);
                        } finally {
                            nativeGrab(iFd, 0);
                        }
                        fileInputStream.close();
                    } catch (Throwable th) {
                        try {
                            fileInputStream.close();
                        } catch (Throwable unused) {
                            th.addSuppressed(unused);
                        }
                        throw th;
                    }
                } catch (Throwable th) {
                    HookUtils.log("consumer gesture reader retry: " + th);
                    sleep(1000L);
                }
            }
        }
    }

    private void readEvents(FileInputStream fileInputStream) throws Exception {
        byte[] bArr = new byte[24];
        outer:
        while (true) {
            int iValue = 0;
            while (running) {
                int i = 0;
                while (i < 24) {
                    int iRead = fileInputStream.read(bArr, i, 24 - i);
                    if (iRead < 0) {
                        throw new EOFException();
                    }
                    i += iRead;
                }
                ByteBuffer byteBufferOrder = ByteBuffer.wrap(bArr).order(ByteOrder.nativeOrder());
                int iType = byteBufferOrder.getShort(16) & 65535;
                int iCode = 65535 & byteBufferOrder.getShort(18);
                int iData = byteBufferOrder.getInt(20);
                if (iType == 4 && iCode == 4) {
                    iValue = iData;
                } else if (iType == 1 && iCode == 240 && iData == 0 && iValue != 0) {
                    SystemStylusHooks.onConsumerGesture(this.context, iValue);
                    continue outer;
                }
            }
            return;
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

    private static void sleep(long j) {
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
