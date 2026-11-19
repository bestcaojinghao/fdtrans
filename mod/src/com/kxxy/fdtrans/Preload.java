package com.kxxy.fdtrans;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「不打开应用也能先翻一遍」：从安装包里抽出英文串 → 批量翻译 → 直接写进目标应用的译文缓存。
 *
 * 写完的效果：用户第一次打开那个应用时，界面文字几乎都命中缓存，立刻显示中文，
 * 不用等网络、不用把它留在后台。
 *
 * 通道仍然是 root（su -mm）+ 目标应用自己的 files 目录，跟 Bridge 用的是同一套思路，
 * 区别是这里的数据源不是「运行期截到的文字」，而是安装包里的字符串池。
 */
public final class Preload {

    public static final String ACTION = "com.kxxy.fdtrans.APRE";

    private static final String MOD_PREFS = "fdtrans-mod";

    private static volatile String runningPkg;
    private static volatile int done, total;
    private static volatile String phase = "";
    private static volatile PreloadService svc;
    private static volatile String lastNote = "";
    /** 排队预翻的包（「全部应用一起预翻」用），跑完一个接一个。 */
    private static final java.util.ArrayDeque<String> QUEUE = new java.util.ArrayDeque<String>();
    private static volatile int queueTotal;

    private Preload() {}

    static void attach(PreloadService s) { svc = s; }

    static void detach() { svc = null; }

    /** 上次没跑完（进程被杀/重启）留下来的待办。 */
    public static String pending(Context ctx) {
        try {
            String p = prefs(ctx).getString("preloadPkg", "");
            return p == null || p.length() == 0 ? null : p;
        } catch (Throwable t) { return null; }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(MOD_PREFS, Context.MODE_PRIVATE);
    }

    private static void markPending(Context ctx, String pkg) {
        try { prefs(ctx).edit().putString("preloadPkg", pkg == null ? "" : pkg).commit(); } catch (Throwable ignore) { }
    }

    /** 开机/进程重启后自动续跑。 */
    public static void resume(Context ctx) {
        String p = pending(ctx);
        if (p == null) return;
        if (runningPkg != null) return;
        android.util.Log.i("FDTrans", "preload resume " + p);
        try { ctx.startForegroundService(new Intent(ctx, PreloadService.class)); } catch (Throwable ignore) { }
    }

    public static boolean isRunning(String pkg) {
        return pkg != null && pkg.equals(runningPkg);
    }

    public static String runningPkg() { return runningPkg; }

    public static int done() { return done; }

    public static int total() { return total; }

    /** 上次预翻完成时写入的条数（0 = 从没跑完过）。 */
    public static int lastDone(Context ctx, String pkg) {
        if (pkg == null) return 0;
        try { return prefs(ctx).getInt("last_" + pkg, 0); } catch (Throwable t) { return 0; }
    }

    public static String phase() { return phase; }

    /** 给详情页用的即时状态包。 */
    public static void sendStatus(Context ctx, String pkg) {
        Intent i = new Intent(ACTION);
        i.setPackage(Config.SELF);
        i.putExtra("pkg", pkg);
        i.putExtra("running", pkg != null && pkg.equals(runningPkg));
        i.putExtra("done", done);
        i.putExtra("total", total);
        i.putExtra("phase", phase);
        try { ctx.sendBroadcast(i); } catch (Throwable ignore) { }
    }

    private static void cast(Context ctx, String pkg) {
        sendStatus(ctx, pkg);
        PreloadService s = svc;
        String note = done + "/" + total + "|" + phase;
        if (s != null && !note.equals(lastNote)) {
            lastNote = note;
            try { s.update(pkg, done, total, phase); } catch (Throwable ignore) { }
        }
    }

    /** 一次登记多个包，逐个预翻（单实例，跑完自动接下一个）。 */
    public static synchronized void enqueue(final Context ctx, java.util.Collection<String> pkgs) {
        QUEUE.clear();
        for (String p : pkgs) { if (p != null && p.length() > 0 && !QUEUE.contains(p)) QUEUE.add(p); }
        queueTotal = QUEUE.size();
        android.util.Log.i("FDTrans", "preload queue=" + queueTotal);
        startNext(ctx);
    }

    public static synchronized int queued() { return QUEUE.size(); }

    public static synchronized int queueTotal() { return queueTotal; }

    private static synchronized void startNext(Context ctx) {
        if (runningPkg != null) return;
        String p = QUEUE.poll();
        if (p == null) { queueTotal = 0; return; }
        start(ctx, p);
    }

