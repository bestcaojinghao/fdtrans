package com.kxxy.fdtrans;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * F-Droid 实时翻译：hook Compose 文本层，把英文 AnnotatedString 换成中文。
 * 无悬浮窗、无附加 UI。
 *
 * 流程：构造 AnnotatedString -> 查缓存 -> 命中则改写 / 未命中则排队翻译
 *      -> 翻译完成后限流触发当前 Activity 重建，让已渲染的英文变中文。
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "FDTrans";
    private static final String TARGET = "org.fdroid.fdroid";
    private static final String AST = "androidx.compose.ui.text.AnnotatedString";

    /** 安静期：最后一条译文到达后再等这么久才重建，避免连续刷新闪烁 */
    private static final long DEBOUNCE_MS = 700L;
    /** 一直有新译文时，最多等这么久就强制刷一次 */
    private static final long MAX_WAIT_MS = 6000L;
    /** 用户在输入框里打字时，推迟重建的最大时间 */
    private static final long TYPING_DEFER_MS = 120000L;

    /** 预编译：shouldTranslate 每次文本构造都会调用，不能反复编译正则 */
    private static final java.util.regex.Pattern ASCII_ONLY =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.\\-/:@+]+$");
    private static final java.util.regex.Pattern HAS_SEP =
            java.util.regex.Pattern.compile(".*[.:@/].*");
    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("^[vV]?\\d+(\\.\\d+)+.*");

    /** 懒创建：模块类在 zygote fork 早期就被加载，那时 main Looper 还不存在。 */
    private static volatile Handler uiHandler;

    private static Handler ui() {
        Handler h = uiHandler;
        if (h != null) return h;
        synchronized (Main.class) {
            if (uiHandler == null) {
                Looper l = Looper.getMainLooper();
                if (l == null) return null;
                uiHandler = new Handler(l);
            }
            return uiHandler;
        }
    }
    private static volatile WeakReference<Activity> cur = new WeakReference<Activity>(null);
    private static volatile long lastRefresh;
    private static volatile long startAt;
    private static volatile Application app;
    private static volatile int lastSeen = -1;
    /** debounce 状态：首次待刷新时间 + 译文序号 */
    private static volatile long firstPendingAt;
    private static volatile int pendingSeq;

    /** 调试用静态字典，优先于网络 */
    private static final java.util.Map<String, String> DICT = new java.util.HashMap<String, String>();

    static {
        DICT.put("Latest", "最新");
        DICT.put("Nearby", "附近");
        DICT.put("Search", "搜索");
        DICT.put("Anti-Features", "负面特征");
        DICT.put("Versions", "版本");
        DICT.put("Description", "描述");
        DICT.put("Permissions", "权限");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;
        Log("module loaded in " + lp.packageName);
        startAt = System.currentTimeMillis();

        // Application 创建后才知道 filesDir
        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass("android.app.Application", lp.classLoader),
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Application a = (Application) p.thisObject;
                                app = a;
                                loadPrefs(a);
                                File dir = a.getFilesDir();
                                Translator.init(dir);
                                registerCfgReceiver(a);
                                Log("cache dir = " + dir + " enabled=" + Config.enabled());
                            } catch (Throwable t) {
                                Log("init err " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log("app hook err " + t);
        }

        // 记录前台 Activity，供翻译完成后刷新
        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass("android.app.Activity", lp.classLoader),
                    "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            cur = new WeakReference<Activity>((Activity) p.thisObject);
                        }
                    });
        } catch (Throwable t) {
            Log("activity hook err " + t);
        }

        Translator.setListener(new Translator.Listener() {
            @Override
            public void onTranslated(String src, String dst) {
                maybeRefresh();
            }
        });

        try {
            Class<?> ast = XposedHelpers.findClass(AST, lp.classLoader);
            Log("AnnotatedString found: " + ast);
            XposedBridge.hookAllConstructors(ast, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Config.enabled()) return;
                    try {
                        String src = firstString(param.args);
                        if (src == null || src.length() == 0) return;

                        String tr = DICT.get(src);
                        if (tr == null) {
                            if (!shouldTranslate(src)) return;
                            tr = Translator.get(src);
                            if (tr == null) {
                                Translator.submit(src);
                                return;
                            }
                        }
                        if (tr.equals(src)) return;
                        // 关键：文本长度变了，span 的字符区间还是按原文算的，
                        // 不清掉会让 Compose 的 setSpan 抛 IndexOutOfBoundsException（此前闪退根因）。
                        dropSpans(param.thisObject);
                        XposedHelpers.setObjectField(param.thisObject, "text", tr);
                        Log("REPLACED: [" + clip(src) + "] -> [" + clip(tr) + "]");
                    } catch (Throwable ignore) { }
                }
            });
        } catch (Throwable t) {
            Log("hook fail " + t);
        }
    }

    /** 翻译完成 -> 安静期后重建当前 Activity，让英文变中文。 */
    private static void maybeRefresh() {
        final Handler h = ui();
        if (h == null) return;
        final int mySeq;
        synchronized (Main.class) {
            if (firstPendingAt == 0L) firstPendingAt = System.currentTimeMillis();
            mySeq = ++pendingSeq;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = System.currentTimeMillis();
                    boolean quiet;
                    synchronized (Main.class) {
                        quiet = (mySeq == pendingSeq);
                    }
                    boolean overdue = (now - firstPendingAt) >= MAX_WAIT_MS;
                    if (!quiet && !overdue) {
                        // 还在陆续来新译文，稍后再看，避免刷到一半又变
                        h.postDelayed(this, 400L);
                        return;
                    }
                    doRefresh();
                } catch (Throwable t) {
                    Log("refresh err " + t);
                }
            }
        }, DEBOUNCE_MS);
    }

    /** 真正重建界面；被打断的条件都记录日志，方便现场判断。 */
    private static void doRefresh() {
        Activity a = cur.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        if (isUserTyping(a)) {
            // 输入中不重建：recreate 会清空搜索框、收掉键盘
            Log("skip refresh: user typing");
            synchronized (Main.class) {
                if (System.currentTimeMillis() - firstPendingAt >= TYPING_DEFER_MS) firstPendingAt = 0L;
            }
            return;
        }
        int n = Translator.doneCount();
        if (n == lastSeen) {
            synchronized (Main.class) { firstPendingAt = 0L; }
            return;
        }
        lastSeen = n;
        lastRefresh = System.currentTimeMillis();
        synchronized (Main.class) { firstPendingAt = 0L; }
        Log("refresh UI, translated=" + n);
        a.recreate();
    }

    /** 键盘弹起或有 EditText 持有焦点时，认为用户在输入。 */
    private static boolean isUserTyping(Activity a) {
        try {
            android.view.View root = a.getWindow().peekDecorView();
            if (root == null) return false;
            android.view.View f = root.findFocus();
            if (f instanceof android.widget.EditText) return true;
            android.view.WindowInsets ins = root.getRootWindowInsets();
            if (ins != null && android.os.Build.VERSION.SDK_INT >= 30) {
                if (ins.isVisible(android.view.WindowInsets.Type.ime())) return true;
            }
        } catch (Throwable ignore) { }
        return false;
    }


    /** 清空按原文字符位置记录的 span/annotation，避免译文变短后区间越界。 */
    private static void dropSpans(Object ast) {
        try {
            Object ss = XposedHelpers.getObjectField(ast, "spanStylesOrNull");
            if (ss != null && !((java.util.List<?>) ss).isEmpty()) {
                XposedHelpers.setObjectField(ast, "spanStylesOrNull", null);
            }
        } catch (Throwable ignore) { }
        try {
            Object ps = XposedHelpers.getObjectField(ast, "paragraphStylesOrNull");
            if (ps != null && !((java.util.List<?>) ps).isEmpty()) {
                XposedHelpers.setObjectField(ast, "paragraphStylesOrNull", null);
            }
        } catch (Throwable ignore) { }
        try {
            Object an = XposedHelpers.getObjectField(ast, "annotations");
            if (an != null && !((java.util.List<?>) an).isEmpty()) {
                XposedHelpers.setObjectField(ast, "annotations", java.util.Collections.emptyList());
            }
        } catch (Throwable ignore) { }
    }

    /** 取出构造参里的第一个 String（AnnotatedString 的所有构造器首参都是原文）。 */
    private static String firstString(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof String) return (String) a;
        }
        return null;
    }

    /** 需要翻译的判据：含拉丁字母、不含已存在的中日韩字符、不是 URL/包名/版本号。 */
    static boolean shouldTranslate(String s) {
        int n = s.length();
        if (n < 2 || n > 2000) return false;
        boolean latin = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)
                    || (c >= 0xAC00 && c <= 0xD7AF)) {
                return false;
            }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin = true;
        }
        if (!latin) return false;
        // 单个词、短、无空格：多半是应用/项目专名（Buddy / Immich / Skywire），
        // 直译会变成"巴迪"这种怪结果，默认保留英文。
        if (Config.skipNames() && n <= 12) {
            boolean space = false;
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t') { space = true; break; }
            }
            if (!space && !DICT.containsKey(s)) return false;
        }
        if (ASCII_ONLY.matcher(s).matches()) {
            if (HAS_SEP.matcher(s).matches()) return false;
            if (VERSION.matcher(s).matches()) return false;
        }
        return true;
    }

    private static String clip(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    private static void Log(String m) {
        android.util.Log.i(TAG, m);
    }

    /** 开关持久化在 F-Droid 自己的 prefs 里（同 uid 可读写）。 */
    private static void loadPrefs(Application a) {
        try {
            android.content.SharedPreferences sp = a.getSharedPreferences(Config.PREFS, 0);
            Config.setEnabled(sp.getBoolean("enabled", true));
            Config.setClearCacheAt(sp.getLong("clearCacheAt", 0L));
            Config.setSkipNames(sp.getBoolean("skipNames", true));
            String eng = sp.getString("engine", "bing");
            if ("mymemory".equals(eng) && !sp.getBoolean("engineMigrated", false)) {
                eng = "bing";   // 老版本默认是 mymemory，迁移到 bing
                sp.edit().putString("engine", eng).putBoolean("engineMigrated", true).commit();
            }
            Config.setEngine(eng);
            Log("prefs loaded enabled=" + Config.enabled() + " clearAt=" + Config.clearCacheAt());
        } catch (Throwable t) {
            Log("prefs err " + t);
        }
    }

    /** 设置页通过显式广播推送变更；收到后落盘，下次启动即可恢复。 */
    private static void registerCfgReceiver(final Application a) {
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    try {
                        android.content.SharedPreferences.Editor e =
                                a.getSharedPreferences(Config.PREFS, 0).edit();
                        if (i.hasExtra("enabled")) {
                            boolean v = i.getBooleanExtra("enabled", true);
                            Config.setEnabled(v);
                            e.putBoolean("enabled", v);
                        }
                        if (i.hasExtra("engine")) {
                            String en = i.getStringExtra("engine");
                            Config.setEngine(en);
                            e.putString("engine", en);
                        }
                        if (i.hasExtra("skipNames")) {
                            boolean v = i.getBooleanExtra("skipNames", true);
                            Config.setSkipNames(v);
                            e.putBoolean("skipNames", v);
                        }
                        if (i.hasExtra("prewarm")) {
                            int m = i.getIntExtra("prewarm", 1);
                            Log("prewarm requested mode=" + m);
                            Prewarm.start(a, m);
                        }
                        if (i.hasExtra("clearCacheAt")) {
                            long t = i.getLongExtra("clearCacheAt", 0L);
                            Config.setClearCacheAt(t);
                            e.putLong("clearCacheAt", t);
                            Translator.clearNow();
                        }
                        e.apply();
                        Log("cfg broadcast enabled=" + Config.enabled());
                    } catch (Throwable t) {
                        Log("cfg recv err " + t);
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(Config.ACTION);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                a.registerReceiver(r, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                a.registerReceiver(r, f);
            }
            Log("cfg receiver registered");
        } catch (Throwable t) {
            Log("cfg receiver err " + t);
        }
    }
}
