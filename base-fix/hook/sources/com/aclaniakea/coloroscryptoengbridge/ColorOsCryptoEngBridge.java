package com.aclaniakea.coloroscryptoengbridge;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.util.Base64;
import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 查找设备（com.coloros.findmyphone）CryptoEng/RPMB 软件模拟器。
 *
 * 移植机的 TrustZone 未 provision OPLUS cryptoeng TA（真版服务加载 TA 报
 * Error 12），因此无法走真加密通道。本模拟器在应用进程内实现同一套协议：
 *  - 应用 MethodBuffer（方法 2001-2022 + 参数）在 w8.e$a.call() 处被拦截；
 *  - RPMB 语义用应用私有文件持久化；
 *  - 公钥加密使用从 OTA cryptoeng TA 中提取的 OPLUS 服务器 RSA-2048 公钥；
 *  - 指令 JSON 结构按 TA 反汇编还原：
 *    {accountName,deviceId,token,mobileName,ticket,protocolVersion,phoneCard,messageKey}
 *  - 全部命令都打日志，便于按实测继续迭代。
 */
public final class ColorOsCryptoEngBridge implements IXposedHookLoadPackage {
    private static final String FIND_PHONE = "com.coloros.findmyphone";
    private static final String OLD_AUTHORITY = "com.heytap.appplatform.dispatcher";
    private static final String NEW_AUTHORITY = "com.oplus.appplatform.dispatcher";
    private static final String AIDL_SERVICE = "vendor.oplus.hardware.cryptoeng.ICryptoeng/default";
    private static final String TAG = "ColorOsCryptoEngBridge";

