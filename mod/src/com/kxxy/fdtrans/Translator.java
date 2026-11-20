package com.kxxy.fdtrans;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 翻译引擎：内存缓存 -> 磁盘缓存 -> MyMemory 免费接口。
 * UI 线程只做 map 查询，永不等网络。
 */
public final class Translator {

    private static final String TAG = "FDTrans";
    private static final String API = "https://api.mymemory.translated.net/get?q=%s&langpair=en|zh-CN";
    private static final int MAX_SEG_MM = 420;   // MyMemory 单次约 500 字节上限
    private static final int MAX_SEG_BING = 1400; // Bing 网页接口单次可吃 4000+，保守取 1400
    private static final int MEM_CAP = 20000;

    /** Bing 免费网页接口：先取 IG/key/token，再 POST ttranslatev3。 */
    private static final String BING_PAGE = "https://cn.bing.com/translator";
    private static final String BING_API = "https://cn.bing.com/ttranslatev3?isVertical=1&IG=%s&IID=translator.5023.%d";
    private static final String BING_UA =
            "Mozilla/5.0 (Linux; Android 16; PJZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static volatile String bingIg, bingKey, bingToken;
    private static volatile long bingAt;
    private static final AtomicInteger BING_SEQ = new AtomicInteger(1);

    /** 已完成翻译：key=英文原文 value=中文 */
    private static final Map<String, String> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > MEM_CAP;
                }
            });

    /** 已排队/进行中，防止重复请求 */
    private static final Set<String> PENDING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 翻不动的（接口返回失败），避免反复重试烧配额 */
    private static final Set<String> FAILED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final AtomicInteger DONE = new AtomicInteger();
    private static android.content.Context appCtx;
    private static volatile long lastStatAt;
    private static volatile long lastActiveAt;
    /** 本次进程内遇到过的不同原文条数（做进度分母用） */
    private static final AtomicInteger SEEN = new AtomicInteger();

    /** 翻译成功回调（在翻译线程里调用）。 */
    public interface Listener {
        void onTranslated(String src, String dst);
    }

    private static volatile Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    private static volatile ThreadPoolExecutor pool;
    private static volatile File cacheFile;
    private static volatile FileOutputStream cacheOut;
    private static volatile boolean loaded;

    private Translator() {}

    /** 统计上报（节流 900ms）：缓存条数 / 本次新增 / 最近 5 秒内是否在翻 */
    public static void report(boolean force) {
        android.content.Context c = appCtx;
        if (c == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastStatAt < 900L) return;
        lastStatAt = now;
        try {
            android.content.Intent i = new android.content.Intent(Config.STAT);
            i.setPackage(Config.SELF);
            i.putExtra("pkg", c.getPackageName());
            i.putExtra("cache", CACHE.size());
            i.putExtra("new", DONE.get());
            i.putExtra("active", now - lastActiveAt < 5000L);
            i.putExtra("seen", SEEN.get());
            c.sendBroadcast(i);
        } catch (Throwable ignore) { }
    }

    private static File reqFile, resFile, skipFile;
    /** 本进程有没有网络权限（缺 INTERNET 的应用会抛 SecurityException） */
    private static volatile boolean noInet;
    /** 已经写进请求文件、还没拿到译文的文本（避免每次重组都重复追加） */
    private static final java.util.Set<String> SPOOLED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    public static boolean noInet() { return noInet; }

    /** 本进程能否自己上网：没网权限或已知断网都不行，得走模块进程代翻。 */
    public static boolean canNet() { return !noInet && !netDown; }

    public static void init(android.content.Context ctx, File dir) {
        appCtx = ctx == null ? null : ctx.getApplicationContext();
        cacheFile = new File(dir, "fdtrans-cache.tsv");
        reqFile = new File(dir, "fdtrans-req.tsv");
        resFile = new File(dir, "fdtrans-res.tsv");
        skipFile = new File(dir, "fdtrans-skip.tsv");
        try {
            java.net.CookieHandler.setDefault(new java.net.CookieManager());
        } catch (Throwable ignore) { }
        applyClearIfNeeded(dir);
        loadDisk();
        loadSkip();
    }

    /** 运行期收到"清空缓存"命令：立即清内存并删盘。 */
    public static void clearNow() {
        try {
            CACHE.clear();
            FAILED.clear();
            if (cacheFile != null && cacheFile.exists()) cacheFile.delete();
            Log.i(TAG, "cache cleared at runtime");
        } catch (Throwable t) {
            Log.w(TAG, "clearNow err " + t);
        }
    }

    /** 设置页点"清空缓存"后写时间戳；这里按时间戳只执行一次。 */
    private static void applyClearIfNeeded(File dir) {
        try {
            long want = Config.clearCacheAt();
            if (want <= 0) return;
            File stamp = new File(dir, "fdtrans-clear.stamp");
            long done = 0L;
            if (stamp.exists()) {
                BufferedReader r0 = null;
                try {
                    r0 = new BufferedReader(new InputStreamReader(new FileInputStream(stamp), "UTF-8"));
                    String line = r0.readLine();
                    if (line != null) done = Long.parseLong(line.trim());
                } catch (Throwable ignore) {
                } finally {
                    close(r0);
                }
            }
            if (want <= done) return;
            CACHE.clear();
            if (cacheFile != null && cacheFile.exists()) cacheFile.delete();
            FileOutputStream o0 = null;
            try {
                o0 = new FileOutputStream(stamp);
                o0.write(String.valueOf(want).getBytes("UTF-8"));
            } finally {
                close(o0);
            }
            Log.i(TAG, "cache cleared by user request");
        } catch (Throwable t) {
            Log.w(TAG, "clear err " + t);
        }
    }

    public static String get(String src) {
        return src == null ? null : CACHE.get(src);
    }

    /** 当前内存里缓存条数（含启动时从磁盘载入的），用于面板显示。 */
    public static int cacheSize() {
        return CACHE.size();
    }

    public static int doneCount() {
        return DONE.get();
    }

    public static boolean isPending(String src) {
        return PENDING.contains(src);
    }

    /** 提交翻译；已有缓存/已排队/已失败则直接返回。 */
    public static void submit(final String src) {
        if (src == null || CACHE.containsKey(src) || FAILED.contains(src) || DEAD.contains(src)) return;
        if (!PENDING.add(src)) return;
        SEEN.incrementAndGet();
        if (!canNet()) {
            PENDING.remove(src);
            if (SPOOLED.add(src)) spoolRequest(src);
            return;
        }
        // 界面上一屏会一口气冒几十条新串；逐条发请求要几十秒，
        // 所以先攒 250ms 再一次多引擎批量发出去，一屏通常 1~3 秒全部到位。
        synchronized (WAIT_LOCK) {
            if (src.length() >= 120) WAIT.addFirst(src); else WAIT.add(src);
            if (!drainScheduled) {
                drainScheduled = true;
                pool().execute(new Runnable() {
                    @Override
                    public void run() {
                        try { Thread.sleep(250L); } catch (Throwable ignore) { }
                        drain();
                    }
                });
            }
        }
    }

    private static final java.util.ArrayDeque<String> WAIT = new java.util.ArrayDeque<String>();
    private static final Object WAIT_LOCK = new Object();
    private static volatile boolean drainScheduled;

    private static void drain() {
        while (true) {
            List<String> batch = new ArrayList<String>();
            synchronized (WAIT_LOCK) {
                int chars = 0;
                while (!WAIT.isEmpty() && batch.size() < 24 && chars < 3500) {
                    String x = WAIT.poll();
                    if (x == null) break;
                    batch.add(x);
                    chars += x.length();
                }
                if (batch.isEmpty()) {
                    drainScheduled = false;
                    return;
                }
            }
            try {
                String[] got = batch.size() == 1 ? null : translateBatch(batch);
                if (got == null) {
                    got = new String[batch.size()];
                    for (int i = 0; i < batch.size(); i++) got[i] = translate(batch.get(i));
                }
                for (int i = 0; i < batch.size(); i++) {
                    String s = batch.get(i);
                    String d = (got != null && i < got.length) ? got[i] : null;
                    if (d != null && d.length() > 0 && d.equalsIgnoreCase(s)) {
                        // 引擎原样返回：用户名/用户名句/纯品牌名，翻不动，记盘上别再烧配额
                        markDead(s);
                    }
                    if (d != null && d.length() > 0 && !d.equalsIgnoreCase(s)) {
                        CACHE.put(s, d);
                        appendDisk(s, d);
                        DONE.incrementAndGet();
                        lastActiveAt = System.currentTimeMillis();
                        report(false);
                        Listener l = listener;
                        if (l != null) {
                            try { l.onTranslated(s, d); } catch (Throwable ignore) { }
                        }
                    } else {
                        FAILED.add(s);
                    }
                    PENDING.remove(s);
                }
            } catch (Throwable t) {
                Log.w(TAG, "drain fail: " + t);
                synchronized (WAIT_LOCK) {
                    for (String s : batch) { PENDING.remove(s); FAILED.add(s); }
                }
            }
        }
    }

    /** 把一条待翻文本追加到本进程的请求文件，等模块 app 代翻。 */
    private static synchronized void spoolRequest(String src) {
        if (reqFile == null) return;
        try {
            java.io.FileWriter w = new java.io.FileWriter(reqFile, true);
            try {
                w.write(esc(src) + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "spool err " + t);
        }
    }

    /** 读模块 app 写回来的译文文件，灌进缓存。返回新写入条数。 */
    public static synchronized int pumpRemote() {
        if (resFile == null || !resFile.exists()) return 0;
        int n = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(resFile), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    String src = unesc(line.substring(0, t));
                    String dst = unesc(line.substring(t + 1));
                    if (src.length() == 0 || dst.length() == 0) continue;
                    put(src, dst);
                    SPOOLED.remove(src);
                    n++;
                }
            } finally {
                r.close();
            }
            if (n > 0) resFile.delete();
        } catch (Throwable t) {
            Log.w(TAG, "pump err " + t);
        }
        if (n > 0) Log.i(TAG, "remote translations in: " + n + " cache=" + CACHE.size());
        return n;
    }

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor p = pool;
        if (p == null) {
            synchronized (Translator.class) {
                p = pool;
                if (p == null) {
                    p = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<Runnable>(4096));
                    p.allowCoreThreadTimeOut(false);
                    pool = p;
                }
            }
        }
        return p;
    }

    // ---------------- 翻译本体 ----------------

    private static String translate(String src) throws Exception {
        final int maxSeg = Config.useBing() ? MAX_SEG_BING : MAX_SEG_MM;
        if (src.length() <= maxSeg) {
            String r = call(src);
            if (r != null) return r;
        }
        // 长文本按句子切段
        List<String> segs = split(src, maxSeg);
        if (segs.size() <= 1) return call(src);
        StringBuilder sb = new StringBuilder(src.length() + 32);
        for (String s : segs) {
            String t = call(s);
            sb.append(t != null ? t : s);
        }
        return sb.toString();
    }

    private static String call(String text) {
        if (Config.useBing()) {
            String r = callBing(text);
            if (r != null && r.length() > 0) return r;
        }
        return callMyMemory(text);
    }

    // ---------------- Bing 网页接口 ----------------

    private static synchronized boolean ensureBing() {
        if (bingToken != null && System.currentTimeMillis() - bingAt < 3300000L) return true;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BING_PAGE).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", BING_UA);
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "bing page http " + c.getResponseCode());
                return false;
            }
            StringBuilder sb = new StringBuilder(200000);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            br.close();
            String html = sb.toString();
            int i = html.indexOf("IG:\"");
            if (i < 0) { Log.w(TAG, "bing IG not found"); return false; }
            int e = html.indexOf('"', i + 4);
            if (e < 0) return false;
            bingIg = html.substring(i + 4, e);
            int k = html.indexOf("params_AbusePreventionHelper");
            if (k < 0) { Log.w(TAG, "bing abuse params not found"); return false; }
            int lb = html.indexOf('[', k);
            int rb = html.indexOf(']', lb);
            if (lb < 0 || rb < 0) return false;
            String[] parts = html.substring(lb + 1, rb).split(",");
            if (parts.length < 2) return false;
            bingKey = parts[0].trim();
            String tok = parts[1].trim();
            if (tok.startsWith("\"")) tok = tok.substring(1);
            if (tok.endsWith("\"")) tok = tok.substring(0, tok.length() - 1);
            bingToken = tok;
            bingAt = System.currentTimeMillis();
            Log.i(TAG, "bing token ok ig=" + bingIg.substring(0, Math.min(8, bingIg.length())) + "…");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "bing token err " + t);
            noteNet(t);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 无网络（DNS 解析失败 / 无网络可用）时置 true，供预翻译循环暂停等待 */
    private static volatile boolean netDown;
    public static boolean netDown() { return netDown; }
    static void setNetDown(boolean v) { netDown = v; }

    private static void noteNet(Throwable t) {
        if (t instanceof SecurityException
                || (t.getMessage() != null && t.getMessage().toLowerCase().contains("internet permission"))) {
            if (!noInet) Log.w(TAG, "no INTERNET permission in this process -> spool to module app");
            noInet = true;
            return;
        }
        if (t instanceof java.net.UnknownHostException
                || (t != null && t.getMessage() != null
                    && t.getMessage().contains("Unable to resolve host"))) {
            netDown = true;
        }
    }

    private static String callBing(String text) {
        for (int attempt = 0; attempt < 4; attempt++) {
            if (!ensureBing()) return null;
            HttpURLConnection c = null;
            try {
                String body = "fromLang=en&to=zh-Hans&text=" + URLEncoder.encode(text, "UTF-8")
                        + "&token=" + URLEncoder.encode(bingToken, "UTF-8")
                        + "&key=" + URLEncoder.encode(bingKey, "UTF-8");
                String u = String.format(BING_API, bingIg, BING_SEQ.incrementAndGet());
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("User-Agent", BING_UA);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setRequestProperty("Referer", BING_PAGE);
                c.setRequestProperty("Origin", "https://cn.bing.com");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
                int code = c.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "bing http " + code + " for " + short_(text));
                    if (code == 401 || code == 403) { bingToken = null; continue; }
                    if (code == 429) {
                        // Bing 按 IP 限流：退避后再试，别把条目当成失败丢掉
                        try { Thread.sleep(2500L + 2500L * attempt); } catch (InterruptedException ignore) { }
                        bingToken = null;
                        continue;
                    }
                    return null;
                }
                StringBuilder sb = new StringBuilder(4096);
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                char[] buf = new char[2048];
                int n;
                while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                br.close();
                String out = pickBing(sb.toString());
                if (out != null) { netDown = false; return out; }
                bingToken = null; // 解析失败多半是 token 失效
            } catch (Throwable t) {
                Log.w(TAG, "bing err " + t);
                noteNet(t);
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    /** 从 [{"translations":[{"text":"...","to":"zh-Hans"}]}] 取第一条译文。 */
    static String pickBing(String json) {
        if (json == null) return null;
        int t = json.indexOf("\"translations\":[");
        if (t < 0) return null;
        int k = json.indexOf("\"text\":\"", t);
        if (k < 0) return null;
        return readJsonString(json, k + 7);
    }

    /** 从引号处（quotePos 指向开引号）读一个 JSON 字符串。 */
    static String readJsonString(String json, int quotePos) {
        StringBuilder sb = new StringBuilder();
        for (int p = quotePos + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '"') return sb.toString();
            if (ch == '\\' && p + 1 < json.length()) {
                char e2 = json.charAt(++p);
                switch (e2) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'u':
                        if (p + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                                p += 4;
                            } catch (Exception ignore) { }
                        }
                        break;
                    default: sb.append(e2);
                }
            } else {
                sb.append(ch);
            }
        }
        return null;
    }

    private static final String SEP = " ||| ";

    /**
     * 批量翻译：把多条短文用 ||| 拼成一次请求（Bing 单次可吃 4000 字符），
     * 返回与入参一一对应的译文；分段数不匹配时返回 null（调用方回退逐条翻）。
     */
    static String[] callBingBatch(java.util.List<String> items) {
        if (items == null || items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(4096);
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(items.get(i).replace("|", "/"));
        }
        String raw = callBing(sb.toString());
        if (raw == null || raw.length() == 0) return null;
        netDown = false;
        String[] parts = raw.split("\\s*\\|\\|\\|\\s*");
        if (parts.length != items.size()) {
            Log.w(TAG, "batch mismatch " + parts.length + " vs " + items.size());
            return null;
        }
        return parts;
    }

    /**
     * 批量翻译入口。
     * 并行模式：把一批拆成若干组，分别丢给 bing / tencent / youdao / mymemory 同时翻，
     * 谁先返回算谁的；有组失败就整批回退 bing，再不行逐条 mymemory。
     * 串行模式：老路径（只用 bing），用于排查问题时对照。
     */
    public static String[] translateBatch(java.util.List<String> items) {
        if (items == null || items.isEmpty()) return null;
        Engines.E[] engines = Engines.ALL;
        if (!Config.parallel() || items.size() < 6 || engines.length < 2) {
            return callBingBatch(items);
        }

        // 每组 ~120 条：一次请求塞满，组数按需扩到 8，配合 8 线程并发
        // 按「字符总量 / 3000」和「条数 / 20」估算组数，最多 8 组；
        // 长句多的时候必须按字符切，否则单个请求被撑爆（腾讯会整批失败）。
        int totalChars = 0;
        for (String it : items) totalChars += it.length() + 4;
        // 组数：条数/12 与 字符/3000 取大，最多 8 组——短句也要摊满 8 条链路，
        // 否则一批 240 条会退化成 2 个请求互相等
        int groups = Math.min(6, Math.max(2, Math.max((totalChars + 2999) / 3000,
                                                      (items.size() + 11) / 12)));
        groups = Math.min(groups, items.size());
        if (groups < 2) return callBingBatch(items);
        final String[] out = new String[items.size()];
        final int target = Math.max(1, totalChars / groups);

        // 贪心均分：每组装到接近 target 字符就切
        final int[] bounds = new int[groups + 1];
        bounds[groups] = items.size();
        int acc = 0, g2 = 0;
        for (int i = 0; i < items.size() && g2 < groups - 1; i++) {
            acc += items.get(i).length() + 4;
            if (acc >= target) {
                bounds[++g2] = i + 1;
                acc = 0;
            }
        }
        while (g2 < groups - 1) bounds[++g2] = items.size();
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(groups);

        for (int g = 0; g < groups; g++) {
            final int from = bounds[g];
            final int to = bounds[g + 1];
            if (from >= to) { latch.countDown(); continue; }
            final int start = g % engines.length;   // 组号轮转起点，几条链路分摊负载
            pool().execute(new Runnable() {
                @Override
                public void run() {
                    java.util.List<String> sub = items.subList(from, to);
                    // 两轮：TLS 偶发 Chain validation failed / 限流都会让单次失败，
                    // 第一轮走完还缺的再补一轮（换引擎起点，别死磕同一个）
                    for (int pass = 0; pass < 2; pass++) {
                      if (pass > 0 && filled(out, from, to)) break;
                      if (pass > 0) { try { Thread.sleep(400L); } catch (InterruptedException ie) { } }
                      for (int k = 0; k < Engines.ALL.length; k++) {
                        Engines.E e = Engines.ALL[(start + k) % Engines.ALL.length];
                        if (!usable(e)) continue;
                        // youdao/mymemory 只能逐条：一组 40 条＝40 个串行请求，会拖死整批
                        if (!batchCapable(e) && sub.size() > 6) continue;
                        // 大模型只在「质量模式」开启、且组不大时用（实测 6~7 条/秒，不适合大组）
                        if ("llm".equals(e.name()) && (!Config.llm() || sub.size() > 20)) continue;
                        // Bing 用 ||| 拼批，条数一多分隔符会被翻掉（实测 120 条返回 123 段），>60 条交给腾讯
                        if ("bing".equals(e.name()) && sub.size() > 60) continue;
                        try {
                            String[] got = e.translate(new ArrayList<String>(sub));
                            noteEngine(e.name(), got != null && got.length == sub.size());
                            if (got != null && got.length == sub.size()) {
                                for (int i = 0; i < got.length; i++) {
                                    if (got[i] != null && got[i].length() > 0) out[from + i] = got[i];
                                }
                                if (filled(out, from, to)) break;
                            }
                        } catch (Throwable t) {
                            noteNet(t);
                            Log.w(TAG, "engine " + e.name() + " err " + t);
                        }
                      }
                    }
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        if (filled(out, 0, out.length)) return out;

        // 兜底 1：整批再走一次 bing
        String[] bing = callBingBatch(items);
        if (bing != null) {
            for (int i = 0; i < bing.length && i < out.length; i++) {
                if (out[i] == null || out[i].length() == 0) out[i] = bing[i];
            }
        }
        // 兜底 1.5：还没填上的交给大模型（质量最好，一次 ≤12 条）
        if (Config.llm()) {
            List<String> rest = new ArrayList<String>();
            List<Integer> idx = new ArrayList<Integer>();
            for (int i = 0; i < out.length; i++) {
                if (out[i] == null || out[i].length() == 0) { rest.add(items.get(i)); idx.add(i); }
            }
            for (int from = 0; from < rest.size() && from < 12; from += 12) {
                int to = Math.min(rest.size(), from + 12);
                try {
                    String[] got = Engines.LLM.translate(new ArrayList<String>(rest.subList(from, to)));
                    noteEngine("llm", got != null && got.length == to - from);
                    if (got != null && got.length == to - from) {
                        for (int k = 0; k < got.length; k++) {
                            if (got[k] != null && got[k].length() > 0) out[idx.get(from + k)] = got[k];
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "engine llm err " + t);
                }
            }
        }

        // 兜底 2：还没填上的逐条 mymemory
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null || out[i].length() == 0) out[i] = callMyMemory(items.get(i));
        }
        return filled(out, 0, out.length) ? out : null;
    }

    private static boolean filled(String[] a, int from, int to) {
        for (int i = from; i < to; i++) {
            if (a[i] == null || a[i].length() == 0) return false;
        }
        return true;
    }

    /** 引擎可用性：没网时全停，bing 需要 token 但会自己取。 */
    private static final java.util.Map<String, long[]> ENG_FAIL = new java.util.concurrent.ConcurrentHashMap<String, long[]>();

    /** 记一次引擎结果：连续失败 3 次冷却 120s（429/限流时会一路 429，必须掐断）。 */
    static void noteEngine(String name, boolean ok) {
        long[] st = ENG_FAIL.get(name);
        if (st == null) { st = new long[]{0, 0}; ENG_FAIL.put(name, st); }
        synchronized (st) {
            if (ok) { st[0] = 0; st[1] = 0; return; }
            st[0]++;
            if (st[0] >= 3) {
                st[1] = System.currentTimeMillis() + 120000L;
                st[0] = 0;
                Log.w(TAG, "engine " + name + " cooling down 120s");
            }
        }
    }

    /**
     * youdao / mymemory 只能逐条调，一组 40 条就是 40 个串行请求（youdao 单条约 2.7s），
     * 会把整批卡在 CountDownLatch 上。批量分组只用支持 list 的引擎（bing / tencent）。
     */
    private static boolean batchCapable(Engines.E e) {
        String n = e.name();
        return "bing".equals(n) || "tencent".equals(n);
    }

    private static boolean usable(Engines.E e) {
        long[] st = ENG_FAIL.get(e.name());
        if (st != null) {
            synchronized (st) {
                if (st[1] > System.currentTimeMillis()) return false;
            }
        }
        if (!canNet()) return false;
        return true;
    }

    /** 同步翻译（预热用，不经过线程池）。 */
    public static String translateSync(String src) {
        try {
            return translate(src);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 外部写入译文（预热用）。 */
    public static void put(String src, String dst) {
        lastActiveAt = System.currentTimeMillis();
        if (src == null || dst == null || dst.length() == 0) return;
        String old = CACHE.put(src, dst);
        if (old == null || !old.equals(dst)) appendDisk(src, dst);   // 已有同样译文就不重复写盘
        DONE.incrementAndGet();
        report(false);
        Listener l = listener;
        if (l != null) {
            try { l.onTranslated(src, dst); } catch (Throwable ignore) { }
        }
    }

    static String callMyMemory(String text) {
        HttpURLConnection c = null;
        try {
            String u = String.format(API, URLEncoder.encode(text, "UTF-8"));
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) FDTrans/1.0");
            int code = c.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "http " + code + " for " + short_(text));
                return null;
            }
            InputStream in = c.getInputStream();
            StringBuilder body = new StringBuilder(4096);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            char[] buf = new char[2048];
            int n;
            while ((n = br.read(buf)) > 0) body.append(buf, 0, n);
            br.close();
            return pickTranslated(body.toString());
        } catch (Throwable t) {
            Log.w(TAG, "net err " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 从 {"responseData":{"translatedText":"..."}} 里挖出译文，不引 JSON 库。 */
    static String pickTranslated(String json) {
        if (json == null) return null;
        int i = json.indexOf("\"translatedText\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        int q = json.indexOf('"', c + 1);
        if (q < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int p = q + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '"') return sb.toString();
            if (ch == '\\' && p + 1 < json.length()) {
                char e = json.charAt(++p);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'u':
                        if (p + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                                p += 4;
                            } catch (Exception ignore) { }
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(ch);
            }
        }
        return null;
    }

    private static List<String> split(String s, int max) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        while (start < s.length()) {
            int end = Math.min(start + max, s.length());
            if (end < s.length()) {
                int cut = -1;
                for (int i = end; i > start + max / 2; i--) {
                    char c = s.charAt(i - 1);
                    if (c == '.' || c == '!' || c == '?' || c == '\n' || c == ';' || c == ' ') {
                        cut = i;
                        break;
                    }
                }
                if (cut > 0) end = cut;
            }
            out.add(s.substring(start, end));
            start = end;
        }
        return out;
    }

    // ---------------- 磁盘缓存 ----------------

    private static void loadDisk() {
        if (loaded || cacheFile == null || !cacheFile.exists()) return;
        loaded = true;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile), "UTF-8"), 1 << 16);
            String line;
            while ((line = br.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                CACHE.put(unesc(line.substring(0, t)), unesc(line.substring(t + 1)));
            }
            Log.i(TAG, "cache loaded: " + CACHE.size());
        } catch (Throwable t) {
            Log.w(TAG, "cache load err " + t);
        } finally {
            close(br);
        }
    }

    private static synchronized void appendDisk(String k, String v) {
        try {
            if (cacheOut == null && cacheFile != null) {
                cacheOut = new FileOutputStream(cacheFile, true);
            }
            if (cacheOut != null) {
                OutputStream o = cacheOut;
                o.write((esc(k) + "\t" + esc(v) + "\n").getBytes("UTF-8"));
                o.flush();
            }
        } catch (Throwable t) {
            Log.w(TAG, "cache write err " + t);
        }
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    static String unesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(++i);
                if (e == 't') sb.append('\t');
                else if (e == 'n') sb.append('\n');
                else sb.append(e);
            } else sb.append(c);
        }
        return sb.toString();
    }

    private static String short_(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Throwable ignore) { }
    }

    /** 引擎确认翻不动（返回原文）的串，落盘持久化，避免每次启动都重新烧配额。 */
    private static final Set<String> DEAD = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    static void markDead(String src) {
        if (src == null || src.length() == 0) return;
        FAILED.add(src);
        if (!DEAD.add(src)) return;
        try {
            if (skipFile != null) {
                java.io.FileWriter w = new java.io.FileWriter(skipFile, true);
                w.write(esc(src) + "\n");
                w.close();
            }
        } catch (Throwable ignore) { }
    }

    static boolean dead(String src) {
        return src != null && DEAD.contains(src);
    }

    private static void loadSkip() {
        try {
            if (skipFile == null || !skipFile.exists()) return;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(skipFile));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) continue;
                String s = unesc(line);
                if (s != null && s.length() > 0) { DEAD.add(s); FAILED.add(s); }
            }
            r.close();
            Log.i(TAG, "skip list loaded: " + DEAD.size());
        } catch (Throwable t) {
            Log.w(TAG, "loadSkip err " + t);
        }
    }
}
