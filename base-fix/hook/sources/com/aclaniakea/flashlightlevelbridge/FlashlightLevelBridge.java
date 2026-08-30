package com.aclaniakea.flashlightlevelbridge;

import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把手电亮度档位接到硬件上。
 *
 * ---- 为什么需要 ----
 * 相机 metadata 里两组能力位互相矛盾：
 *   android.flash.info.strengthMaximumLevel = 4   (AOSP 标准键，说支持 4 档)
 *   android.flash.torchStrengthMaxLevel     = 1   (高通厂商键，说只有 1 档)
 * 上层照 AOSP 那个把滑条画出来了，真正执行的 HAL 路径照厂商那个办事，
 * 于是 SystemUI 里 mFlashlightLevel 会变，两颗灯的电流却纹丝不动 —— 拖了没反应。
 *
 * ---- 硬件其实支持 ----
 * /sys/class/leds/led:torch_0 与 led:torch_3 各有 100 级电流，实测：
 *   写 10 → 读回 13（量化到硬件电流台阶）  写 30/57/80 → 原值  写 120 → 钳到 100
 * 并且肉眼确认亮度真的跟着变。所以这条走"修"不走"禁"。
 *
 * ★ 一个只有实测才知道的约束：灯**亮着**的时候驱动把持续电流上限压到 78
 *   （关着写 100 读回 100，亮着写 100 只能读回 78）——闪光灯的常见保护，
 *   瞬时闪光能到满档，长亮不行。所以映射顶格取 78，硬写 100 会被驱动自己削掉，
 *   最高档和次高档反而看不出区别。
 *
 * ---- 为什么不让 SystemUI 直接写 sysfs ----
 * 试过了，走不通。把两个 brightness 节点 chmod 0666 + chcon 成
 * vendor_sysfs_graphics，再用 ksud 把 platform_app 对该类型的
 * file{open read write}、sysfs/sysfs_leds 的 dir{search}、lnk_file{read}
 * 全部放行（ksud sepolicy apply 免重启生效，逐条 check 都返回 valid），
 * open() 依旧稳定 EACCES，而 dmesg 里**一条 avc 都不落** —— AOSP 对
 * appdomain 访问 sysfs 的这类拒绝有 dontaudit，日志上看永远是"策略没问题"。
 * 排查这条路花的时间远超收益，所以改成不跟 SELinux 较劲的做法。
 *
 * ---- 现在的做法：信箱 + root 侧落笔 ----
 * Hook 只把"该写多少电流"写进 SystemUI 自己的 DE 数据目录（应用写自己的
 * 文件永远有权限，不涉及任何 sysfs 标签）：
 *   /data/user_de/0/com.android.systemui/files/aclaniakea_torch_level
 * fix 模块在 service.sh 里挂一个 inotifyd 盯着这个文件，内容一变就由 root
 * 写进 led:torch_0 / led:torch_3。inotifyd 平时阻塞在 read 上，不轮询、
 * 几乎不耗电，只有拖滑条时才醒一下。
 *
 * 选 DE（device_encrypted）目录而不是 getFilesDir()：锁屏下也能开手电，
 * CE 目录要首次解锁后才挂上，DE 从开机起就在。
 */
public final class FlashlightLevelBridge implements IXposedHookLoadPackage {

    private static final String SYSTEMUI = "com.android.systemui";

    /** 信箱：Hook 只写这里，真正落到 sysfs 的是 root 侧的 inotifyd 处理脚本。 */
    private static final String MAILBOX =
            "/data/user_de/0/com.android.systemui/files/aclaniakea_torch_level";
    /**
     * 停用开关：这个文件存在时本 Hook 全程让路，什么都不做。
     * 放在 SystemUI 自己的目录里是因为 root 能创建、应用能读 —— 不需要重启
     * SystemUI 就能 A/B 对比"有我们"和"纯原厂 HAL"两种行为。
     */
    private static final String KILL_SWITCH =
            "/data/user_de/0/com.android.systemui/files/aclaniakea_torch_off";