    // 服务器公钥候选（X.509 SPKI）：
    //  - 前 11 位：2026-08-10 从真机 PKX110 cryptoeng.b01 TA 提取的内置 RSA-1024 公钥表
    //    （2010 GET_ENCRYPT_TMP_AES 在 IS_FIND_PHONE_OPEN=0 时用 RSA-1024，实测输出 128B）
    //  - 前 7 位：2026-08-08 从 OTA system_ext / findmyphone classes2.dex 提取的 RSA-1024
    //    usercenter 平台公钥（版本轮换历史，其中一把应为注册密钥）
    //  - 其后：服务器 222 下发的 RSA-512 传输密钥；OTA cryptoeng TA 内的 RSA-2048
    private static final String[] EMBEDDED_PUBLIC_KEYS = {
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDiKT6qa9VZHpzmR9W21+Pxjp7pg18Qn2PsBETMP/jZOhfgT/7YTc1GVHS/CsRnnKfYiWVM/VgqRw/0N7ZVsB3tpzn8T6PEdTqjmKdF9WbLfGX7gCPm//2ZH45r/16TZt9sb8P2OC7/abWsrrvGcRZr0Pgi2fiiciDS4jpwS96rLwIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDaUZu4sioUdVhAjSdw+jFIsCAhNPpMV6gRiPOnriHptivRzaf42AyKdiI1RM4/JSmDfXmnMdbssr/aNLb2sjvzeFoEgzM+ouKBghPUNRdjm57EjZFMA3fHcVvug23VeIj2LHnCSrR5kHC/3zRWlnHjDmiRvOrLM8C+Rdf8MP0BOwIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDSnyq3OBnHF5VzeK71y3WDfxlLy4b7ShWathcESQeN9mZKBvYFp99mgjz/th1XiTNfnAV1f/Nd3DRlcoUipBQbQcPk0J5p1es4dHBD3NlQ5Jdtc9b7yKf6tMLEnV0M1Z95s1TCt2w9fcst+MTzeFozKrgMbQb68mLTQtC9yEqlDQIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDUqZAV3r8sxI2d+6HC5IPjeWUi07dJbE2UHyKxYOc6ALE4oqsPtGyq554043xAeFOy+SPqoJrqYMiPpq/fKQlLBh4xrRfa2NHpM6tbGAhbh/ilH/273Njtl1fkw3PW8J4BpptIjnq0u+WIkcUq30u60Is+A5d3L0d+UQyuZY3ehwIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDfOvklXiQ3JkCXL+BKulIbUa+EBjIkDM9EqHenrbWMWMzIMbcNvAiK4KaMwnPlGmSS6O1Mbwump/Oa9W9pyjwi0BWoICdB+ENCf7GToQSF2qAc1sb3ip7qXHinVcRrBYvAg8vOgwX4shYr3wY/uOwW2kMzwY+wuKyu1JS42m9qwwIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDdrgDNoHIaBYruL9RxS/A+5cHhKYumZzCY5xLv3RIBkCRY8HaS5z27I+HO+aHUOBs/ILMPZWqPVVc27rKERPyRiOGk3TtKQE18hu3htULvuWHNWBl3Aq5YgNsTPccfne3/rJj8zfliBIORiQ2GQ4wMxxuQTb4vxXzNQvXTrY79nQIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDCp0cHSGrI1LMg4Zju/1pvMHqlR0DcFmJC8SzcuMdV3gc8nbHQ3wKCsEhY4TSrz7SFIyZ4T3pZb/uMPd89bAJHnOVeSfEFCx+/SA/cELk9HRB3KnP5373N8x/rbmTKK3hP+HPCEO95lTMeeTUJ/4gbtD5M4ScudYBYEQMhI5aatQIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDjA6/+mNILe3LpO468pfas5SIGstde/YlLFmcygyJYjmKktC35FhNU9p8v+bsOfoxvCNrI6RxmEHCTkI3PkDpDiUnrgyr+WofOdEJBDYxzUbx7IMX99gtl7aku/A/1UPmNNzaaIN/D4ye8mHLBFEtx6YMU/yTiFBW2bw8yndmY0QIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDiDPvDM5tHiCfyJt7rXu5A9mNbNSP11Qdh36KfWDAEIiu02dpGf0j1T9Dq16BFimKLjKxzXvo2ZT66bLpea5IpXmoP1tKllYbacsWeyWs3Xkubd+FnGh4w2EFoQNOctPbrKiLfeCnSZJJbL3hkSqKmaz5QsXqxjVm0Vbq2kYWjLwIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCUV/DgKP2985xDTT79N08jUo3hTP5MVYCCuj/+UeEw1TvZcx3LJby7P6Xad6a1/BqveaGyFKIfEFIaBUBItk801sDDpDaYc4gL00Xc7lFuBHOZkxJYlss5QrGpuOEl9ZwUt5IrFLBdYaKqNHzNVC1pCPfb/JyH6Dr2HUxqgxUwAQIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCcU6G2L8AG9d9c0UpOyL1tMvFe5Ttw0KjlQVdsh1MP6yigYo9DYuwubHFVW2r0dBTqegP2/KTOxKzaHfC1qf0RGDsUoJCNJrd1cwoCLG8P2EF4w3OBrKqv8u4ytY0F+Vlanj5lm3TaoHSVF1+NWPyOTiwevIECGKwSxvlki4fDAAIDAQAB",

        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCRQ+6nTqMd+KQybI1rgCshl+kE79RAd6lsPvw8EXsW814wI0fNj4APoy7lMovhyebIJk7VVHKeOyRoENtMHcUty3i1eYQ94CBbXpmOovYa6xG/16a3vO1tyaA8/GDJrQLHo/CgNbzko8hZF1kGrO5zc4jcZweKdiMQB2pl+DN2owIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCgDSKdbFOJr3g+NgbStgsElK+uou2cbmuOqM3ZrUsPOBNwv0RDSVgO86QCFzOAdQGENaF2mjpr7SCIvT6GFWy2Nvk68p/NscwD4HCm0g2jUGhM4274S0f7LdkKiI9qrQQx/xwwspUpo/hQgtLGdqGwfGD/OYtFVM2DK6s8zBaecQIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLCSnM2rZ/Zm/LnTTdDaMkxg7xjTJmn09Dl1Sf0z9l4CrGCsuC5y+6ByK6pYMGB8ia80WlJpvNP8qSgY+EkaZ5axhn+H6YEUua1T0ZR4CYcUKJIXyLypszLJJ3kHur9T0gU4HRctaAqOaTC9xKvT/3BUZBUNVXQk/CY7L8nCtJ4wIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDUi5cTlCSDfczffuYb2UyvrKXuW/7xqBhLYG1ro+PmCNdJ01U1o7uc18YP6VNl5ZBF1IadY/XC6JphzBzCARVOqk1OE/Qfb1dQF6tO2nEZmDVDFeMHXsDtM1Jic/ntBcAy7Y6GP3OyqPRLgUribU7+m4CmAtk8b5y117cyWMBsXwIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDpgSW5VkZ6/xvh+wMXezrOokNdiupuvuMj4RVJy44byWDupl4H37z907A26RVdFzMeyLUQB4rsDIaXdxCODlljWW+/K96uF5MsDtOFUBw7VlOclIjcYTv/YDQEul8JoXoOuy1Yf3b5sbTpTuVTcl97tAuLJ8PoGe2K7N3B1eUQqQIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCQPC5pXZXcFlU/z4QI17x67TWeQ68RbH7Ft6d8CySz4UrzOPy0BDFrNYeiXaH40Kwu+RejR9ZxTNy2HzlmVHXVNfu0zk0gGf7h+0hmbgFR4H26BEmsIQRxThkdf+ihWbhZu//AAIukj5Vy1aIwNBFg5tBng3QK357TqxYiEPQ7yQIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDMYyJUOd0HWc+unxl/VKUUNq1WM4upHb63x+RF1kMFkJNJUyLIaoXJyAvoM3W34qtOkI+PX3MaDrYNfNqgT/g47V2DUDl2/cDWG3PhMHTUuB6ffJoSu5IkAYgYtIudF2guzvTB4/ZABAbElHjS2GnJ5sOmVgdio4hi3CecwMCIXQIDAQAB",
        "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAKMjoNhKnpqt47eBRdaccgOlCYqkBIvqBzLkRTakY8/3Bz79EYcmpd3LJ61KoougpkYEGTNZ/pfEisXNKoG+uC8CAwEAAQ==",
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwN83/Be74JadP4beljJ9RKUWoM0h8ZnU7OrLfBhYCJSl7JvFi98aHpk4mYcee8CNOd84XXB4B9Oe2ZPouXJRxc6jMFKp8udAcBTLRKJyC8LlQPk+5aYOs/nsSmPAuCkAdJxXO6ilBJBx8b2D2T/WpeI8Ko/vJ2DDxp/LuuxgfbfmhDK+T/tYJiIDW9S01fv145YucMDkLr38Lu7iQVXANC59JHJpy0exFECDfWf0hvYxq/F5pLK1LhL5hBfwYm8nPhNYsVQNIZpzN6Ewz2+S3Pbp/KzbLijRfgJLI6AV8jhlZAnqDG6OGxegccizm8mr6cPyz4eWj4ACMp6ZWG+i1QIDAQAB",
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwU2j3efNHdEE10lyuJmsDnjkOjxKzzoTFtBa5M2jAIin7h5rlqdStJDvLXJ6PiSa/LY0rCT1d+AmZIycsCh9odrqjObJHJa8/sEEUrM21KP64bF22JDBYbRmUjaiJlOqq3ReB30Zgtsq2B+g2Q0cLUlm91slc0boC4pPaQy1AJDh2oIQZn2uVCuLZXmRoeJhw81ASQjuaAzxi4bSRr/QuKoRAx5/VqgaHkQYDw+Fi9qLRF7iGMZiL8dmjfpd2H3zJ4kpAcWQDj8n8TDISg7v1t7HxydrxwU9esQCPJodPg/oNJhby3NLUpbYEaIsgIhpOVrTD7DeWS8Rx/fqEgEwlwIDAQAB",
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyHTEzLn5tXnpRdkUYLB9u5Pyax6fM60Nj4o8VmXl3ETZzGaFB9X4J7BKNdBjngpuG7fa8H6r7gwQk4ZJGDTzqCrSV/Uu1C93KYRhTYJQj6eVSHD1bk2y1RPD0hrt5kPqQhTrdOrA7R/UV06p86jt0uDBMHEwMjDV0/YI0FZPRo7yX/k9Z5GIMC5Cst99++UMd//sMcB4j7/Cf8qtbCHWjdmLao5v4Jv4EFbMs44TFeY0BGbH7vk2DmqV9gmaBmf0ZXH4yqSxJeD+PIs1BGe64E92hfx//DZrtenNLQNiTrM9AM+vdqBpVoNq0qjU51Bx5rU2BXcFbXvI5MT9TNUhXwIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCf5viGpYn1duRt9wzwca1SEuL+wwnBfBfza0nTuLPYR5uZyheUoFI+cudN9eB4jlvXij4yAxH59ML8BhVUab/j+TmeDsCe+OLpswdHWEXtY1HacLpw/wpsKQHBQZYhAARZRx/4J5/fiz/pJcH5qVGYK0Yu8c9CNl9/eHDQkj9LoQIDAQAB",
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDJrFzaru9+kMq8eS6zhL8Ef7ThMSsvuM645mWPpt0jRPh55Uf8d2YC6Wy/d5sNM6YYTJsD4a+S93UOzNEiQC6Siuma+gMhQxFM7jUxOS+0Zt9aNMOyjk5WffRRo7A1VT0QfB73ZHWB+2Io2goWKVdOFEaAi2p39YwaJLcjRV/7MQIDAQAB"
    };

