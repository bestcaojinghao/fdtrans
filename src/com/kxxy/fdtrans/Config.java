package com.kxxy.fdtrans;

/**
 * 开关的运行时状态。
 * 持久化在 F-Droid 自己的 SharedPreferences（fdtrans-hook），
 * 由设置页发显式广播推送变更——跨 uid 读模块 prefs 会被权限/SELinux 拦，
 * ContentResolver 跨包查询又受 package visibility 限制，广播是最稳的路。
 */
public final class Config {

    public static final String ACTION = "com.kxxy.fdtrans.CFG";
    /** 目标应用 -> 设置页：统计/进度上报 */
    public static final String STAT = "com.kxxy.fdtrans.STAT";
    public static final String SELF = "com.kxxy.fdtrans";
    public static final String REMOTE_PKG = "org.fdroid.fdroid";
    public static final String PREFS = "fdtrans-hook";

    private static volatile boolean enabled = true;
    private static volatile String engine = "bing";
    private static volatile long clearCacheAt;
    /** true = 保留应用/项目英文原名（不翻译单个词）；false = 全都翻 */
    private static volatile boolean skipNames = true;

    private Config() {}

    public static boolean enabled() {
        return enabled;
    }

    public static String engine() {
        return engine;
    }

    public static long clearCacheAt() {
        return clearCacheAt;
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static void setEngine(String e) {
        if (e != null) engine = e;
    }

    public static void setClearCacheAt(long t) {
        clearCacheAt = t;
    }

    public static boolean skipNames() {
        return skipNames;
    }

    public static void setSkipNames(boolean v) {
        skipNames = v;
    }

    /** 主引擎 Bing（免费网页接口，质量高、吞吐大）；engine=="mymemory" 时走老接口。 */
    public static boolean useBing() {
        return !"mymemory".equals(engine);
    }
}
