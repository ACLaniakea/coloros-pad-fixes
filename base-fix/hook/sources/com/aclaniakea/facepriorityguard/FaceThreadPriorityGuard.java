package com.aclaniakea.facepriorityguard;

import android.os.Process;
import android.os.SystemClock;
import com.aclaniakea.devicegate.DeviceGate;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the legacy Megvii face engine from pre-empting display work on wake.
 *
 * <p>The port has no separate vendor face_hal. Its legacy libmegface is loaded
 * into system_server and creates mgu_* workers at nice -10. The stock device
 * runs face inference in a dedicated HAL process instead, so those workers can
 * never compete with PowerManager, WindowManager, or display animation threads.
 * Preserve face unlock and only restore the native inference workers to the
 * normal priority. Scans are bounded to boot initialization and wake events;
 * no resident watchdog is used.</p>
 */
public final class FaceThreadPriorityGuard implements IXposedHookLoadPackage {
    private static final String TAG = "FaceThreadPriorityGuard";
    private static final long WAKE_SCAN_MS = 15_000L;
    private static final AtomicBoolean SCANNER_RUNNING = new AtomicBoolean(false);
    private static final AtomicLong SCAN_UNTIL = new AtomicLong(0L);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!DeviceGate.isSupported()
                || !"android".equals(lpp.packageName)
                || !"android".equals(lpp.processName)) {
            return;
        }

        installWakeHook(lpp.classLoader);
        startBootWatcher();
    }

    private static void installWakeHook(ClassLoader classLoader) {
        try {
            Class<?> powerManager = XposedHelpers.findClass(
                    "com.android.server.power.PowerManagerService", classLoader);
            XposedBridge.hookAllMethods(powerManager, "wakeUpInternal", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    requestScan(WAKE_SCAN_MS);
                }
            });
            XposedBridge.log(TAG + ": wake priority guard installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": wake hook installation failed");
            XposedBridge.log(t);
        }
    }

    /** Waits cheaply for the late-created face engine, then exits. */
    private static void startBootWatcher() {
        Thread watcher = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                Thread.sleep(10_000L);
                for (int i = 0; i < 120; i++) {
                    if (normalizeMguThreads() > 0) {
                        requestScan(WAKE_SCAN_MS);
                        return;
                    }
                    Thread.sleep(1_000L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": boot watcher failed");
                XposedBridge.log(t);
            }
        }, "FacePriorityBoot");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void requestScan(long durationMs) {
        long requested = SystemClock.elapsedRealtime() + durationMs;
        long current;
        do {
            current = SCAN_UNTIL.get();
            if (current >= requested) {
                break;
            }
        } while (!SCAN_UNTIL.compareAndSet(current, requested));

        if (!SCANNER_RUNNING.compareAndSet(false, true)) {
            return;
        }
        Thread scanner = new Thread(() -> {
            int corrected = 0;
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                do {
                    corrected += normalizeMguThreads();
                    Thread.sleep(100L);
                } while (SystemClock.elapsedRealtime() < SCAN_UNTIL.get());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": bounded scan failed");
                XposedBridge.log(t);
            } finally {
                SCANNER_RUNNING.set(false);
                if (corrected > 0) {
                    XposedBridge.log(TAG + ": restored normal priority for "
                            + corrected + " Megvii worker(s)");
                }
                if (SystemClock.elapsedRealtime() < SCAN_UNTIL.get()) {
                    requestScan(SCAN_UNTIL.get() - SystemClock.elapsedRealtime());
                }
            }
        }, "FacePriorityScan");
        scanner.setDaemon(true);
        scanner.start();
    }

    private static int normalizeMguThreads() {
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) {
            return 0;
        }
        int corrected = 0;
        for (File task : tasks) {
            String name = readFirstLine(new File(task, "comm"));
            if (name == null || !name.startsWith("mgu_")) {
                continue;
            }
            int tid;
            try {
                tid = Integer.parseInt(task.getName());
                if (Process.getThreadPriority(tid) < Process.THREAD_PRIORITY_DEFAULT) {
                    Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT);
                    corrected++;
                }
            } catch (Throwable ignored) {
                // A short-lived prepare/compare worker may exit between listing
                // and setpriority. The next scan handles its replacement.
            }
        }
        return corrected;
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