    // 真机 PKX110 cryptoeng TA 2002 输出（参数=本平板 deviceId/token），成员公钥加密，服务器可解
    private static final String REAL_PHONE_2002_SN_CT = "rx5YnUmhw3fVajtaiWfDHh3zgJkGMl/uSP7fsBFZ7LbS4RmwqAuhkLEYvJaTXS2iRVY6vT4tsl8aIHa+vquNAN9lBcMk7tBcIgtNZQwu+rkFcOOVphJzxrhsCP8IHPIpfcqbvRHrpfOwQATSlZT86v70Yry9ftLDCyPVVqxY/YI9ttu3AIi5dOQ9jzHMX7WssKGc9KEXp6R9Uv4Nok8MeHFiGQfJ56v/cEdzPHR0+IwwTVWsqYeNjT594PaN3bOXUBrVNOCazcFNPIpvMJ9XAZ+Yz3mJqZti/hNJLjNuX3Tp4+0N8IjyVFAY1oPzSD8iKedeZ/FUS5VckGmBRVI9Vh0iFckPXMeoBTcsNZtJuSrPOCqvqZVAFYYkxXMjHJh+z5OLDfdkjHJidouebwckF+labXCh1b+Kq/yB/JxFskyXOHyfNx12iigoMF+g41f5hwi9IODFi/Lm/uP3sJDT9F3HPc1uTfZrU/qo39pmydr7Lf3aaZd1vfKJ9g59pbezDjjUxCDWlpwogJMvHWoqJyCeeSp5uglqItOCukn0NFZ75Er1oL2QhLPdESZTNdRXkmJpMUaTrCAylzjbu8j6GlJfMKMH4HYuMLzWERSk/PlmP1En0EEAx+rKibVg75GJG/CMS2fotCJTa2Zkd4lWBhqJfKwHJRj2TTKQ/00iA++YF3HE2CJvASZKpW98zvGqONOdESdICeedw0kAwW3EtUIONfdeEIRHq5qGN3FG031tuGx9WDAUj9fxP5V7NTSli0zkxaEAGbf1ittLdrZbyWJTizJCjXWgDmqI3JtgeNBrtt5heYAh4vuvOL9rbiJQYSrVI0/R4kZTeNcQbehJrpnZZoODidgKMV07QsaEZY9imNoA6HTx9gVOLzvwjuSwOfJmKbgOk+0hYnGkfnz7X+C+++er//C5a2gB4ED6Y1wdHAQd6QCirV3B8D5Fmul4BlOX7biXK3wqXNdMMUcOyLDuRWoBLfmqU/wBisZjX92s1Ii4vE9oBW6zFDyrH6oW";

    private static volatile Map<String, String> store;
    private static volatile boolean storeDirty;
    private static final String DEFAULT_RSA_VERSION = "0"; // 服务器密钥表只有版本 0（真机实测），固定为 0

