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

    private static File reqFile, resFile;
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
        try {
            java.net.CookieHandler.setDefault(new java.net.CookieManager());
        } catch (Throwable ignore) { }
        applyClearIfNeeded(dir);
        loadDisk();
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
        if (src == null || CACHE.containsKey(src) || FAILED.contains(src)) return;
        if (!PENDING.add(src)) return;
        SEEN.incrementAndGet();
        if (!canNet()) {
            PENDING.remove(src);
            if (SPOOLED.add(src)) spoolRequest(src);
            return;
        }
        pool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String out = translate(src);
                    if (out != null && out.length() > 0 && !out.equalsIgnoreCase(src)) {
                        CACHE.put(src, out);
                        appendDisk(src, out);
                        DONE.incrementAndGet();
                        lastActiveAt = System.currentTimeMillis();
                        report(false);
                        Listener l = listener;
                        if (l != null) {
                            try { l.onTranslated(src, out); } catch (Throwable ignore) { }
                        }
                    } else {
                        FAILED.add(src);
                    }
                } catch (Throwable t) {
                    FAILED.add(src);
                    Log.w(TAG, "translate fail: " + short_(src) + " / " + t);
                } finally {
                    PENDING.remove(src);
                }
            }
        });
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
                    p = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
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
    public static String[] translateBatch(java.util.List<String> items) {
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

    private static String callMyMemory(String text) {
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
}
