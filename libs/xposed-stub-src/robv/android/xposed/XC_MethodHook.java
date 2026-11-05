package de.robv.android.xposed;
import java.lang.reflect.Member;
public class XC_MethodHook {
    public class Unhook {}
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        public Object getResult() { return result; }
        public void setResult(Object r) { result = r; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { throwable = t; }
    }
}
