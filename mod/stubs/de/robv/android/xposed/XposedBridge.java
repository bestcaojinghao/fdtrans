package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
  public static void log(String text) {}
  public static void log(Throwable t) {}
  public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c, String name, XC_MethodHook cb){return null;}
  public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> c, XC_MethodHook cb){return null;}
}
