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
    private static final String SELF = "com.kxxy.fdtrans";
    /** 不碰的包：系统/桌面/输入法这类改文本容易搞坏系统的，以及自家其它模块 */
    private static final java.util.Set<String> SKIP = new java.util.HashSet<String>(
            java.util.Arrays.asList(
                    SELF, "android", "com.android.systemui", "com.android.settings",
                    "com.android.phone", "com.android.bluetooth", "com.android.inputmethod.latin",
                    "com.android.providers.media", "com.android.packageinstaller"));
    private static final String AST = "androidx.compose.ui.text.AnnotatedString";
    private static final java.util.concurrent.atomic.AtomicInteger SKIPPED =
            new java.util.concurrent.atomic.AtomicInteger();

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
    /** 13 MB / 4.2 GiB 这类大小串，翻了反而看不懂 */
    /** 体积/数量这类值不该翻：13 MB / 99k / 1.2M / 3.4GB */
    private static final java.util.regex.Pattern SIZE =
            java.util.regex.Pattern.compile("^[\\d.,]+\\s*[kKMGT]?i?[bB]?\\s*(free|可用|B)?$");
    /** 单独的数值+单位后缀（99k → 9.9万 这种误翻） */
    private static final java.util.regex.Pattern NUM_UNIT =
            java.util.regex.Pattern.compile("^[\\d.,]+\\s*[kKMGT]?$");

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
    /** 每个界面类的刷新预算：{次数, 上次时间}。防止"译文不断到达 → 无限重建"导致页面来回乱动。 */
    private static final java.util.Map<String, long[]> REFRESH = new java.util.HashMap<String, long[]>();
    private static final int MAX_REFRESH_PER_PAGE = 4;
    private static final long PAGE_MIN_GAP_MS = 4000L;
    private static final long PAGE_RESET_MS = 90000L;
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
        // 专有名词/技术名：翻成中文就是错的（Dart → 飞镖），映射到自己 = 跳过
        String[] keep = {
                "Dart", "Kotlin", "Java", "Rust", "Go", "Python", "Ruby", "PHP", "Swift", "Scala",
                "Flutter", "Compose", "Gradle", "Maven", "Node", "npm", "Deno", "Bun", "React",
                "Vue", "Svelte", "Angular", "JavaScript", "TypeScript", "HTML", "CSS", "JSON",
                "YAML", "TOML", "SQL", "SQLite", "Redis", "Docker", "Kubernetes", "Git", "GitHub",
                "GitLab", "Gitee", "Linux", "Unix", "macOS", "Windows", "Android", "iOS", "Chrome",
                "Firefox", "Safari", "Edge", "WebView", "Electron", "Unity", "Unreal", "Godot",
                "RustDesk", "Termux", "Magisk", "LSPosed", "Xposed", "Shizuku", "Root", "ADB",
                "SDK", "NDK", "APK", "IPA", "API", "GPU", "CPU", "RAM", "HTTP", "HTTPS", "TCP",
                "UDP", "DNS", "VPN", "SSH", "FTP", "TLS", "SSL", "Socket", "Proxy", "Tor",
                "Bluetooth", "WiFi", "NFC", "USB", "OTG", "LLM", "GPT", "AI", "ML", "NLP",
                "WSL", "GPL", "MIT", "Apache", "BSD", "FOSS", "OSS", "PR", "CI", "CD", "DevOps",
                "CMake", "Makefile", "Rust", "Golang", "Node.js", "Next.js", "Nuxt", "Vite",
                "Webpack", "Babel", "ESLint", "Prettier", "Jest", "Pytest", "Cargo", "Pip"
        };
        for (String k : keep) DICT.put(k, k);
        // 高频界面词：翻成中文、不加括号（比 MT 准，也不该保留英文）
        String[][] ui = {
                {"Home", "首页"}, {"Apps", "应用"}, {"App", "应用"}, {"Privacy", "隐私"},
                {"History", "历史"}, {"Settings", "设置"}, {"Search", "搜索"},
                {"Install", "安装"}, {"Installed", "已安装"}, {"Update", "更新"},
                {"Updates", "更新"}, {"Open", "打开"}, {"Close", "关闭"}, {"Back", "返回"},
                {"Next", "下一步"}, {"Done", "完成"}, {"Cancel", "取消"}, {"OK", "确定"},
                {"More", "更多"}, {"All", "全部"}, {"New", "新增"}, {"Free", "免费"},
                {"Paid", "付费"}, {"Pro", "专业版"}, {"Beta", "测试版"}, {"Add", "添加"},
                {"Edit", "编辑"}, {"Delete", "删除"}, {"Share", "分享"}, {"Save", "保存"},
                {"Login", "登录"}, {"Logout", "退出登录"}, {"Sign in", "登录"},
                {"Sign up", "注册"}, {"Skip", "跳过"}, {"Continue", "继续"},
                {"Active", "活跃"}, {"Inactive", "未启用"}, {"Enabled", "已启用"},
                {"Disabled", "已停用"}, {"Loading", "加载中"}, {"Retry", "重试"},
                {"Refresh", "刷新"}, {"Copy", "复制"}, {"Paste", "粘贴"}, {"Undo", "撤销"},
                {"Safe", "安全"}, {"Unsafe", "不安全"}, {"Block", "屏蔽"}, {"Allow", "允许"},
                {"Download", "下载"}, {"Upload", "上传"}, {"Size", "大小"},
                {"Version", "版本"}, {"About", "关于"}, {"Help", "帮助"},
                {"Notifications", "通知"}, {"Language", "语言"}, {"Theme", "主题"}
        };
        for (String[] kv : ui) DICT.put(kv[0], kv[1]);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        String pkg = lp.packageName;
        if (pkg == null) return;
        if (SKIP.contains(pkg)) return;
        if (pkg.startsWith("com.kxxy.")) return;   // 自家模块不互相 hook
        Log("module loaded in " + pkg);
        final ClassLoader cl = lp.classLoader;
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
                                Translator.init(a, dir);
        startRemotePump();
                                installComposeHooks(a, cl);
                                Prewarm.resumeIfNeeded(a);
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

        // 通用路径：View 体系的 app（RecyclerView / 普通 TextView）全部走这里。
        // 只替换纯 String 文本，带 span 的一律放过，避免区间越界崩溃。
        try {
            Class<?> tvCls = XposedHelpers.findClass("android.widget.TextView", lp.classLoader);
            XC_MethodHook tvHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Config.enabled()) return;
                    try {
                        Object o = param.args[0];
                        if (!(o instanceof String)) return;
                        String src = (String) o;
                        if (src.length() < 2 || src.length() > 2000) return;
                        String tr = translate(src, "ast");
                    if (tr == null) return;
                        param.args[0] = tr;
                        Log("TVIEW: [" + clip(src) + "] -> [" + clip(tr) + "]");
                    } catch (Throwable ignore) { }
                }
            };
            XposedHelpers.findAndHookMethod(tvCls, "setText", CharSequence.class,
                    "android.widget.TextView$BufferType", tvHook);
            XposedHelpers.findAndHookMethod(tvCls, "setText", CharSequence.class, tvHook);
                tvOk = true;
            Log("TextView hook ok");
        } catch (Throwable t) {
            Log("TextView hook skip: " + t);
        }

        // Compose 文本层无 Context 时也得装（真正安装挪到 Application.onCreate，见 installComposeHooks）
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
        // 同一界面类的重建次数/间隔限制：超过预算就只用缓存，不再重建（下次进页面直接是中文）
        String key = a.getClass().getName();
        long now = System.currentTimeMillis();
        long[] st = REFRESH.get(key);
        if (st == null) {
            st = new long[]{0L, 0L};
            REFRESH.put(key, st);
        }
        if (now - st[1] > PAGE_RESET_MS) st[0] = 0L;
        if (st[0] >= MAX_REFRESH_PER_PAGE) {
            Log("skip refresh: budget used up for " + key + " (" + st[0] + ")");
            synchronized (Main.class) { firstPendingAt = 0L; }
            lastSeen = n;
            return;
        }
        if (now - st[1] < PAGE_MIN_GAP_MS) {
            Log("skip refresh: too soon for " + key);
            synchronized (Main.class) { firstPendingAt = 0L; }
            return;
        }
        st[0]++;
        st[1] = now;
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
    /**
     * 单 token、短、无空格 → 多半是应用/项目专名（Buddy / Immich / Element），保留英文并括号给中文。
     * 全大写（ANDROID / WINDOWS / INSTALL）不算专名，是界面标签，直接翻，不加括号。
     */
    static boolean isNameLike(String s) {
        int n = s.length();
        if (n < 2 || n > 12) return false;
        boolean lower = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '-') return false;
            if (c >= 'a' && c <= 'z') lower = true;
        }
        return lower;
    }

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
        if (SIZE.matcher(s).matches()) return false;
        if (NUM_UNIT.matcher(s).matches()) return false;
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
            Config.setDebug(sp.getBoolean("debug", false));
            Config.setParallel(sp.getBoolean("parallel", true));
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
                        if (i.hasExtra("debug")) {
                            boolean v = i.getBooleanExtra("debug", false);
                            Config.setDebug(v);
                            e.putBoolean("debug", v);
                        }
                        if (i.hasExtra("parallel")) {
                            boolean v = i.getBooleanExtra("parallel", true);
                            Config.setParallel(v);
                            e.putBoolean("parallel", v);
                        }
                        if (i.hasExtra("skipNames")) {
                            boolean v = i.getBooleanExtra("skipNames", true);
                            Config.setSkipNames(v);
                            e.putBoolean("skipNames", v);
                        }
                        if (i.getBooleanExtra("queryStat", false)) {
                            Translator.report(true);
                        }
                        if (i.getBooleanExtra("queryProbe", false)) {
                            sendProbe(a);
                        }
                        if (i.hasExtra("queryStatus")) {
                            Prewarm.sendStatus(a);
                        }
                        if (i.hasExtra("prewarm")) {
                            int m = i.getIntExtra("prewarm", 1);
                            long req = i.getLongExtra("req", 0L);
                            Log("prewarm requested mode=" + m + " req=" + req);
                            Prewarm.start(a, m, req);
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

    /**
     * Compose 文本层 hook。类名不能写死：Compose Multiplatform 的类叫
     * androidx$compose$ui$text$AnnotatedString。先试常见名，再从目标 APK 的 dex 里扫。
     */
    private static void installComposeHooks(Application app, ClassLoader cl) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        names.add(AST);
        names.add("androidx$compose$ui$text$AnnotatedString");
        try {
            names.addAll(ComposeText.discover(app));
        } catch (Throwable t) {
            Log("compose discover fail " + t);
        }
        // 诊断：这个 ClassLoader 到底能不能加载它
        try {
            Log("cl=" + cl.getClass().getName() + " parent=" + cl.getParent());
        } catch (Throwable ignore) { }
        try {
            Class<?> c = Class.forName("androidx.compose.ui.text.AnnotatedString", false, cl);
            Log("forName ok " + c);
        } catch (Throwable t) {
            Log("forName fail " + t);
        }
        int hooked = 0;
        for (String n : names) {
            try {
                Class<?> ast = XposedHelpers.findClass(n, cl);
                XposedBridge.hookAllConstructors(ast, composeCtorHook());
                Log("AnnotatedString found: " + ast);
                hooked++;
            } catch (Throwable t) {
                Log("hook skip " + n + ": " + t);
            }
        }
        Log("compose hooks installed: " + hooked + " / " + names.size());
        installParagraphHooks(cl);
        installPlainTextCacheHooks(cl);
        if (Config.debug()) installDiagHooks(cl);
        int structOk = 0;
        if (hooked == 0) structOk = installStructuralHooks(app, cl);
        probeAndReport(app, hooked, structOk);
    }

    /** 能力探测：把「这个应用能不能翻」的结果广播给模块主界面。 */
    private static volatile String lastProbe;
    private static volatile int lastNamed, lastStruct;
    private static volatile boolean tvOk;

    private static void probeAndReport(Application app, int named, int struct) {
        lastNamed = named;
        lastStruct = struct;
        try {
            lastProbe = Dex.probe(app, tvOk);
        } catch (Throwable t) {
            lastProbe = "探测失败：" + t;
        }
        Log("probe: named=" + named + " struct=" + struct + " tv=" + tvOk + " para=" + paraHooks
                + " textcache=" + textCacheHooks + " · " + lastProbe);
        // 落盘：主界面/终端都能事后查，不依赖前台
        try {
            java.io.File f = new java.io.File(app.getFilesDir(), "fdtrans-probe.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write("pkg=" + app.getPackageName() + "\n");
            w.write("named=" + named + "\n");
            w.write("struct=" + struct + "\n");
            w.write("tv=" + tvOk + "\n");
            w.write("at=" + System.currentTimeMillis() + "\n");
            w.write("para=" + paraHooks + "\n");
            w.write("textcache=" + textCacheHooks + "\n");
            w.write("probe=" + lastProbe + "\n");
            w.close();
            // 目标应用私有目录，模块主界面用 root 读
            Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "chmod 644 '" + f.getAbsolutePath() + "'"}).waitFor();
        } catch (Throwable t) {
            Log("probe write fail " + t);
        }
        sendProbe(app);
    }

    static void sendProbe(Application app) {
        try {
            android.content.Intent i = new android.content.Intent(Config.PROBE);
            i.setPackage(Config.SELF);
            i.putExtra("pkg", app.getPackageName());
            i.putExtra("named", lastNamed);
            i.putExtra("struct", lastStruct);
            i.putExtra("tv", tvOk);
            i.putExtra("probe", lastProbe);
            app.sendBroadcast(i);
        } catch (Throwable t) {
            Log("sendProbe fail " + t);
        }
    }

    /**
     * 名字被 R8 混淆的应用（Compose Multiplatform 之类）：按构造函数签名找 AnnotatedString，
     * 再用反射找那个 String 字段（名字可能是 a/b/c），把 List 字段清空防止 span 越界。
     */
    private static int installStructuralHooks(Application app, ClassLoader cl) {
        java.util.List<String> cands;
        try {
            cands = Dex.annotatedStringCandidates(app);
        } catch (Throwable t) {
            Log("struct discover fail " + t);
            return 0;
        }
        Log("struct candidates: " + cands);
        int ok = 0;
        for (String n : cands) {
            try {
                Class<?> c = XposedHelpers.findClass(n, cl);
                if (textFieldOf(c) == null) {
                    Log("struct skip (no String field): " + n);
                    continue;
                }
                XposedBridge.hookAllConstructors(c, structuralCtorHook());
                Log("struct hooked: " + c);
                ok++;
            } catch (Throwable t) {
                Log("struct hook skip " + n + ": " + t);
            }
        }
        Log("structural hooks installed: " + ok);
        return ok;
    }

    /**
     * 新版 Compose（1.9 起）的纯 String 文本根本不构造 AnnotatedString：
     * BasicText(String) → ParagraphLayoutCache(String,...) → AndroidParagraphIntrinsics(String,...)。
     * 所以再挂一层最深的排版入口，谁都要经过这里。
     */
    private static void installParagraphHooks(ClassLoader cl) {
        String[] names = {
                "androidx.compose.ui.text.platform.AndroidParagraphIntrinsics",
                "androidx$compose$ui$text$platform$AndroidParagraphIntrinsics"
        };
        int ok = 0;
        for (String n : names) {
            try {
                Class<?> c = XposedHelpers.findClass(n, cl);
                XposedBridge.hookAllConstructors(c, paragraphCtorHook());
                Log("paragraph hook ok: " + n);
                ok++;
            } catch (Throwable t) {
                Log("paragraph hook skip " + n + ": " + t);
            }
        }
        paraHooks = ok;
    }

    private static volatile int paraHooks;

    /**
     * Compose 1.9 的纯 String 文本从 BasicText 直接进 ParagraphLayoutCache(String, TextStyle, ...)，
     * 既不造 AnnotatedString 也不马上造 ParagraphIntrinsics（Vanillify 实测）。
     * 这里把入参 String 换掉，路径上没有 span 列表，不存在越界风险。
     */
    private static void installPlainTextCacheHooks(ClassLoader cl) {
        String[] names = {
                "androidx.compose.foundation.text.modifiers.ParagraphLayoutCache",
                "androidx.compose.foundation.text.modifiers.MultiParagraphLayoutCache"
        };
        int ok = 0;
        for (String n : names) {
            try {
                Class<?> c = XposedHelpers.findClass(n, cl);
                XposedBridge.hookAllConstructors(c, stringArgCtorHook("cache"));
                Log("text cache hook ok: " + n);
                ok++;
            } catch (Throwable t) {
                Log("text cache hook skip " + n + ": " + t);
            }
        }
        textCacheHooks = ok;
    }

    private static volatile int textCacheHooks;

    /** 目标进程自己没网时，定期收回模块 app 代翻的结果。 */
    private static void startRemotePump() {
        final Handler h = ui();
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Translator.pumpRemote() > 0) maybeRefresh();
                } catch (Throwable ignore) { }
                h.postDelayed(this, 2000L);
            }
        }, 2000L);
    }

    /** 通用的「构造函数第 0 个参数是 String 就翻译」hook。 */
    private static XC_MethodHook stringArgCtorHook(final String tag) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Config.enabled()) return;
                try {
                    Object[] a = param.args;
                    if (a == null || a.length == 0 || !(a[0] instanceof String)) return;
                    String src = (String) a[0];
                    String tr = translate(src, tag);
                    if (tr == null) return;
                    a[0] = tr;
                    Log("REPLACED(" + tag + "): [" + clip(src) + "] -> [" + clip(tr) + "]");
                    maybeRefresh();
                } catch (Throwable ignore) { }
            }
        };
    }

    /** 统一翻译入口：字典 → 判据 → 缓存 → 未命中就排队。返回 null 表示不改写。 */
    static String translate(String src, String tag) {
        if (src == null || src.length() < 2 || src.length() > 2000) return null;
        String tr = DICT.get(src);
        boolean fromDict = tr != null;
        if (tr == null) {
            if (!shouldTranslate(src)) {
                skip("rule", src);
                return null;
            }
            tr = Translator.get(src);
            if (tr == null) {
                Translator.submit(src);
                return null;
            }
        }
        if (tr.equals(src)) return null;
        if (fromDict && Translator.get(src) == null) Translator.put(src, tr);  // 只记一次，避免反复 +DONE 引发刷新循环
        if (!fromDict && Config.skipNames() && isNameLike(src)
                && !tr.contains(src) && !src.contains(tr)) {
            tr = src + "（" + tr + "）";
        }
        return tr;
    }

    /** 诊断：确认 hook 到底落在哪个类、UI 走的是哪条文本通路。只在前 N 次打日志。 */
    private static final java.util.concurrent.atomic.AtomicInteger DIAG_N =
            new java.util.concurrent.atomic.AtomicInteger();

    private static void installDiagHooks(ClassLoader cl) {
        String[][] targets = {
                {"androidx.compose.ui.platform.AbstractComposeView", "setContent"},
                {"androidx.compose.ui.platform.AndroidComposeView", "onDraw"},
                {"androidx.compose.ui.text.MultiParagraphIntrinsics", "<init>"},
                {"androidx.compose.foundation.text.modifiers.ParagraphLayoutCache", "<init>"}
        };
        for (String[] t : targets) {
            try {
                Class<?> c = XposedHelpers.findClass(t[0], cl);
                XC_MethodHook h = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (DIAG_N.incrementAndGet() > 12) return;
                        Object a0 = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                        Log("DIAG " + p.method.getName() + " a0="
                                + (a0 == null ? "null" : a0.getClass().getName() + "[" + clip(String.valueOf(a0)) + "]"));
                    }
                };
                if ("<init>".equals(t[1])) {
                    XposedBridge.hookAllConstructors(c, h);
                } else {
                    XposedBridge.hookAllMethods(c, t[1], h);
                }
                Log("diag ok " + t[0] + "#" + t[1]);
            } catch (Throwable e) {
                Log("diag skip " + t[0] + "#" + t[1] + ": " + e);
            }
        }
    }


    private static XC_MethodHook paragraphCtorHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Config.enabled()) return;
                try {
                    Object[] a = param.args;
                    if (a == null || a.length < 2) return;
                    if (!(a[0] instanceof String)) return;
                    String src = (String) a[0];
                    if (src.length() < 2 || src.length() > 2000) return;
                    String tr = translate(src, "hook");
                    if (tr == null) return;
                    a[0] = tr;
                    // span 区间按原文长度算，译文长度不同会越界 → 直接清空
                    for (int i = 2; i < a.length && i < 4; i++) {
                        if (a[i] instanceof java.util.List && !((java.util.List) a[i]).isEmpty()) {
                            a[i] = java.util.Collections.emptyList();
                        }
                    }
                    Log("REPLACED(para): [" + clip(src) + "] -> [" + clip(tr) + "]");
                } catch (Throwable ignore) { }
            }
        };
    }

    private static final java.util.Map<Class<?>, java.lang.reflect.Field> TEXT_FIELDS =
            new java.util.HashMap<Class<?>, java.lang.reflect.Field>();

    private static java.lang.reflect.Field textFieldOf(Class<?> c) {
        synchronized (TEXT_FIELDS) {
            if (TEXT_FIELDS.containsKey(c)) return TEXT_FIELDS.get(c);
            java.lang.reflect.Field best = null, any = null;
            try {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != String.class) continue;
                    if (any == null) any = f;
                    if ("text".equals(f.getName())) { best = f; break; }
                }
            } catch (Throwable ignore) { }
            java.lang.reflect.Field r = best != null ? best : any;
            if (r != null) r.setAccessible(true);
            TEXT_FIELDS.put(c, r);
            return r;
        }
    }

    /** 把这类对象里所有 List 字段清空（AnnotatedString 的 spanStyles / annotations）。 */
    private static void clearLists(Object o) {
        try {
            for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                if (f.get(o) != null) f.set(o, null);
            }
        } catch (Throwable ignore) { }
    }

    private static XC_MethodHook structuralCtorHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Config.enabled()) return;
                try {
                    String src = firstString(param.args);
                    if (src == null || src.length() == 0) return;
                    String tr = translate(src, "struct");
                    if (tr == null) return;
                    java.lang.reflect.Field tf = textFieldOf(param.thisObject.getClass());
                    if (tf == null) return;
                    clearLists(param.thisObject);
                    tf.set(param.thisObject, tr);
                    Log("REPLACED(struct): [" + clip(src) + "] -> [" + clip(tr) + "]");
                } catch (Throwable ignore) { }
            }
        };
    }

    private static XC_MethodHook composeCtorHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Config.enabled()) return;
                try {
                    String src = firstString(param.args);
                    if (src == null || src.length() == 0) return;

                    String tr = translate(src, "hook");
                    if (tr == null) return;
                    dropSpans(param.thisObject);
                    XposedHelpers.setObjectField(param.thisObject, "text", tr);
                    Log("REPLACED: [" + clip(src) + "] -> [" + clip(tr) + "]");
                } catch (Throwable ignore) { }
            }
        };
    }

    /** 被规则挡下的文本：只在 debug 打开时打日志，按原因计数，避免刷屏。 */
    private static void skip(String why, String s) {
        if (!Config.debug()) return;
        int n = SKIPPED.incrementAndGet();
        if (n % 50 == 0) {
            Log("skip#" + n + " (" + why + ") e.g. [" + clip(s) + "]");
        }
    }
}
