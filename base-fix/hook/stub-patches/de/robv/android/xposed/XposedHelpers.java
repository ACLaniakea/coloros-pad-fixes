package de.robv.android.xposed;

/** Compile-only API surface. LSPosed supplies the implementation at runtime. */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookConstructor(
            String className, ClassLoader classLoader,
            Object... parameterTypesAndCallback) { return null; }
    public static Object getObjectField(Object object, String fieldName) { return null; }
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) { return null; }
    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {}
    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) { return null; }
    public static Object callMethod(Object object, String methodName, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) { return null; }
    public static boolean getBooleanField(Object object, String fieldName) { return false; }
    public static int getIntField(Object object, String fieldName) { return 0; }
    public static void setBooleanField(Object object, String fieldName, boolean value) {}
    public static void setIntField(Object object, String fieldName, int value) {}
    public static float getFloatField(Object object, String fieldName) { return 0f; }
    public static void setFloatField(Object object, String fieldName, float value) {}
}
