package com.aclaniakea.cryptoengrelay;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 真机 CryptoEng TA 执行器（跑在一加 13T 原厂 ColorOS 上）。
 *
 * 监听 TCP 30000：接收平板转发的 CryptoEng 命令（MethodBuffer），用真机原厂
 * CryptoEngManager（TA 可用、RPMB 含服务器认可公钥）执行并返回密文响应。
 * 这样平板的注册/关闭指令由真机 TA 加密，应用侧流程保持原厂。
 */
public final class RelayServer implements IXposedHookLoadPackage {
    private static final String TAG = "CryptoEngRelay";
    private static final int PORT = 30000;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.coloros.findmyphone".equals(p.packageName)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(PORT);
                XposedBridge.log(TAG + ": listening on " + PORT);
                while (true) {
                    try {
                        Socket s = ss.accept();
                        handle(s);
                    } catch (Throwable t1) {
                        XposedBridge.log(TAG + ": accept error " + t1);
                    }
                }
            } catch (Throwable t2) {
                XposedBridge.log(TAG + ": server failed " + t2);
            }
        }, "cryptoeng-relay");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Socket s) {
        try (Socket sock = s) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            int len = in.readInt();
            if (len <= 0 || len > 65536) {
                return;
            }
            byte[] cmd = new byte[len];
            in.readFully(cmd);
            byte[] resp = exec(cmd);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            if (resp == null) {
                out.writeInt(0);
            } else {
                out.writeInt(resp.length);
                out.write(resp);
            }
            out.flush();
            XposedBridge.log(TAG + ": served len=" + len + " respLen=" + (resp == null ? -1 : resp.length));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": handle error " + t);
        }
    }

    private static byte[] exec(byte[] cmd) {
        try {
            Class<?> mgr = Class.forName("com.oplus.hardware.cryptoeng.CryptoEngManager");
            Object inst = mgr.getMethod("getInstance").invoke(null);
            return (byte[]) mgr.getMethod("cryptoEngCommand", byte[].class).invoke(inst, new Object[]{cmd});
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CryptoEngManager failed " + t);
        }
        try {
            Class<?> cls = Class.forName("w8.e$a");
            Object callable = cls.getDeclaredConstructor(byte[].class).newInstance(cmd);
            return (byte[]) cls.getMethod("call").invoke(callable);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": w8.e$a fallback failed " + t);
        }
        return null;
    }
}