    /** 灯亮着时驱动允许的持续电流上限（实测）。 */
    private static final int CURRENT_MAX = 78;
    /**
     * 最低档取 42 —— 这是"档位拉得开"和"开灯别闪"之间量出来的折中。
     *
     * 背景：点灯只能由 HAL 做，而 camx 点灯时一定先写 57
     * （/vendor/etc/camera/camxoverridesettings.txt 里的 torchCurrent=57，实测
     * 把本 Hook 整个停用后拖一整轮滑条，led:torch_0 从头到尾恒为 57，camx 根本
     * 不按档位算电流）。我们只能在它之后覆盖，所以最低档比 57 低多少，开灯时
     * 就会看到多大的一次回落。
     *
     * 试过两头：
     *   13..78  档位差别明显，但开灯时 57→13 是 4.4 倍的回落，低档位下非常刺眼。
     *           点灯前预置、20ms 密集复查、去掉全部 fork 都只能让它变短，消不掉。
     *   57..78  回落从原理上不存在（只往上加），但 1.37 倍的总跨度实测"一二档、
     *           三四档几乎一样"，四个档位形同虚设。
     * 现在 42..78 分成 42/54/66/78：总跨度回到 1.86 倍，四档能分得出来；最坏
     * 情况是开灯在一二档时有一次 57→42（1.36 倍）的轻微回落，远到不了"爆闪"，
     * 三四档则是往上走、什么都看不见。
     *
     * 想再拉开只有改 camx 的 torchCurrent 一条路，但那是全局值，相机 App 自己的
     * 手电和录像补光也吃它、且不走本 Hook，会跟着一起变暗 —— 已明确否掉。
     */
    private static final int CURRENT_MIN = 42;
    /** 档位总数，取自 android.flash.info.strengthMaximumLevel。 */
    private static final int LEVELS = 4;

