package com.aclaniakea.aoncameraopbridge;

import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 让 AON(注视感知) 的摄像头 appop 判定通过，止住它每 2 秒开关一次前置摄像头的空转。
 *
 * ---- 现象 ----
 * com.aiunit.aon 每 2~3 秒一轮 CONNECT/DISCONNECT device 1，日志里成对出现：
 *   AttributionAndPermissionUtils: Permission soft denied for client attribution
 *     [uid 10237, pid ..., packageName "com.aiunit.aon"]
 *   AppOps: Operation not started: uid=10237 pkg=com.aiunit.aon(null) op=CAMERA
 * 实测 com.aiunit.aon + camera provider HAL + cameraserver 合计稳定 31~41% 一个核。
 *
 * ---- 根因 ----
 * AON 的 CAMERA appop **uid 模式**是 foreground，而 AONService 是被
 * com.heytap.accessory 绑起来的后台服务，进程状态够不到 FOREGROUND_SERVICE，
 * 于是 startOp 被拒 → 打开的会话立刻拆掉 → 隔两秒再来一轮。
 * 移植包里 /my_product/etc/permissions/oplus_aon_grant_permissions_list.xml
 * 只 default-grant 了运行时权限，没有把 appop 抬成 allow；
 * vendor 侧倒是有 ro.camera.privileged.3rdpartyApp=com.aiunit.aon;，
 * 但那条只管相机服务的特权判定，管不到 framework 的 AppOps。
 *
 * ---- 为什么不用 cmd appops ----
 * 实测 `cmd appops set` 的三种写法（包名 / --uid / --user 0 --uid）读回来都还是
 * foreground，ColorOS 侧会把 uid 模式按住，改不动也不持久。
 *
 * ---- 本 hook 的做法 ----
 * 只在 system_server 里，只对 **AON 这一个 uid** 的 **OP_CAMERA(26)**，
 * 把 `checkOperation` 的返回值改成 MODE_ALLOWED(0)。
 *
 * ★ 只挂返回类型确实是 int 的重载。第一版按方法名把 noteOperation/startOperation
 * 也挂上了，而 startOperation 在本版框架里返回的是对象，被 setResult(Integer) 之后
 * system_server 当场崩、整机卡死。教训见 memory: hook-return-type-first。
 * 其它 uid、其它 op 一律不碰——这不是放开后台相机，是把这一个原厂就该有的
 * 白名单补回去。
 *
 * 撤销：卸载/停用本 Hook 即可，没有落盘副作用。
 */
public final class AonCameraOpBridge implements IXposedHookLoadPackage {

    private static final String AON_PACKAGE = "com.aiunit.aon";
    /** AppOpsManager.OP_CAMERA。framework 里是 @hide 常量，这里按值写死并在运行时校验。 */
    private static final int OP_CAMERA = 26;
    private static final int MODE_ALLOWED = 0;

    private static final AtomicBoolean HOOKED = new AtomicBoolean(false);
    private static final AtomicInteger AON_UID = new AtomicInteger(-1);
    private static final AtomicInteger GRANTS = new AtomicInteger(0);

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!DeviceGate.isSupported()) return;
        if (!"android".equals(p.packageName) || !"android".equals(p.processName)) return;
        hookAppOps(p.classLoader);
    }

    private static void hookAppOps(ClassLoader loader) {
        if (!HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> svc = XposedHelpers.findClass("com.android.server.appop.AppOpsService", loader);
            XC_MethodHook allow = new XC_MethodHook() {
                @Override protected void afterHookedMethod(XC_MethodHook.MethodHookParam hook) {
                    try {
                        Object result = hook.getResult();
                        // ★ 只碰返回 int 的重载。startOperation 这类在本版框架里返回的是
                        // SyncNotedAppOp 之类的对象，往它上面 setResult(Integer) 会让
                        // 框架当场 ClassCastException —— 2026-08-30 就是这样把
                        // system_server 打崩、整机卡死的（见 memory: hook-return-type-first）。
                        if (!(result instanceof Integer)) return;
                        if (((Integer) result).intValue() == MODE_ALLOWED) return;
                        Object[] args = hook.args;
                        if (args == null || args.length < 3) return;
                        if (!(args[0] instanceof Integer) || ((Integer) args[0]).intValue() != OP_CAMERA) return;
                        if (!(args[1] instanceof Integer)) return;
                        int uid = ((Integer) args[1]).intValue();
                        if (!isAon(uid, args)) return;
                        hook.setResult(Integer.valueOf(MODE_ALLOWED));
                        int n = GRANTS.incrementAndGet();
                        if (n <= 3 || n % 500 == 0) {
                            XposedBridge.log("AonCameraOpBridge: forced CAMERA appop ALLOWED for " + AON_PACKAGE
                                    + " uid=" + uid + " (grant #" + n + ", was " + result + ")");
                        }
                    } catch (Throwable t) {
                        // 热路径上出任何问题都必须放行原返回值，绝不改结果。
                        XposedBridge.log(t);
                    }
                }
            };
            int hooked = 0;
            for (Method m : svc.getDeclaredMethods()) {
                // 只认 checkOperation：它是判定入口，返回 int，且不在 startOp/noteOp 那种
                // 每秒上千次的最热路径上。名字匹配之外**必须再按返回类型过滤**。
                if (!"checkOperation".equals(m.getName())) continue;
                if (m.getReturnType() != Integer.TYPE) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 3 || ps[0] != Integer.TYPE || ps[1] != Integer.TYPE) continue;
                XposedBridge.hookMethod(m, allow);
                hooked++;
            }
            XposedBridge.log("AonCameraOpBridge: installed on " + hooked + " int-returning checkOperation overloads (op=" + OP_CAMERA + ")");
        } catch (Throwable t) { HOOKED.set(false); XposedBridge.log(t); }
    }

    /**
     * 只认 AON。优先按包名比对（第三个参数通常是 packageName），
     * 拿不到包名时退回到已缓存的 uid，避免误伤别的应用。
     */
    private static boolean isAon(int uid, Object[] args) {
        for (Object a : args) {
            if (a instanceof String && AON_PACKAGE.equals(a)) { AON_UID.set(uid); return true; }
        }
        int known = AON_UID.get();
        return known != -1 && known == uid;
    }
}
