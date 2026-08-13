package com.aclaniakea.phonejoinprobe;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 查找设备密钥探针（纯记录，不修改任何数据）。
 *
 * 目标：com.coloros.findmyphone
 * 1) PhoneJoin：finding_phone_join_en.xml（Tink + AndroidKeyStore 加密），
 *    调用应用自身 PhoneJoinDataSource.create(Context)+restore() 解密后打印 mPublicKey 等字段。
 * 2) RPMB：hook w8/h.a（callRpmbMethodWithSummary）抓 GET_ALL_RPMB_INFO(2016)/GET_RPMB_VALUE(2014)
 *    原始请求/响应字节，并主动触发 v8/b.a 读取全部 RPMB 信息，用于提取 RSA-1024 服务器公钥。
 */
public final class Probe implements IXposedHookLoadPackage {
    private static final String TAG = "PhoneJoinProbe";
    private static final String FIND_PHONE = "com.coloros.findmyphone";
    private static final String DS_CLS = "com.heytap.finding.data.PhoneJoinDataSource";
    private static final String RPMB_UTIL = "w8.h";
    private static final String RPMB_DEVICE = "v8.b";
    private static final String RPMB_CHANNEL = "w8.e";
    private static final String RESULT_SUMMARY = "w8.i$b";
    private static final String RESULT_WRAPPER = "u2.a";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!FIND_PHONE.equals(p.packageName)) {
            return;
        }
        final ClassLoader cl = p.classLoader;

        // 1) hook restore()：应用自然读取 PhoneJoin 时直接打印
        try {
            XposedHelpers.findAndHookMethod(DS_CLS, cl, "restore", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    dump(param.getResult());
                }
            });
            XposedBridge.log(TAG + ": restore hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": restore hook failed " + t);
        }

        // 2) hook w8/h.a：抓 RPMB 原始请求/响应字节
        try {
            XposedHelpers.findAndHookMethod(RPMB_UTIL, cl, "a",
                    android.content.Context.class, byte[].class,
                    XposedHelpers.findClass("w8.c", cl), boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                byte[] req = (byte[]) param.args[1];
                                Object method = param.args[2];
                                XposedBridge.log(TAG + ": rpmb req method=" + method
                                        + " reqHex=" + hex(req));
                                Object result = param.getResult();
                                if (result != null) {
                                    Object summary = getField(result, "b");
                                    if (summary != null) {
                                        byte[] raw = (byte[]) getField(summary, "f");
                                        String err = (String) getField(summary, "e");
                                        int code = (Integer) getField(summary, "c");
                                        XposedBridge.log(TAG + ": rpmb resp code=" + code
                                                + " err=" + err
                                                + " rawLen=" + (raw == null ? -1 : raw.length)
                                                + " rawHex=" + hex(raw));
                                    } else {
                                        XposedBridge.log(TAG + ": rpmb resp no summary result=" + result);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": rpmb resp log failed " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": rpmb hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": rpmb hook failed " + t);
        }

        // 3) hook w8/e.a：原始通道请求/响应
        try {
            XposedHelpers.findAndHookMethod(RPMB_CHANNEL, cl, "a", byte[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                byte[] req = (byte[]) param.args[0];
                                Object result = param.getResult();
                                byte[] resp = null;
                                if (result != null) {
                                    resp = (byte[]) getField(result, "b");
                                }
                                XposedBridge.log(TAG + ": channel req=" + hex(req)
                                        + " resp=" + hex(resp));
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": channel log failed " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": channel hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": channel hook failed " + t);
        }

        // 4) 主动触发：延迟读取 PhoneJoin + RPMB + 字段名探测（带重试）
        schedule(cl);
    }

    private static void schedule(final ClassLoader cl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i <= 4; i++) {
                    try {
                        Thread.sleep(4000L);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    try {
                        Object context = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread", cl),
                                "currentApplication");
                        if (context == null) {
                            XposedBridge.log(TAG + ": currentApplication null, retry");
                            continue;
                        }
                        Class<?> ds = Class.forName(DS_CLS, false, cl);
                        Object dataSource = XposedHelpers.callStaticMethod(ds, "create", context);
                        Object join = XposedHelpers.callMethod(dataSource, "restore");
                        dump(join);
                        // 读取全部 RPMB 信息（GET_ALL_RPMB_INFO）
                        try {
                            Object rpmbResult = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass(RPMB_DEVICE, cl), "a",
                                    context, Boolean.FALSE);
                            dumpRpmbResult(rpmbResult);
                        } catch (Throwable t2) {
                            XposedBridge.log(TAG + ": proactive rpmb failed " + t2);
                        }
                        // 字段名探测：GET_RPMB_VALUE 按 key 名读字段
                        probeRpmbFields(cl);
                        // 命令号探测：GET_ALL_REGISTER_INFO + 未知号段（GetMemberFromRpmb）
                        probeRpmbCommands(cl);
                        return;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": proactive dump attempt " + i + " failed " + t);
                    }
                }
            }
        }, "phonejoin-probe").start();
    }

    private static void dump(Object join) {
        if (join == null) {
            XposedBridge.log(TAG + ": PhoneJoin is null");
            return;
        }
        try {
            XposedBridge.log(TAG + ": PhoneJoin class=" + join.getClass().getName());
            XposedBridge.log(TAG + ": mIdentityKey=" + str(XposedHelpers.callMethod(join, "getIdentityKey")));
            XposedBridge.log(TAG + ": mJoinTime=" + str(XposedHelpers.callMethod(join, "getJoinTime")));
            XposedBridge.log(TAG + ": mKeyAgreementStrategy=" + XposedHelpers.callMethod(join, "getKeyAgreementStrategy"));
            XposedBridge.log(TAG + ": mPublicKey=" + str(XposedHelpers.callMethod(join, "getPublicKey")));
            XposedBridge.log(TAG + ": mRotationKeyFast=" + str(XposedHelpers.callMethod(join, "getRotationKey", true)));
            XposedBridge.log(TAG + ": mRotationKeySlow=" + str(XposedHelpers.callMethod(join, "getRotationKey", false)));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dump failed " + t);
        }
    }

    private static String str(Object o) {
        return o == null ? "null" : o.toString();
    }

    private static Object getField(Object o, String name) {
        try {
            return XposedHelpers.getObjectField(o, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dumpRpmbResult(Object result) {
        try {
            if (result == null) {
                XposedBridge.log(TAG + ": rpmb result null");
                return;
            }
            XposedBridge.log(TAG + ": rpmb result class=" + result.getClass().getName());
            Object data = getField(result, "b");
            Object method = getField(result, "c");
            XposedBridge.log(TAG + ": rpmb data=" + data + " method=" + method);
            if (data != null) {
                // RpmbData 字段
                for (String f : new String[]{"mAccountName", "mAesKey", "mImei",
                        "mLockdeadState", "mRsaVersion", "mSsoid", "mUniqueId", "errMsg"}) {
                    try {
                        XposedBridge.log(TAG + ": rpmbData." + f + "="
                                + XposedHelpers.getObjectField(data, f));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dumpRpmbResult failed " + t);
        }
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

    private static byte[] be(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] buildGetRpmbValue(String name, int type) {
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[12 + 8 + nameBytes.length];
        System.arraycopy(be(2014), 0, out, 0, 4);      // GET_RPMB_VALUE
        System.arraycopy(be(0x2710), 0, out, 4, 4);    // 固定头
        System.arraycopy(be(1), 0, out, 8, 4);         // param count
        System.arraycopy(be(type), 0, out, 12, 4);     // param type
        System.arraycopy(be(nameBytes.length), 0, out, 16, 4);
        System.arraycopy(nameBytes, 0, out, 20, nameBytes.length);
        return out;
    }

    private static void probeRpmbFields(final ClassLoader cl) {
        final String[][] names = {
            {"RSA_KEY_VERSION", "29"},   // 已知字段做对照
            {"publicKey", "0"},
            {"PUBLIC_KEY", "0"},
            {"public_key", "0"},
            {"modulus", "0"},
            {"MODULUS", "0"},
            {"memberKey", "0"},
            {"MEMBER_KEY", "0"},
            {"member_key", "0"},
            {"rsaKey", "0"},
            {"RSA_KEY", "0"},
            {"rsaPublicKey", "0"},
            {"RSA_PUBLIC_KEY", "0"},
            {"serverKey", "0"},
            {"SERVER_KEY", "0"},
            {"srvPubKey", "0"},
            {"findKey", "0"},
            {"FIND_KEY", "0"},
            {"key", "0"},
            {"KEY", "0"},
            // 全部已知 type+name 组合（只读枚举）
            {"PROTOCOL_VERSION", "1"},
            {"REQ_RESP_TYPE", "2"},
            {"INSTRUCTION_TYPE", "3"},
            {"MSG_ID", "7"},
            {"CONTENT", "8"},
            {"PHONE_NUMBER", "9"},
            {"USING_AES_KEY", "10"},
            {"TOKEN", "12"},
            {"PHONE_CARD", "13"},
            {"MOBILE_NAME", "14"},
            {"TICKET", "15"},
            {"AES_KEY", "16"},
            {"LAST_UNIQUE_ID", "17"},
            {"LAST_DEVICE_ID", "18"},
            {"LAST_USER_ID", "19"},
            {"CIPHER_TEXT", "20"},
            {"TEXT", "21"},
            {"COMMAND_BODY", "22"},
            {"KEYGUARD_TOKEN", "23"},
            {"RPMB_DATA_STR", "24"},
            {"SHA_256", "25"},
            {"LOCK_DEAD_STATE", "26"},
            {"RESULT_CODE", "27"},
            {"UPDATE_DATA", "28"},
            {"IS_FIND_PHONE_OPEN", "30"},
            {"USING_TMP_AES", "31"},
            {"SCREEN_UNLOCK_SUPPORT_STATE", "32"},
            {"LOCK_SCREEN_PWD_TYPE", "33"},
            {"SCREEN_UNLOCK_VERIFY_STATE", "34"},
            {"VERIFY_PASSWORD", "35"},
            {"SCREEN_UNLOCK_RETRY_COUNT_LEFT", "36"},
            {"SALT", "37"},
            {"SOURCE_TYPE", "38"},
            {"PWD_INFO", "39"},
            {"VERIFY_PWD_TYPE", "40"},
            {"VERIFY_PWD_BUFFER", "41"},
            {"CHALLENGE", "42"},
            {"MODIFY_PWD_INFO_TYPE", "43"},
            {"MODIFY_PWD_BUFFER", "44"},
            {"MODIFY_PWD_RESULT", "45"},
            {"PWD_TYPE", "46"},
        };
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2500L);
                } catch (InterruptedException ignored) {
                    return;
                }
                for (String[] n : names) {
                    try {
                        String name = n[0];
                        int type = Integer.parseInt(n[1]);
                        byte[] req = buildGetRpmbValue(name, type);
                        Object result = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass(RPMB_CHANNEL, cl), "a", req);
                        byte[] resp = result == null ? null : (byte[]) getField(result, "b");
                        XposedBridge.log(TAG + ": field[" + name + ",t" + type + "] -> "
                                + hex(resp));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": field probe " + n[0] + " failed " + t);
                    }
                }
            }
        }, "phonejoin-fields").start();
    }

    private static byte[] buildRequest(int method, java.util.List<byte[]> params) {
        int len = 12;
        for (byte[] p : params) {
            len += 8 + p.length;
        }
        byte[] out = new byte[len];
        System.arraycopy(be(method), 0, out, 0, 4);
        System.arraycopy(be(0x2710), 0, out, 4, 4);
        System.arraycopy(be(params.size()), 0, out, 8, 4);
        int off = 12;
        for (byte[] p : params) {
            // 参数头由调用方预先拼好 type+len+data
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static byte[] param(int type, String s) {
        byte[] d = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[8 + d.length];
        System.arraycopy(be(type), 0, out, 0, 4);
        System.arraycopy(be(d.length), 0, out, 4, 4);
        System.arraycopy(d, 0, out, 8, d.length);
        return out;
    }

    private static void probeRpmbCommands(final ClassLoader cl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(6000L);
                } catch (InterruptedException ignored) {
                    return;
                }
                // 1) GET_ALL_REGISTER_INFO (2002) 带 deviceId
                try {
                    java.util.List<byte[]> ps = new java.util.ArrayList<>();
                    ps.add(param(4, "cb88891116e312e9708daca76cfcc763d0c6bb4634a32a85feb8f3b96e813174"));
                    ps.add(param(11, "182******410"));
                    byte[] req = buildRequest(2002, ps);
                    Object result = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass(RPMB_CHANNEL, cl), "a", req);
                    byte[] resp = result == null ? null : (byte[]) getField(result, "b");
                    XposedBridge.log(TAG + ": cmd[2002 GET_ALL_REGISTER_INFO] -> " + hex(resp));
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": cmd 2002 failed " + t);
                }
                // 2) 全量枚举 2000-2024（跳过已知写命令）
                int[] knownWrites = {2006, 2013, 2015, 2019, 2021};
                for (int m = 2000; m <= 2024; m++) {
                    boolean skip = false;
                    for (int w : knownWrites) {
                        if (m == w) skip = true;
                    }
                    if (skip) continue;
                    try {
                        byte[] req = buildRequest(m, new java.util.ArrayList<byte[]>());
                        Object result = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass(RPMB_CHANNEL, cl), "a", req);
                        byte[] resp = result == null ? null : (byte[]) getField(result, "b");
                        XposedBridge.log(TAG + ": cmd[" + m + "] -> " + hex(resp));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": cmd " + m + " failed " + t);
                    }
                    try {
                        Thread.sleep(120L);
                    } catch (InterruptedException ignored) {
                    }
                }
                // 3) 带 param 2000 试探（dump_rpmb 风格）
                for (int m = 2000; m <= 2024; m++) {
                    boolean skip = false;
                    for (int w : knownWrites) {
                        if (m == w) skip = true;
                    }
                    if (skip) continue;
                    try {
                        java.util.List<byte[]> ps = new java.util.ArrayList<>();
                        ps.add(param(2000, "00"));
                        byte[] req = buildRequest(m, ps);
                        Object result = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass(RPMB_CHANNEL, cl), "a", req);
                        byte[] resp = result == null ? null : (byte[]) getField(result, "b");
                        XposedBridge.log(TAG + ": cmdP2k[" + m + "] -> " + hex(resp));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": cmdP2k " + m + " failed " + t);
                    }
                    try {
                        Thread.sleep(120L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "phonejoin-cmds").start();
    }
}
