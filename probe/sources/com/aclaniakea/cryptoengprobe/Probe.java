package com.aclaniakea.cryptoengprobe;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.ArrayList;
import java.util.List;

/**
 * CryptoEng / HTTP 旁路抓包探针（纯记录，不修改任何数据）。
 *
 * 目标包：com.coloros.findmyphone
 *  1) w8.e$a.call —— 记录 cryptoeng 请求/响应（重点解析 2016 rsa_version、2011 解密配置、2014 field=29）；
 *  2) okhttp3.internal.http.RealInterceptorChain.proceed —— 记录明文 HTTP 请求/响应。
 */
public final class Probe implements IXposedHookLoadPackage {
    private static final String TAG = "CryptoEngProbe";
    private static final String FIND_PHONE = "com.coloros.findmyphone";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!FIND_PHONE.equals(p.packageName)) {
            return;
        }
        final ClassLoader cl = p.classLoader;
        try {
            // FindSwitchConfigRequest.a(Context, brand, rpmbData, version)：打印 getConfig 实际参数
            XposedHelpers.findAndHookMethod("t4.b", cl, "a", android.content.Context.class,
                    String.class, String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                XposedBridge.log(TAG + ": getConfig args brand=" + param.args[1]
                                        + " version=" + param.args[3] + " rpmbData=" + param.args[2]);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": getConfig args log failed " + t);
                            }
                        }
                    });

            XposedBridge.log(TAG + ": getConfig args hook installed");
            try {
                XposedHelpers.findAndHookMethod("okhttp3.internal.http.BridgeInterceptor", cl, "intercept",
                        Class.forName("okhttp3.Interceptor$Chain", false, cl), new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object chain = param.args[0];
                                    Object req = XposedHelpers.callMethod(chain, "request");
                                    Object url = XposedHelpers.callMethod(req, "url");
                                    String u = XposedHelpers.callMethod(url, "toString").toString();
                                    if (u.contains("ifind.heytapmobi.com")) {
                                        Object headers = XposedHelpers.callMethod(req, "headers");
                                        XposedBridge.log(TAG + ": BRIDGE req " + u + " headers=" + headers);
                                        try {
                                            Object body = XposedHelpers.callMethod(req, "body");
                                            if (body != null) {
                                                Object len = XposedHelpers.callMethod(body, "contentLength");
                                                XposedBridge.log(TAG + ": BRIDGE bodyLen=" + len + " url=" + u);
                                            }
                                        } catch (Throwable t2) {
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": bridge hook fail " + t);
                                }
                            }
                        });
    
            XposedBridge.log(TAG + ": bridge hook installed");
            try {
                XposedHelpers.findAndHookMethod("okhttp3.internal.http.CallServerInterceptor", cl, "intercept",
                        Class.forName("okhttp3.Interceptor$Chain", false, cl), new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object chain = param.args[0];
                                    Object req = XposedHelpers.callMethod(chain, "request");
                                    Object url = XposedHelpers.callMethod(req, "url");
                                    String u = XposedHelpers.callMethod(url, "toString").toString();
                                    if (u.contains("getConfig")) {
                                        Object headers = XposedHelpers.callMethod(req, "headers");
                                        XposedBridge.log(TAG + ": CALLSERVER getConfig headers=" + headers);
                                        Object body = XposedHelpers.callMethod(req, "body");
                                        if (body != null) {
                                            Object len = XposedHelpers.callMethod(body, "contentLength");
                                            XposedBridge.log(TAG + ": CALLSERVER getConfig bodyLen=" + len);
                                            // 尝试读取 body
                                            try {
                                                Class<?> bufCls = XposedHelpers.findClass("np.e", cl);
                                                Object buffer = bufCls.newInstance();
                                                XposedHelpers.callMethod(body, "writeTo", buffer);
                                                Object snap = XposedHelpers.callMethod(buffer, "M");
                                                byte[] bytes = (byte[]) XposedHelpers.callMethod(snap, "h");
                                                XposedBridge.log(TAG + ": CALLSERVER getConfig bodyHex=" + hex(bytes));
                                            } catch (Throwable t3) {
                                                XposedBridge.log(TAG + ": CALLSERVER body read fail " + t3);
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": callserver hook fail " + t);
                                }
                            }
                        });
    
            XposedBridge.log(TAG + ": callserver hook installed");
            try {
                Class<?> rb = XposedHelpers.findClass("okhttp3.RequestBody", cl);
                XposedBridge.hookAllMethods(rb, "writeTo", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object sink = param.args[0];
                            if (sink != null) {
                                Object b64 = XposedHelpers.callMethod(sink, "base64");
                                XposedBridge.log(TAG + ": BODY writeTo base64=" + b64);
                            }
                        } catch (Throwable t) {
                            try {
                                XposedBridge.log(TAG + ": BODY writeTo str=" + param.args[0]);
                            } catch (Throwable t2) {
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + ": body hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": body hook fail " + t);
            }

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": callserver hook fail2 " + t);
            }

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": bridge hook fail2 " + t);
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getConfig args hook failed " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("w8.e$a", cl, "call", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object cmdObj = XposedHelpers.getObjectField(param.thisObject, "a");
                        if (cmdObj instanceof byte[]) {
                            byte[] cmd = (byte[]) cmdObj;
                            XposedBridge.log(TAG + ": ce req method=" + methodOf(cmd) + " len=" + cmd.length
                                    + " hex=" + hex(cmd));
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": ce req log failed " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object r = param.getResult();
                        if (!(r instanceof byte[])) {
                            return;
                        }
                        byte[] resp = (byte[]) r;
                        XposedBridge.log(TAG + ": ce resp len=" + resp.length + " hex=" + hex(resp));
                        int m = methodOf(resp);
                        if (m == 2016) {
                            XposedBridge.log(TAG + ": 2016 rsa_version=" + paramStr(resp, 29));
                        } else if (m == 2011) {
                            XposedBridge.log(TAG + ": 2011 decrypted config=" + paramStr(resp, 21));
                        } else if (m == 2014) {
                            int field = 0;
                            for (int[] pp : parseParams(resp)) {
                                field = pp[0];
                                break;
                            }
                            if (field == 29) {
                                XposedBridge.log(TAG + ": 2014 field29=" + paramStr(resp, 29));
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": ce resp log failed " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": cryptoeng hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cryptoeng hook failed " + t);
        }
        try {
            Class<?> req = Class.forName("okhttp3.Request", false, cl);
            XposedHelpers.findAndHookMethod("okhttp3.internal.http.RealInterceptorChain", cl, "proceed", req,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object r = param.args[0];
                                Object url = XposedHelpers.callMethod(r, "url");
                                Object headers = XposedHelpers.callMethod(r, "headers");
                                StringBuilder sb = new StringBuilder(TAG).append(": http >> ")
                                        .append(XposedHelpers.callMethod(r, "method")).append(' ')
                                        .append(XposedHelpers.callMethod(url, "toString"));
                                try {
                                    Object body = XposedHelpers.callMethod(r, "body");
                                    sb.append(" bodyLen=").append(XposedHelpers.callMethod(body, "contentLength"));
                                    try {
                                        Class<?> bufCls = XposedHelpers.findClass("np.e", cl);
                                        Object buffer = bufCls.newInstance();
                                        XposedHelpers.callMethod(body, "writeTo", buffer);
                                        Object snap = XposedHelpers.callMethod(buffer, "M");
                                        byte[] bytes = (byte[]) XposedHelpers.callMethod(snap, "h");
                                        sb.append(" bodyHex=").append(hex(bytes));
                                    } catch (Throwable t2) {
                                        sb.append(" bodyReadFail=").append(t2);
                                    }
                                } catch (Throwable ignored) {
                                }
                                sb.append(" headers=").append(XposedHelpers.callMethod(headers, "toString"));
                                XposedBridge.log(sb.toString());
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": http req log failed " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object resp = param.getResult();
                                if (resp == null) {
                                    return;
                                }
                                StringBuilder sb = new StringBuilder(TAG).append(": http << code=")
                                        .append(XposedHelpers.callMethod(resp, "code"))
                                        .append(" headers=")
                                        .append(XposedHelpers.callMethod(XposedHelpers.callMethod(resp, "headers"), "toString"));
                                XposedBridge.log(sb.toString());
                                try {
                                    Object peek = XposedHelpers.callMethod(resp, "peekBody", 1_000_000L);
                                    String b = (String) XposedHelpers.callMethod(peek, "string");
                                    if (b != null && b.length() > 1500) {
                                        b = b.substring(0, 1500) + "...(truncated len=" + b.length() + ")";
                                    }
                                    byte[] rb = b.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                                    XposedBridge.log(TAG + ": http body=" + b + " hex=" + hex(rb));
                                } catch (Throwable t2) {
                                    XposedBridge.log(TAG + ": http body read failed " + t2);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": http resp log failed " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": okhttp hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": okhttp hook failed " + t);
        }
    }

    // ============ MethodBuffer 解析 ============

    private static int methodOf(byte[] cmd) {
        if (cmd == null || cmd.length < 4) {
            return 0;
        }
        return intAt(cmd, 0);
    }

    private static int intAt(byte[] cmd, int off) {
        return ((cmd[off] & 0xff) << 24) | ((cmd[off + 1] & 0xff) << 16)
                | ((cmd[off + 2] & 0xff) << 8) | (cmd[off + 3] & 0xff);
    }

    private static List<int[]> parseParams(byte[] cmd) {
        List<int[]> out = new ArrayList<>();
        if (cmd == null || cmd.length < 12) {
            return out;
        }
        int count = intAt(cmd, 8);
        int off = 12;
        for (int i = 0; i < count && off + 8 <= cmd.length; i++) {
            int ptype = intAt(cmd, off);
            int plen = intAt(cmd, off + 4);
            off += 8;
            if (plen < 0 || off + plen > cmd.length) {
                break;
            }
            out.add(new int[]{ptype, off, plen});
            off += plen;
        }
        return out;
    }

    private static String paramStr(byte[] cmd, int ptype) {
        for (int[] p : parseParams(cmd)) {
            if (p[0] == ptype) {
                byte[] d = java.util.Arrays.copyOfRange(cmd, p[1], p[1] + p[2]);
                return new String(d, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String hex(byte[] data) {
        if (data == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