    public static synchronized boolean start(final Context ctx, final String pkg) {
        if (runningPkg != null) return false;
        runningPkg = pkg;
        markPending(ctx, pkg);
        try { ctx.startForegroundService(new Intent(ctx, PreloadService.class)); } catch (Throwable ignore) { }
        done = 0;
        total = 0;
        phase = "扫描安装包…";
        cast(ctx, pkg);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    work(ctx, pkg);
                } catch (Throwable e) {
                    phase = "出错：" + e;
                    android.util.Log.w("FDTrans", "preload fail " + pkg + ": " + e);
                } finally {
                    cast(ctx, pkg);
                    runningPkg = null;
                    markPending(ctx, null);
                    PreloadService s = svc;
                    if (s != null) s.finish();
                    if (!QUEUE.isEmpty()) {
                        android.util.Log.i("FDTrans", "preload next, left=" + QUEUE.size());
                        startNext(ctx);
                    }
                }
            }
        }, "fdtrans-preload");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return true;
    }

    private static void work(Context ctx, String pkg) throws Exception {
        List<String> cand = ApkText.extract(ctx, pkg, uiOnlyPref(ctx));
        android.util.Log.i("FDTrans", "preload " + pkg + " candidates=" + cand.size());
        if (cand.isEmpty()) {
            phase = "没在安装包里找到可翻的英文";
            return;
        }
        Set<String> have = cachedKeys(pkg);
        List<String> todo = new ArrayList<String>();
        for (String s : cand) {
            if (have.contains(s)) continue;
            // 过滤规则已经在 ApkText.add() 里做过了；这里不再引用 Main（那个类带 Xposed 依赖，
            // 在我们自己的进程里会 NoClassDefFoundError）。
            todo.add(s);
        }
        total = todo.size();
        done = 0;
        phase = total == 0 ? "安装包里的文字都已经翻过了" : "翻译中";
        cast(ctx, pkg);
        android.util.Log.i("FDTrans", "preload " + pkg + " todo=" + total + " cached=" + have.size());
        if (total == 0) return;

        StringBuilder out = new StringBuilder();
        int inBatch = 0, chars = 0, saved = 0;
        List<String> batch = new ArrayList<String>();
        String uid = uidOf(pkg);

        for (String s : todo) {
            batch.add(s);
            chars += s.length() + 6;
            inBatch++;
            if (chars >= 6000 || inBatch >= 240) {
                String[] got = Translator.translateBatch(batch);
                if (got == null && batch.size() > 1) got = Translator.translateBatch(batch); // 一次重试
                if (got != null) {
                    for (int i = 0; i < batch.size() && i < got.length; i++) {
                        String src = batch.get(i);
                        String dst = got[i];
                        if (dst == null || dst.length() == 0) continue;
                        Translator.put(src, dst);
                        out.append(Translator.esc(src)).append('\t').append(Translator.esc(dst)).append('\n');
                        saved++;
                    }
                } else {
                    // 整批失败：逐条兜底
                    for (String src : batch) {
                        String dst = Translator.translateSync(src);
                        if (dst == null || dst.length() == 0) continue;
                        Translator.put(src, dst);
                        out.append(Translator.esc(src)).append('\t').append(Translator.esc(dst)).append('\n');
                        saved++;
                    }
                }
                done += batch.size();
                if (done % 200 < batch.size()) android.util.Log.i("FDTrans", "preload progress " + done + "/" + total);
                batch.clear();
                inBatch = 0;
                chars = 0;
                if (out.length() > 12000) {
                    flush(pkg, out.toString(), uid);
                    out.setLength(0);
                }
                // 每批之间让出 CPU；发烫时在这里挂起，降温后自动继续
                Thermal.pace(ctx, 200L);
                cast(ctx, pkg);
            }
        }
        if (!batch.isEmpty()) {
            String[] got = Translator.translateBatch(batch);
            if (got != null) {
                for (int i = 0; i < batch.size() && i < got.length; i++) {
                    String dst = got[i];
                    if (dst == null || dst.length() == 0) continue;
                    Translator.put(batch.get(i), dst);
                    out.append(Translator.esc(batch.get(i))).append('\t').append(Translator.esc(dst)).append('\n');
                    saved++;
                }
            }
            done += batch.size();
        }
        if (out.length() > 0) flush(pkg, out.toString(), uid);
        try { prefs(ctx).edit().putInt("last_" + pkg, saved).commit(); } catch (Throwable ignore) { }
        phase = "完成（新增 " + saved + " 条）";
        android.util.Log.i("FDTrans", "preload " + pkg + " attempted=" + done + " saved=" + saved);
    }

    /** 目标应用现有缓存的 key 集合（用来跳过已经翻过的）。 */
    private static Set<String> cachedKeys(String pkg) {
        Set<String> out = new HashSet<String>();
        String body = Scope.suMM("cat /data/user/0/" + pkg + "/files/fdtrans-cache.tsv 2>/dev/null");
        if (body == null) return out;
        for (String line : body.split("\n")) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            out.add(Translator.unesc(line.substring(0, t)));
        }
        return out;
    }

    private static String uidOf(String pkg) {
        String u = Scope.suMM("stat -c %u /data/user/0/" + pkg + "/files");
        if (u == null) return "0";
        u = u.trim();
        for (int i = 0; i < u.length(); i++) {
            if (u.charAt(i) < '0' || u.charAt(i) > '9') return "0";
        }
        return u.length() == 0 ? "0" : u;
    }

    private static void flush(String pkg, String payload, String uid) {
        String path = "/data/user/0/" + pkg + "/files/fdtrans-cache.tsv";
        String safe = payload.replace("'", "'\\''");
        String r = Scope.suMM("printf '%s' '" + safe + "' >> " + path
                + "; chown " + uid + ":" + uid + " " + path
                + "; chmod 660 " + path + "; echo FDOK");
        if (r == null || !r.contains("FDOK")) {
            android.util.Log.w("FDTrans", "preload write fail " + pkg + " -> " + r);
        } else {
            android.util.Log.i("FDTrans", "preload flushed " + pkg + " " + payload.length() + " bytes");
        }
    }
    /** 预翻是否包含 dex 里的长句：默认不（多半是异常/日志，翻了也用不上）。 */
    static boolean uiOnlyPref(android.content.Context ctx) {
        try {
            return prefs(ctx).getBoolean("uiOnly", true);
        } catch (Throwable t) {
            return true;
        }
    }

}
