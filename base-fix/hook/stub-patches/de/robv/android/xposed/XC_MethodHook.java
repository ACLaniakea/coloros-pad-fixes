package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-only LSPosed API surface; never packaged into the hook APK. */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public Throwable getThrowable() { return null; }
        public boolean hasThrowable() { return false; }
        public void setResult(Object result) {}
        public void setThrowable(Throwable throwable) {}
    }

    public class Unhook {
        public Member getHookedMethod() { return null; }
        public XC_MethodHook getCallback() { return XC_MethodHook.this; }
        public void unhook() {}
    }
}
