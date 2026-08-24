package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Compile-only API surface. LSPosed supplies the implementation at runtime. */
public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String message) {}
    public static void log(Throwable throwable) {}
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) { return null; }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) { return null; }
}
