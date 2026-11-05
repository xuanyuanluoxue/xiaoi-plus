package de.robv.android.xposed;
public class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader cl) { return null; }
    public static void findAndHookMethod(String className, ClassLoader cl, String methodName, Object... parameterTypesAndCallback) {}
    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {}
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static void setObjectField(Object obj, String fieldName, Object value) {}
}