    private static final AtomicBoolean HOOKED = new AtomicBoolean(false);
    private static final AtomicInteger APPLIES = new AtomicInteger(0);
    private static volatile int lastLevel = -1;
    /** 上一次投进信箱的电流值，用来去重。 */
    private static volatile int lastPosted = -1;
    private static final AtomicInteger WRITE_FAILS = new AtomicInteger(0);

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!DeviceGate.isSupported()) return;
        if (!SYSTEMUI.equals(p.packageName) || p.processName == null
                || !p.processName.startsWith(SYSTEMUI)) return;
        install(p.classLoader);
    }

    private static void install(ClassLoader loader) {
        if (!HOOKED.compareAndSet(false, true)) return;

        // ---- 主路径：直接挂 AOSP 的分级手电 API ----
        // 滑条最终一定会走到 CameraManager.turnOnTorchWithStrengthLevel(String,int)，
        // 这比猜 OPlus 内部方法名可靠得多——第一版只挂 FlashlightControllerImpl 里
        // 名字带 "level" 的方法，实测一次都没触发。
        try {
            Class<?> cm = XposedHelpers.findClass("android.hardware.camera2.CameraManager", loader);
            int n = 0;
            for (Method m : cm.getDeclaredMethods()) {
                if (!"turnOnTorchWithStrengthLevel".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2 || ps[1] != Integer.TYPE) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    /**
                     * 在**调用前**就把目标电流投出去，让 root 侧的纠正循环先跑起来，
                     * 然后放行让 HAL 照常执行。
                     *
                     * 上一版是"灯已经亮着就把这个调用整个吞掉"，想的是不让 HAL 写那
                     * 一下 57。代价太大：吞不吞取决于"手电现在开没开"，而这个状态只能
                     * 从 SystemUI 的几个入口去猜 —— 实测关灯那条路没走我们挂的任何一个
                     * 入口，状态卡在"开着"，于是**每一次点灯的调用都被吞掉，手电彻底
                     * 点不亮**。判断错的代价太不对称，这条路不值得再走。
                     *
                     * 不拦之后 HAL 每次都会先写它那个 57，我们随后覆盖。这在档位跨度
                     * 42..78 的前提下是可以接受的：57 就在区间内，任何一档与它的差距
                     * 最多 1.36 倍，是"轻微一沉/一提"而不是爆闪。当初非要拦，是因为
                     * 那时最低档取 13，57→13 有 4.4 倍。
                     */
                    @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                        try {
                            if (disabled()) return;
                            Object lv = hook.args[1];
                            if (!(lv instanceof Integer)) return;
                            lastPosted = -1;   // HAL 马上要重置电流，这一次必须真投
                            applyCurrent(((Integer) lv).intValue(), "turnOnTorchWithStrengthLevel", true);
                        } catch (Throwable t) { XposedBridge.log(t); }
                    }
                });
                n++;
            }
            // 开灯时补一次：HAL 点亮时可能把电流重置回默认。
            for (Method m : cm.getDeclaredMethods()) {
                if (!"setTorchMode".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2 || ps[1] != Boolean.TYPE) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    // 点灯前投递，理由同上：HAL 点亮时会写它自己的 torchCurrent，
                    // 得让 root 侧的纠正循环赶在它前面跑起来。
                    @Override protected void beforeHookedMethod(XC_MethodHook.MethodHookParam hook) {
                        try {
                            if (disabled()) return;
                            Object on = hook.args[1];
                            if (!(on instanceof Boolean) || !((Boolean) on).booleanValue()) return;
                            if (lastLevel < 0) return;
                            lastPosted = -1;
                            applyCurrent(lastLevel, "setTorchMode(on)", true);
                        } catch (Throwable t) { XposedBridge.log(t); }
                    }
                });
                n++;
            }
            XposedBridge.log("FlashlightLevelBridge: CameraManager hooks = " + n);
        } catch (Throwable t) {
            XposedBridge.log("FlashlightLevelBridge: CameraManager hook failed");
            XposedBridge.log(t);
        }

        // ---- 不再挂 FlashlightControllerImpl ----
        //
        // 这里先后挂过两回，两回都是坑，记下来免得再来第三次：
        //   1) 读它的 mFlashlightLevel 兜底写电流 —— 那个字段恒为 0，于是每次
        //      控制器方法被调用都把电流按 0 档压到最低，把 CameraManager 那条正确
        //      路径刚写好的值全盖掉，表现是"亮度调节完全失效，永远最暗"。
        //   2) 用它的 setFlashlight(boolean) 维护"手电开没开"，好决定要不要拦截
        //      turnOnTorchWithStrengthLevel —— 实测关灯那条路没走我们挂的任何一个
        //      入口，状态卡在"开着"，结果每一次点灯调用都被拦掉，手电彻底点不亮。
        //
        // 现在既不拦截也不需要开关状态，档位只认 turnOnTorchWithStrengthLevel 的
        // 入参（滑条真正走的路，值确实是 1..4），这个类就没有再碰的必要了。
    }

    /** 把档位直接映射成电流写下去，并记住它供开灯时补写。 */
    private static void applyCurrent(int level, String why) {
        applyCurrent(level, why, false);
    }

    /**
     * @param settle 这一次是不是"刚点亮"。点亮瞬间 HAL 还会重置一次电流，需要
     *               root 侧多做一次延迟复查；拖滑条的那些则不需要，否则 inotifyd
     *               串行执行会被一堆 sleep 撑出好几秒的队列积压。
     */
    private static void applyCurrent(int level, String why, boolean settle) {
        lastLevel = level;
        int cur = currentFor(level);
        boolean ok = post(cur, settle);
        int n = APPLIES.incrementAndGet();
        if (n <= 8 || n % 50 == 0) {
            XposedBridge.log("FlashlightLevelBridge: " + why + " level=" + level
                    + " -> current=" + cur + " (posted=" + ok + ", #" + n + ")");
        }
    }

    /**
     * 档位 → 电流。档位可能是 0 基或 1 基，两种都收：先归一到 0..LEVELS-1，
     * 再在 CURRENT_MIN..CURRENT_MAX 之间线性取值。
     */
    private static int currentFor(int level) {
        // 实测滑条给的是 1..4（1 基）。早先按 0 基算，结果 3 档和 4 档都被
        // 钳到顶格 78，最高两档看不出区别 —— 这里减 1 归一。
        int idx = level > 0 ? level - 1 : 0;
        if (idx >= LEVELS) idx = LEVELS - 1;
        if (idx < 0) idx = 0;
        if (LEVELS <= 1) return CURRENT_MAX;
        int span = CURRENT_MAX - CURRENT_MIN;
        return CURRENT_MIN + (span * idx) / (LEVELS - 1);
    }

    /**
     * 把目标电流投进信箱。写的是 SystemUI 自己的 DE 数据文件，应用对自己的
     * 数据目录永远有权限，不碰 sysfs 标签，因此这一步不会再有 EACCES。
     * 真正写 led:torch_* 的是 root 侧 inotifyd 拉起的 torch_level_apply.sh。
     */
    private static boolean post(int current, boolean settle) {
        // 去重：同一次拖动里 turnOnTorchWithStrengthLevel 和控制器里那几个
        // setLevel/setFlashlightLevel 会各触发一遍，值却是同一个。不挡掉的话
        // root 侧要多跑好几轮，而驱动每收到一次写就重新下发一次电流 —— 那是
        // 肉眼可见的闪。
        if (current == lastPosted && !settle) return true;
        FileOutputStream out = null;
        try {
            File f = new File(MAILBOX);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            out = new FileOutputStream(f);
            out.write((Integer.toString(current) + (settle ? " on" : "") + "\n").getBytes());
            out.flush();
            lastPosted = current;
            return true;
        } catch (Throwable t) {
            int n = WRITE_FAILS.incrementAndGet();
            if (n <= 6) XposedBridge.log("FlashlightLevelBridge: 投信箱失败: " + t);
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
        }
    }

    /** 停用开关是否被拉下。每次调用现查，方便不重启 SystemUI 就切换。 */
    private static boolean disabled() {
        try { return new File(KILL_SWITCH).exists(); } catch (Throwable t) { return false; }
    }

    private static Integer readIntField(Object o, String name) {
        try {
            Field f = findField(o.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            Object v = f.get(o);
            return (v instanceof Integer) ? (Integer) v : null;
        } catch (Throwable t) { return null; }
    }

    private static Boolean readBoolField(Object o, String name) {
        try {
            Field f = findField(o.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            Object v = f.get(o);
            return (v instanceof Boolean) ? (Boolean) v : null;
        } catch (Throwable t) { return null; }
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        return null;
    }
}
