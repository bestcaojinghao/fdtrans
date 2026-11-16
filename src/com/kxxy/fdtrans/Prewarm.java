package com.kxxy.fdtrans;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 预翻译：把 F-Droid 本地索引里的英文条目一次性翻好写进缓存。
 * 跑在 F-Droid 进程内（LSPosed 注入），所以能直接读它自己的 fdroid_db。
 *
 * 模式：
 *   1 = 应用名 + 摘要（列表页能看到的东西，约 26 万字符，几分钟）
 *   2 = 上面 + 描述（详情页长文本，量级大，几小时）
 */
public final class Prewarm {

    private static final String TAG = "FDTrans";
    private static final String DB_NAME = "fdroid_db";

    private static volatile boolean running;
    private static volatile int done;
    private static volatile int total;
    private static volatile String phase = "";

    private Prewarm() {}

    public static boolean isRunning() {
        return running;
    }

    public static int done() {
        return done;
    }

    public static int total() {
        return total;
    }

    public static String phase() {
        return phase;
    }

    /**
     * @param mode 1=名称+摘要，2=再加描述
     */
    public static void start(final Context ctx, final int mode) {
        if (running) {
            Log.i(TAG, "prewarm already running: " + done + "/" + total);
            return;
        }
        running = true;
        done = 0;
        total = 0;
        phase = "读取本地索引";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runJobs(ctx, mode);
                } catch (Throwable e) {
                    Log.w(TAG, "prewarm err " + e);
                } finally {
                    running = false;
                    phase = "完成";
                }
            }
        }, "fdtrans-prewarm");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void runJobs(Context ctx, int mode) {
        List<String> jobs = collect(ctx, mode);
        // 已在缓存里的先剔掉，省得白占批次
        List<String> todo = new ArrayList<String>();
        for (String s : jobs) {
            if (Translator.get(s) == null) todo.add(s);
        }
        jobs = todo;
        total = jobs.size();
        if (total == 0) {
            Log.i(TAG, "prewarm: nothing to do (all cached)");
            return;
        }
        // 组装批次：单批不超过 3000 字符 / 60 条
        List<List<String>> batches = new ArrayList<List<String>>();
        List<String> cur = new ArrayList<String>();
        int curLen = 0;
        for (String s : jobs) {
            int add = s.length() + 6;
            if (!cur.isEmpty() && (curLen + add > 3000 || cur.size() >= 60)) {
                batches.add(cur);
                cur = new ArrayList<String>();
                curLen = 0;
            }
            cur.add(s);
            curLen += add;
        }
        if (!cur.isEmpty()) batches.add(cur);

        Log.i(TAG, "prewarm start mode=" + mode + " items=" + total + " batches=" + batches.size());
        final long t0 = System.currentTimeMillis();
        final int workers = 5;
        final java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] ts = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            ts[w] = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        int i = idx.getAndIncrement();
                        if (i >= batches.size()) return;
                        List<String> b = batches.get(i);
                        String[] out = null;
                        try {
                            out = Translator.translateBatch(b);
                        } catch (Throwable e) {
                            Log.w(TAG, "batch err " + e);
                        }
                        if (out == null) {
                            // 回退逐条
                            for (String src : b) {
                                if (Translator.get(src) != null) { done++; continue; }
                                String t = Translator.translateSync(src);
                                if (t != null && t.length() > 0 && !t.equalsIgnoreCase(src)) {
                                    Translator.put(src, t);
                                }
                                done++;
                            }
                        } else {
                            for (int k = 0; k < b.size(); k++) {
                                String t = out[k].trim();
                                if (t.length() > 0 && !t.equalsIgnoreCase(b.get(k))) {
                                    Translator.put(b.get(k), t);
                                }
                                done++;
                            }
                        }
                        int d = done;
                        if (d % 200 < b.size()) {
                            long ms = System.currentTimeMillis() - t0;
                            Log.i(TAG, "prewarm " + d + "/" + total
                                    + " " + (ms > 0 ? (d * 1000L / ms) + " 条/秒" : "")
                                    + " cache=" + Translator.doneCount());
                            if (ctx != null) {
                                ctx.getSharedPreferences(Config.PREFS, 0).edit()
                                        .putInt("prewarmDone", d).commit();
                            }
                        }
                    }
                }
            }, "fdtrans-prewarm-" + w);
            ts[w].setPriority(Thread.MIN_PRIORITY);
            ts[w].start();
        }
        for (Thread th : ts) {
            try {
                th.join();
            } catch (InterruptedException ignore) {
            }
        }
        long ms = System.currentTimeMillis() - t0;
        Log.i(TAG, "prewarm done " + done + "/" + total + " in " + (ms / 1000) + "s, cache="
                + Translator.doneCount());
        if (ctx != null) {
            ctx.getSharedPreferences(Config.PREFS, 0).edit()
                    .putInt("prewarmDone", done)
                    .putLong("prewarmAt", System.currentTimeMillis())
                    .commit();
        }
    }

    /** 从 fdroid_db 里收集要翻的英文串。 */
    private static List<String> collect(Context ctx, int mode) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            String path = ctx == null
                    ? "/data/user/0/org.fdroid.fdroid/databases/" + DB_NAME
                    : ctx.getDatabasePath(DB_NAME).getAbsolutePath();
            Log.i(TAG, "prewarm db = " + path);
            db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("select localizedName, localizedSummary from AppMetadata", null);
            while (c.moveToNext()) {
                for (int i = 0; i < 2; i++) {
                    String s = c.isNull(i) ? null : c.getString(i);
                    if (s == null) continue;
                    s = s.trim();
                    if (s.length() < 2 || s.length() > 1200) continue;
                    if (!Main.shouldTranslate(s)) continue;
                    if (seen.add(s)) out.add(s);
                }
            }
            if (mode >= 2) {
                c.close();
                c = null;
                // 描述是长文本，按 1000 字上限切段后逐段翻
                c = db.rawQuery("select description from AppMetadata", null);
                while (c.moveToNext()) {
                    String s = c.isNull(0) ? null : c.getString(0);
                    if (s == null) continue;
                    s = s.trim();
                    if (s.length() < 2) continue;
                    for (String seg : s.split("\n")) {
                        seg = seg.trim();
                        if (seg.length() < 6 || seg.length() > 1200) continue;
                        if (!Main.shouldTranslate(seg)) continue;
                        if (seen.add(seg)) out.add(seg);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "prewarm collect err " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) { }
            if (db != null) try { db.close(); } catch (Throwable ignore) { }
        }
        return out;
    }
}
