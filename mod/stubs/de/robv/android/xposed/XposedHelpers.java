package de.robv.android.xposed;
public final class XposedHelpers {
  public static Class<?> findClass(String name, ClassLoader cl){return null;}
  public static Class<?> findClassIfExists(String name, ClassLoader cl){return null;}
  public static Object getObjectField(Object obj, String name){return null;}
  public static void setObjectField(Object obj, String name, Object v){}
  public static int getIntField(Object obj, String name){return 0;}
  public static void setIntField(Object obj, String name, int v){}
  public static Object callMethod(Object obj, String name, Object... args){return null;}
  public static Object callStaticMethod(Class<?> c, String name, Object... args){return null;}
  public static Object getAdditionalInstanceField(Object obj, String key){return null;}
  public static Object setAdditionalInstanceField(Object obj, String key, Object v){return null;}
  public static java.lang.reflect.Method findMethodExact(Class<?> c, String name, Object... params){return null;}
  public static de.robv.android.xposed.XC_MethodHook.Unhook findAndHookMethod(Class<?> c, String name, Object... paramsAndHook){return null;}
}