    private static volatile Object aidlService;
    private static volatile Method aidlInvoke;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!DeviceGate.isSupported() || !FIND_PHONE.equals(p.packageName)) {
            return;
        }
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder real = (IBinder) sm.getMethod("getService", String.class).invoke(null, AIDL_SERVICE);
            XposedBridge.log(TAG + ": real CryptoEng service present=" + (real != null) + ", emulator forced ON");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": service probe failed " + t);
        }
        // 移植机开机时查找开关未开启，原厂 AndroidBootCompleteDispatcher 不会建立
        // 位置定时上报任务。这里在 FindDaemonService 启动后按原厂 AutoReportLocationHelper
        // 补上：立即上报一次 + 10 分钟 AlarmManager 循环上报。
        installFindDaemonReportHook(p.classLoader);
        XC_MethodHook queryHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Uri)) {
                    return;
                }
                Uri uri = (Uri) param.args[0];
                if (OLD_AUTHORITY.equals(uri.getAuthority())) {
                    param.args[0] = uri.buildUpon().authority(NEW_AUTHORITY).build();
                    XposedBridge.log(TAG + ": rewritten authority -> " + NEW_AUTHORITY);
                }
            }
        };
        XC_MethodHook callHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object cmdObj = XposedHelpers.getObjectField(param.thisObject, "a");
                    if (!(cmdObj instanceof byte[])) {
                        return;
                    }
                    byte[] cmd = (byte[]) cmdObj;
                    byte[] resp = emulate(cmd);
                    if (resp != null) {
                        param.setResult(resp);
                        XposedBridge.log(TAG + ": emulated method=" + methodOf(cmd) + " len=" + cmd.length
                                + " req=" + hex(cmd) + " resp=" + hex(resp));
                    } else {
                        XposedBridge.log(TAG + ": unhandled method=" + methodOf(cmd) + " len=" + cmd.length
                                + " hex=" + hex(cmd));
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": emulation failed " + t);
                }
            }
        };
        int ok = 0;
        final ClassLoader cl = p.classLoader;
        try {
            XposedHelpers.findAndHookMethod("w8.e$a", cl, "call", callHook);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": w8.e$a.call hook skipped " + t);
        }
        ok += tryQueryHook(p.classLoader, queryHook, Uri.class, String[].class, Bundle.class, CancellationSignal.class) ? 1 : 0;
        ok += tryQueryHook(p.classLoader, queryHook, Uri.class, String[].class, Bundle.class, CancellationSignal.class, java.util.concurrent.Executor.class) ? 1 : 0;
        ok += tryQueryHook(p.classLoader, queryHook, Uri.class, String[].class, String.class, String[].class, String.class) ? 1 : 0;
        XposedBridge.log(TAG + ": installed hooks=" + ok);
    }

    /** 查找守护服务启动后按原厂逻辑补建位置上报（立即 + 10 分钟定时循环）。 */
    private static void installFindDaemonReportHook(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.find.service.FindDaemonService", cl, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.thisObject;
                                Class<?> nk = XposedHelpers.findClass("nk.a", cl);
                                // nk.a.c(Context)：设置 AUTO_REPORT_LOCATION_IN_LOST 定时上报
                                XposedHelpers.callStaticMethod(nk, "c", ctx);
                                // nk.a.d(int, Context)：立即主动上报一次
                                XposedHelpers.callStaticMethod(nk, "d", 5, ctx);
                                XposedBridge.log(TAG + ": FindDaemonService auto-report location armed");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": FindDaemonService report hook failed " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FindDaemonService hook skipped " + t);
        }
    }

    /** 旁路+记录模式：不动真实 cryptoeng/HTTP，只抓明文请求响应（用于真机抓注册数据流）。 */
    private static void installPassthroughHooks(final ClassLoader cl) {
        XC_MethodHook ceHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object cmdObj = XposedHelpers.getObjectField(param.thisObject, "a");
                    if (cmdObj instanceof byte[]) {
                        byte[] cmd = (byte[]) cmdObj;
                        XposedBridge.log(TAG + "PD: req method=" + methodOf(cmd) + " len=" + cmd.length
                                + " hex=" + hex(cmd));
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "PD: req log failed " + t);
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
                    XposedBridge.log(TAG + "PD: resp len=" + resp.length + " hex=" + hex(resp));
                    int m = methodOf(resp);
                    if (m == 2016) {
                        String ver = paramStr(resp, 29);
                        XposedBridge.log(TAG + "PD: 2016 rsa_version=" + ver);
                    } else if (m == 2011) {
                        String text = paramStr(resp, 21);
                        XposedBridge.log(TAG + "PD: 2011 decrypted config=" + text);
                    } else if (m == 2014) {
                        int t = 0;
                        for (int[] pp : parseParams(resp)) {
                            t = pp[0];
                            break;
                        }
                        if (t == 29) {
                            XposedBridge.log(TAG + "PD: 2014 field29=" + paramStr(resp, 29));
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "PD: resp log failed " + t);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod("w8.e$a", cl, "call", ceHook);
            XposedBridge.log(TAG + "PD: cryptoeng passthrough hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "PD: cryptoeng hook failed " + t);
        }
        try {
            Class<?> chain = Class.forName("okhttp3.internal.http.RealInterceptorChain", false, cl);
            Class<?> req = Class.forName("okhttp3.Request", false, cl);
            XC_MethodHook httpHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object r = param.args[0];
                        Object url = XposedHelpers.callMethod(r, "url");
                        Object headers = XposedHelpers.callMethod(r, "headers");
                        XposedBridge.log(TAG + "PD: HTTP >> " + XposedHelpers.callMethod(r, "method")
                                + " " + XposedHelpers.callMethod(url, "toString")
                                + " headers=" + XposedHelpers.callMethod(headers, "toString"));
                        Object body = XposedHelpers.callMethod(r, "body");
                        if (body != null) {
                            XposedBridge.log(TAG + "PD: HTTP body contentLength="
                                    + XposedHelpers.callMethod(body, "contentLength"));
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "PD: HTTP req log failed " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object resp = param.getResult();
                        if (resp == null) {
                            return;
                        }
                        XposedBridge.log(TAG + "PD: HTTP << code=" + XposedHelpers.callMethod(resp, "code")
                                + " headers=" + XposedHelpers.callMethod(XposedHelpers.callMethod(resp, "headers"), "toString"));
                        try {
                            Object peek = XposedHelpers.callMethod(resp, "peekBody", 1_000_000L);
                            String b = (String) XposedHelpers.callMethod(peek, "string");
                            XposedBridge.log(TAG + "PD: HTTP body=" + (b == null ? "null" : b.substring(0, Math.min(b.length(), 1200))));
                        } catch (Throwable t2) {
                            XposedBridge.log(TAG + "PD: HTTP body read failed " + t2);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "PD: HTTP resp log failed " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod("okhttp3.internal.http.RealInterceptorChain", cl, "proceed", req, httpHook);
            XposedBridge.log(TAG + "PD: okhttp passthrough hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "PD: okhttp hook failed " + t);
        }
    }

    private static boolean tryQueryHook(ClassLoader cl, XC_MethodHook hook, Class<?>... params) {
        try {
            Object[] args = new Object[params.length + 1];
            System.arraycopy(params, 0, args, 0, params.length);
            args[params.length] = hook;
            XposedHelpers.findAndHookMethod(ContentResolver.class.getName(), cl, "query", args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================ storage ============================

    private static synchronized Map<String, String> store() {
        if (store == null) {
            store = new LinkedHashMap<>();
            try {
                Context ctx = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                ctx.getFilesDir().mkdirs();
                File f = new File(ctx.getFilesDir(), "rpmb_emulator_store.txt");
                if (f.exists()) {
                    List<String> lines = java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                    for (String line : lines) {
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            store.put(line.substring(0, eq), line.substring(eq + 1));
                        }
                    }
                    XposedBridge.log(TAG + ": store loaded entries=" + store.size() + " rsa_version=" + store.get("rsa_version"));
                } else {
                    store.put("rsa_version", DEFAULT_RSA_VERSION);
                    storeDirty = true;
                    XposedBridge.log(TAG + ": store initialized defaults rsa_version=" + DEFAULT_RSA_VERSION);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": store load failed " + t);
            }
        }
        if (storeDirty) {
            storeDirty = false;
            try {
                Context ctx = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                File f = new File(ctx.getFilesDir(), "rpmb_emulator_store.txt");
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : store.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                fos.close();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": store save failed " + t);
            }
        }
        return store;
    }

    private static String get(String k) {
        String v = store().get(k);
        return v == null ? "" : v;
    }

    private static void set(String k, String v) {
        store().put(k, v == null ? "" : v);
        storeDirty = true;
    }

    // ============================ protocol ============================

    private static int intAt(byte[] cmd, int off) {
        return ((cmd[off] & 0xff) << 24) | ((cmd[off + 1] & 0xff) << 16)
                | ((cmd[off + 2] & 0xff) << 8) | (cmd[off + 3] & 0xff);
    }

    private static void putInt(byte[] out, int off, int v) {
        out[off] = (byte) (v >> 24);
        out[off + 1] = (byte) (v >> 16);
        out[off + 2] = (byte) (v >> 8);
        out[off + 3] = (byte) v;
    }

    private static int methodOf(byte[] cmd) {
        if (cmd == null || cmd.length < 4) {
            return 0;
        }
        return intAt(cmd, 0);
    }

    private static String strOf(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** 解析 MethodBuffer：{method,10000,paramCount,paramType,len,data...} */
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
                return strOf(java.util.Arrays.copyOfRange(cmd, p[1], p[1] + p[2]));
            }
        }
        return "";
    }

    private static Object[] param(int ptype, byte[] data) {
        return new Object[]{ptype, data == null ? new byte[0] : data};
    }

    private static byte[] response(int method, Object[]... params) {
        int size = 12;
        for (Object[] p : params) {
            size += 8 + ((byte[]) p[1]).length;
        }
        byte[] out = new byte[size];
        putInt(out, 0, method);
        putInt(out, 4, 0);
        putInt(out, 8, params.length);
        int off = 12;
        for (Object[] p : params) {
            byte[] data = (byte[]) p[1];
            putInt(out, off, (Integer) p[0]);
            putInt(out, off + 4, data.length);
            System.arraycopy(data, 0, out, off + 8, data.length);
            off += 8 + data.length;
        }
        return out;
    }

    // ============================ crypto ============================

    private static byte[] rsaEncrypt(byte[] data, String pubKeyB64) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        java.security.PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pubKeyB64, Base64.DEFAULT)));
        int keyBytes = ((java.security.interfaces.RSAPublicKey) pub).getModulus().bitLength() / 8;
        // 真机 2010 密文 128B 单块、2002 密文 768B = 6×128B 多块：
        // OAEP 单块明文上限 62B，6 块只装得下 372B，而 2002 JSON（含 token）实测 500+ 字节，
        // 因此真机 TA 走的是 PKCS1 v1.5 多块（每块上限 117B）。默认用 PKCS1；
        // persist.rsa_pad=oaep 可切换回 OAEP/SHA-1 做对照。
        String pad = sysprop("persist.rsa_pad");
        Cipher cipher;
        int chunk;
        if ("oaep".equals(pad)) {
            cipher = Cipher.getInstance("RSA/ECB/OAEPPadding"); // SHA-1 + MGF1-SHA1
            chunk = keyBytes - 42;
        } else {
            cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            chunk = keyBytes - 11;
        }
        cipher.init(Cipher.ENCRYPT_MODE, pub);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int len = data.length;
        int off = 0;
        while (len - off > 0) {
            chunk = Math.min(len - off, chunk);
            bos.write(cipher.doFinal(data, off, chunk));
            off += chunk;
        }
        return bos.toByteArray();
    }

    private static byte[] aesDecrypt(byte[] ciphertext, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] aesEncrypt(byte[] plaintext, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(plaintext);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    // ============================ emulation ============================

    private static byte[] emulate(byte[] cmd) {
        int method = methodOf(cmd);
        List<int[]> params = parseParams(cmd);
        StringBuilder dbg = new StringBuilder();
        for (int[] p : params) {
            dbg.append('[').append(p[0]).append(':').append(p[2]).append(']');
        }
        XposedBridge.log(TAG + ": cmd method=" + method + " paramCount=" + params.size() + " types=" + dbg);
        try {
            switch (method) {
                case 2001: // ENCODE_BY_PUBLIC_KEY
                case 2004: { // ENCRYPT_BY_PUBLIC_KEY
                    String text = paramStr(cmd, 8) + paramStr(cmd, 21);
                    byte[] ct = rsaRaw(text.getBytes(StandardCharsets.UTF_8));
                    set("last_cipher", Base64.encodeToString(ct, Base64.NO_WRAP));
                    return response(method, param(20, ct));
                }
                case 2002: { // GET_ALL_REGISTER_INFO
                    String accountName = paramStr(cmd, 11);
                    String deviceId = paramStr(cmd, 4);
                    String token = paramStr(cmd, 12);
                    String mobileName = paramStr(cmd, 14);
                    String ticket = paramStr(cmd, 15);
                    String protocolVersion = paramStr(cmd, 1);
                    String phoneCard = paramStr(cmd, 13);
                    byte[] msgKey = randomBytes(16);
                    set("message_key", Base64.encodeToString(msgKey, Base64.NO_WRAP));
                    String json = "{\"accountName\":\"" + esc(accountName) + "\",\"deviceId\":\"" + esc(deviceId)
                            + "\",\"token\":\"" + esc(token) + "\",\"mobileName\":\"" + esc(mobileName)
                            + "\",\"ticket\":\"" + esc(ticket) + "\",\"protocolVersion\":\"" + esc(protocolVersion)
                            + "\",\"phoneCard\":\"" + esc(phoneCard) + "\",\"messageKey\":\""
                            + Base64.encodeToString(msgKey, Base64.NO_WRAP) + "\"}";
                    String ovK = initKeyB64();
                    int kIdx = keyIndexFromProp();
                    byte[] ct = rsaEncrypt(json.getBytes(StandardCharsets.UTF_8),
                            ovK != null ? ovK : EMBEDDED_PUBLIC_KEYS[kIdx]);
                    if (ovK != null) XposedBridge.log(TAG + ": 2011 using override init key");
                    set("register_json", json);
                    set("last_cipher", Base64.encodeToString(ct, Base64.NO_WRAP));
                    XposedBridge.log(TAG + ": register instruction local key idx=" + kIdx + " ct len=" + ct.length);
                    return response(method, param(20, ct));
                }
                case 2003: { // DECRYPT_REGISTER_RET_AND_SAVE_IMPORT_IOFO
                    return decryptResponse(method, cmd);
                }
                case 2005: { // DECRYPT_BY_AES_KEY
                    return decryptResponse(method, cmd);
                }
                case 2006: { // UNREGISTER_AND_CLEAR_DATA
                    store().clear();
                    storeDirty = true;
                    return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)));
                }
                case 2007: { // DECODE_AND_CHECK_SIGN_INCTRUCTIO
                    String body = paramStr(cmd, 19) + paramStr(cmd, 22);
                    set("last_instruction", body);
                    return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)), param(21, body.getBytes(StandardCharsets.UTF_8)));
                }
                case 2008: // CLEAR_LOCK_DEAD_BY_KEYGUARD_TOKEN
                case 2009: { // CLEAR_LOCK_DEAD_BY_BOOT_REG
                    return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)));
                }
                case 2010: { // GET_ENCRYPT_TMP_AES_FOR_UPDATE_PUBLIC_KEY
                    byte[] tmpAes = randomBytes(16);
                    set("tmp_aes", Base64.encodeToString(tmpAes, Base64.NO_WRAP));
                    // TA 2010 handler 结构：{"imei":...,"tmpAes":...,"uniqueId":...}，RSA-1024
                    int keyIdx = keyIndexFromProp(); // persist.key_idx 选择候选，便于逐个测试
                    String imei = get("imei");
                    if (imei.isEmpty()) {
                        imei = "";
                    }
                    String payload = "{\"imei\":\"" + imei + "\",\"tmpAes\":\""
                            + Base64.encodeToString(tmpAes, Base64.NO_WRAP) + "\",\"uniqueId\":\""
                            + get("unique_id") + "\"}";
                    byte[] ct = rsaRaw(payload.getBytes(StandardCharsets.UTF_8));
                    set("last_tmp_aes_payload", payload);
                    XposedBridge.log(TAG + ": 2010 using key idx=" + keyIndexFromProp() + " tmpAesPayload=" + payload + " cipherLen=" + ct.length);
                    return response(method, param(20, ct));
                }
                case 2011: { // UPDATE_PUBLIC_KEY：解密服务器配置，提取新公钥并存储
                    String ct = paramStr(cmd, 20);
                    set("last_public_key_update", ct);
                    XposedBridge.log(TAG + ": UPDATE_PUBLIC_KEY server body=" + ct);
                    String decrypted = decryptConfig(ct);
                    XposedBridge.log(TAG + ": UPDATE_PUBLIC_KEY decrypted=" + decrypted);
                    if (decrypted != null) {
                        storeConfigKey(decrypted);
                        return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)),
                                param(21, decrypted.getBytes(StandardCharsets.UTF_8)));
                    }
                    return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)),
                            param(21, ct.getBytes(StandardCharsets.UTF_8)));
                }
                case 2012: { // ENCODE_BY_AES
                    String text = paramStr(cmd, 8) + paramStr(cmd, 21);
                    byte[] key = currentAesKey();
                    byte[] out = aesEncrypt(text.getBytes(StandardCharsets.UTF_8), key);
                    return response(method, param(20, Base64.encodeToString(out, Base64.NO_WRAP).getBytes(StandardCharsets.UTF_8)));
                }
                case 2013: { // MOVE_DATA_TO_RPMB
                    for (int[] p : params) {
                        set("param_" + p[0], strOf(java.util.Arrays.copyOfRange(cmd, p[1], p[1] + p[2])));
                    }
                    return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)));
                }
                case 2014: { // GET_RPMB_VALUE
                    // 请求参数的类型即字段选择器（SSOID=5/DEVICE_ID=4/UNIQUE_ID=6/
                    // ACCOUNT_NAME=11/UPDATE_DATA=28/LOCK_DEAD_STATE=26...），
                    // 响应必须回同一类型且值非空（解析器拒绝 len=0）。
                    int fieldType = 28;
                    for (int[] p : params) {
                        fieldType = p[0];
                        break;
                    }
                    String val;
                    switch (fieldType) {
                        case 5: // SSOID
                            val = get("ssoid");
                            break;
                        case 4: // DEVICE_ID
                            val = get("device_id");
                            if (val.isEmpty()) {
                                val = "0db683cee188696671337eff1d4ee7922fa28b26923455503758dafe3ca19c58"; // 本平板 SHA256(SN+AndroidID) 派生 deviceId
                            }
                            break;
                        case 6: // UNIQUE_ID
                            val = get("unique_id");
                            break;
                        case 11: // ACCOUNT_NAME
                            val = get("account_name");
                            break;
                        case 28: // UPDATE_DATA
                            val = get("update_data");
                            if (val.isEmpty()) {
                                val = "1";
                            }
                            break;
                        case 26: // LOCK_DEAD_STATE
                            val = get("lock_dead_state");
                            break;
                        case 33: // LOCK_SCREEN_PWD_TYPE
                            val = get("pwd_type");
                            break;
                        case 37: // SALT
                            val = get("salt");
                            break;
                        case 29: // RSA_KEY_VERSION（getConfig 的 version 字段来源）
                            val = get("rsa_version");
                            if (val.isEmpty()) {
                                val = sysprop("persist.rsa_version");
                            }
                            if (val.isEmpty()) {
                                val = DEFAULT_RSA_VERSION;
                            }
                            break;
                        default:
                            val = get("rpmb_" + fieldType);
                            break;
                    }
                    if (val.isEmpty()) {
                        if (fieldType == 4 || fieldType == 6) {
                            val = "860000000000000";
                        } else {
                            val = "0";
                        }
                    }
                    XposedBridge.log(TAG + ": GET_RPMB_VALUE field=" + fieldType + " val=" + val);
                    // 应用侧 u8.b.h() 按 4 字节大端整数解析 UPDATE_DATA/LOCK_DEAD_STATE/
                    // LOCK_SCREEN_PWD_TYPE（int 字段），不能回传 ASCII 字符串，否则
                    // hasMovedDataToRpmb 把 '1'(0x31) 解析成 49 判定未迁移，RPMB 全部 System error。
                    boolean intField = fieldType == 28 || fieldType == 26 || fieldType == 33
                            || fieldType == 32 || fieldType == 34 || fieldType == 36;
                    byte[] valBytes;
                    if (intField) {
                        int iv = 0;
                        try {
                            iv = Integer.parseInt(val.trim());
                        } catch (Throwable t) {
                            iv = 0;
                        }
                        valBytes = new byte[]{(byte) (iv >>> 24), (byte) (iv >>> 16), (byte) (iv >>> 8), (byte) iv};
                    } else {
                        valBytes = val.getBytes(StandardCharsets.UTF_8);
                    }
                    return response(method, param(fieldType, valBytes));
                }
                case 2015: { // RESET_RPMB
                    store().clear();
                    storeDirty = true;
                    return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)));
                }
                case 2016: { // GET_ALL_RPMB_INFO
                    return response(method,
                            param(6, nz(get("unique_id"), "860000000000000").getBytes(StandardCharsets.UTF_8)),
                            param(4, nz(get("device_id"), "0db683cee188696671337eff1d4ee7922fa28b26923455503758dafe3ca19c58").getBytes(StandardCharsets.UTF_8)),
                            param(5, nz(get("ssoid"), "0").getBytes(StandardCharsets.UTF_8)),
                            param(11, nz(get("account_name"), "0").getBytes(StandardCharsets.UTF_8)),
                            param(29, nz(get("rsa_version"), "0").getBytes(StandardCharsets.UTF_8)));
                }
                case 2017: { // APPEND_TMP_AES_AND_ENCRYPT_BY_PUBLIC_KEY
                    byte[] tmp = Base64.decode(get("tmp_aes"), Base64.DEFAULT);
                    if (tmp == null || tmp.length == 0) {
                        tmp = randomBytes(16);
                    }
                    String text = paramStr(cmd, 8) + paramStr(cmd, 21);
                    byte[] payload = new byte[tmp.length + text.getBytes(StandardCharsets.UTF_8).length];
                    System.arraycopy(tmp, 0, payload, 0, tmp.length);
                    System.arraycopy(text.getBytes(StandardCharsets.UTF_8), 0, payload, tmp.length, text.getBytes(StandardCharsets.UTF_8).length);
                    byte[] ct = rsaRaw(payload);
                    return response(method, param(20, ct));
                }
                case 2018: { // IS_SUPPORT_UNLOCK_BY_LOCK_SCREEN_PWD
                    return response(method, param(32, "1".getBytes(StandardCharsets.UTF_8)));
                }
                case 2019: { // SET_LOCK_SCREEN_PWD_TYPE
                    return response(method, param(33, "0".getBytes(StandardCharsets.UTF_8)));
                }
                case 2020: { // GET_LOCK_SCREEN_PWD_TYPE
                    return response(method, param(33, get("pwd_type").getBytes(StandardCharsets.UTF_8)));
                }
                case 2021: { // VERIFY_LOCK_SCREEN_PWD
                    return response(method, param(34, "1".getBytes(StandardCharsets.UTF_8)));
                }
                case 2022: { // GET_SALT
                    String salt = get("salt");
                    if (salt.isEmpty()) {
                        salt = Base64.encodeToString(randomBytes(16), Base64.NO_WRAP);
                        set("salt", salt);
                    }
                    return response(method, param(37, salt.getBytes(StandardCharsets.UTF_8)));
                }
                default:
                    return null;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": emulate error method=" + method + " " + t);
            return null;
        }
    }

    private static byte[] currentAesKey() {
        String k = get("message_key");
        if (k.isEmpty()) {
            k = Base64.encodeToString(randomBytes(16), Base64.NO_WRAP);
            set("message_key", k);
        }
        return Base64.decode(k, Base64.DEFAULT);
    }

    private static byte[] decryptResponse(int method, byte[] cmd) {
        String ct = paramStr(cmd, 20);
        boolean useTmpAes = "1".equals(paramStr(cmd, 31));
        XposedBridge.log(TAG + ": decrypt method=" + method + " useTmpAes=" + useTmpAes + " cipher len=" + ct.length());
        try {
            byte[] key;
            if (useTmpAes) {
                String tmp = get("tmp_aes");
                key = tmp.isEmpty() ? currentAesKey() : Base64.decode(tmp, Base64.DEFAULT);
            } else {
                key = currentAesKey();
            }
            byte[] plain = aesDecrypt(Base64.decode(ct, Base64.DEFAULT), key);
            String text = strOf(plain);
            XposedBridge.log(TAG + ": decrypt result=" + text);
            set("decrypted_" + method, text);
            return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": decrypt failed " + t);
            return response(method, param(27, "200".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** 尝试用 2010 生成的临时 AES 解密服务器配置（ECB，然后 CBC 零 IV）。 */
    private static String decryptConfig(String b64) {
        String keyB64 = get("tmp_aes");
        if (keyB64.isEmpty()) {
            XposedBridge.log(TAG + ": decryptConfig no tmp_aes stored");
            return null;
        }
        byte[] key = Base64.decode(keyB64, Base64.DEFAULT);
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            try {
                return new String(aesDecrypt(raw, key), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cbc.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(new byte[16]));
                return new String(cbc.doFinal(raw), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": decryptConfig failed " + t);
            return null;
        }
    }

    /** 从服务器配置 JSON 里提取 publicKey/modulus/version 并存储为当前公钥。 */
    private static void storeConfigKey(String body) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            if (root.has("data")) {
                root = root.getJSONObject("data");
            }
            if (root.has("publicKey")) {
                org.json.JSONObject pk = root.getJSONObject("publicKey");
                String modulus = pk.optString("modulus", "");
                String data = pk.optString("data", "");
                String version = pk.optString("version", "");
                String pub = data != null && !data.isEmpty() ? data : modulus;
                set("current_public_key", pub);
                set("current_key_version", version);
                XposedBridge.log(TAG + ": stored current public key version=" + version + " len=" + pub.length());
            } else if (root.has("public_key")) {
                set("current_public_key", root.getString("public_key"));
                XposedBridge.log(TAG + ": stored current public key (public_key)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": storeConfigKey failed " + t);
        }
    }

    private static String rsaB64WithCurrentKey(byte[] data) throws Exception {
        String k = get("current_public_key");
        if (!k.isEmpty()) {
            try {
                String out = Base64.encodeToString(rsaEncrypt(data, k), Base64.NO_WRAP);
                XposedBridge.log(TAG + ": encrypted with current_public_key");
                return out;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": current_public_key encrypt failed " + t + ", falling back");
            }
        }
        return rsaB64(data);
    }

    private static String rsaB64(byte[] data) throws Exception {
        Throwable last = null;
        for (int i = 0; i < EMBEDDED_PUBLIC_KEYS.length; i++) {
            String key = EMBEDDED_PUBLIC_KEYS[i];
            try {
                byte[] out = rsaEncrypt(data, key);
                XposedBridge.log(TAG + ": rsaB64 key idx=" + i + " outLen=" + out.length);
                return Base64.encodeToString(out, Base64.NO_WRAP);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new RuntimeException("all embedded RSA keys failed", last);
    }

    /** 用当前选定公钥做 PKCS1 RSA 加密，返回原始密文字节（与真机 TA 的 field20 原始输出一致）。
     *  应用侧拿到 field20 后会自行 Base64 编码，桥接不得预编码，否则服务器收到双重编码密文解不开。 */
    // 覆盖公钥来源（拿到 findphone-init RSA-1024 公钥后，无需改源码重编）：
    //   优先级 1：属性 persist.findphone.initkey = 单行 base64 SPKI（去掉 PEM 头尾）
    //   优先级 2：文件 /data/local/tmp/findphone_init_key.pem（标准 PEM）或 .der（二进制 SPKI）
    // 命中任一即作为唯一 RSA 公钥使用，忽略内置候选与 persist.key_idx。
    private static volatile String overrideKey = "";
    private static volatile boolean overrideProbed;
    private static String initKeyB64() {
        if (overrideProbed) {
            return overrideKey.isEmpty() ? null : overrideKey;
        }
        overrideProbed = true;
        try {
            String prop = sysprop("persist.findphone.initkey").trim();
            if (!prop.isEmpty()) {
                overrideKey = prop.replaceAll("\\s", "");
                XposedBridge.log(TAG + ": init key loaded from prop, len=" + overrideKey.length());
                return overrideKey;
            }
            java.io.File pem = new java.io.File("/data/local/tmp/findphone_init_key.pem");
            if (pem.exists()) {
                String t = new String(java.nio.file.Files.readAllBytes(pem.toPath()), StandardCharsets.UTF_8);
                overrideKey = t.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
                XposedBridge.log(TAG + ": init key loaded from pem, len=" + overrideKey.length());
                return overrideKey;
            }
            java.io.File der = new java.io.File("/data/local/tmp/findphone_init_key.der");
            if (der.exists()) {
                byte[] b = java.nio.file.Files.readAllBytes(der.toPath());
                overrideKey = Base64.encodeToString(b, Base64.NO_WRAP);
                XposedBridge.log(TAG + ": init key loaded from der, len=" + overrideKey.length());
                return overrideKey;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init key load failed " + t);
        }
        return null;
    }

    private static byte[] rsaRaw(byte[] data) throws Exception {
        Throwable last = null;
        String ov = initKeyB64();
        if (ov != null) {
            try {
                byte[] out = rsaEncrypt(data, ov);
                XposedBridge.log(TAG + ": rsaRaw override(init) outLen=" + out.length);
                return out;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": rsaRaw override key failed, falling back " + t);
                last = t;
            }
        }
        int idx = keyIndexFromProp();
        try {
            byte[] out = rsaEncrypt(data, EMBEDDED_PUBLIC_KEYS[idx]);
            XposedBridge.log(TAG + ": rsaRaw key idx=" + idx + " outLen=" + out.length);
            return out;
        } catch (Throwable t) {
            last = t;
        }
        // 候选密钥兜底：选定密钥异常时逐个尝试其余公钥
        for (int i = 0; i < EMBEDDED_PUBLIC_KEYS.length; i++) {
            if (i == idx) {
                continue;
            }
            try {
                byte[] out = rsaEncrypt(data, EMBEDDED_PUBLIC_KEYS[i]);
                XposedBridge.log(TAG + ": rsaRaw fallback key idx=" + i + " outLen=" + out.length);
                return out;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new RuntimeException("all embedded RSA keys failed", last);
    }

    private static String nz(String v, String fallback) {
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static String sysprop(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    private static int keyIndexFromProp() {
        String v = sysprop("persist.key_idx");
        try {
            int i = Integer.parseInt(v.trim());
            if (i < 0) {
                return 4; // 默认：真机 findmyphone 当前内置公钥（EMBEDDED_PUBLIC_KEYS[4]）
            }
            return i % EMBEDDED_PUBLIC_KEYS.length;
        } catch (Throwable t) {
            return 4; // 默认同上
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String configJson(ClassLoader cl) throws Exception {
        Object cfg = XposedHelpers.callStaticMethod(XposedHelpers.findClass("s4.a", cl), "a");
        StringBuilder sb = new StringBuilder("{\"version\":").append(XposedHelpers.callMethod(cfg, "getVersion"))
                .append(",\"updateCommonConfigImmediately\":").append(XposedHelpers.callMethod(cfg, "getUpdateCommonConfigImmediately"))
                .append(",\"updateCommonConfigIntervalTime\":").append(XposedHelpers.callMethod(cfg, "getUpdateCommonConfigIntervalTime"));
        Object pk = XposedHelpers.callMethod(cfg, "getPublicKey");
        if (pk != null) {
            sb.append(",\"publicKey\":{\"data\":\"").append(XposedHelpers.callMethod(pk, "getData"))
                    .append("\",\"modulus\":\"").append(XposedHelpers.callMethod(pk, "getModulus"))
                    .append("\",\"version\":\"").append(XposedHelpers.callMethod(pk, "getVersion")).append("\"}");
        }
        Object sms = XposedHelpers.callMethod(cfg, "getSms");
        if (sms != null) {
            sb.append(",\"sms\":{\"maxSendCount\":").append(XposedHelpers.callMethod(sms, "getMaxSendCount"))
                    .append(",\"receiveNumbers\":").append(arrJson((Object[]) XposedHelpers.callMethod(sms, "getReceiveNumbers")))
                    .append(",\"sendNumbers\":").append(arrJson((Object[]) XposedHelpers.callMethod(sms, "getSendNumbers")))
                    .append(",\"signs\":").append(arrJson((Object[]) XposedHelpers.callMethod(sms, "getSigns"))).append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String arrJson(Object[] arr) {
        if (arr == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('\"').append(String.valueOf(arr[i])).append('\"');
        }
        return sb.append("]").toString();
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
