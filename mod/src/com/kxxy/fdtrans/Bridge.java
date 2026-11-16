package com.kxxy.fdtrans;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 代翻服务：模块自己这个 app 有 INTERNET 权限，替那些「没有联网权限 / 后台没网」的目标应用做翻译。
 *
 * 通道是目标应用自己私有目录里的两个文件（模块用 su 读写，因为跨 app 目录访问被 SELinux 拦住）：
 *   files/fdtrans-req.tsv  目标写入待翻文本（每行一条，TSV 转义）
 *   files/fdtrans-res.tsv  模块追加「原文<TAB>译文」，目标自己读走并删除
 */
public class Bridge extends Service {
    static final String TAG = "FDTrans";
    static final String CH = "fdtrans-bridge";
    static volatile boolean running;

    @Override
    public IBinder onBind(Intent i) { return null; }

    private static Context loopCtx;
    private static File loopDir;

    /** 由 Service 或 App 进程直接调用：起一条常驻线程跑代翻循环。 */
    public static synchronized void startLoop(Context ctx) {
        if (running) return;
        running = true;
        loopCtx = ctx.getApplicationContext();
        appCtxCache = loopCtx;
        loopDir = ctx.getFilesDir();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() { loop(); }
        }, "fdtrans-bridge");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "bridge loop started");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        startLoop(this);
        Log.i(TAG, "bridge service started");
    }

    private void startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(
                        CH, "后台代翻", NotificationManager.IMPORTANCE_MIN);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(c);
            }
            Intent open = new Intent(this, Settings.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_menu_edit)
             .setContentTitle("实时翻译：后台代翻中")
             .setContentText("为没有联网权限的应用提供翻译，点开可停止")
             .setContentIntent(pi)
             .setOngoing(true);
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, n);
            }
            Log.i(TAG, "bridge fg ok sdk=" + Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            Log.w(TAG, "foreground err " + t);
            try { startForeground(1, new Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("实时翻译后台").build()); }
            catch (Throwable t2) { Log.w(TAG, "foreground fallback err " + t2); }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Log.i(TAG, "bridge service stopped");
        super.onDestroy();
    }

    private static void loop() {
        Translator.init(loopCtx, loopDir);
        Set<String> pkgs = null;
        long scopeAt = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (pkgs == null || now - scopeAt > 60000) {
                    pkgs = Scope.read(loopCtx);
                    scopeAt = now;
                    Log.i(TAG, "bridge scope: " + (pkgs == null ? "null" : pkgs.size() + ""));
                }
                ticks++;
                checkPreloadTrigger();
                if (pkgs != null && ticks % 10 == 0) Log.i(TAG, "bridge tick " + ticks + " running=" + running);
                if (pkgs != null) {
                    for (String pkg : pkgs) {
                        try { pumpOne(pkg); } catch (Throwable t) { Log.w(TAG, "bridge " + pkg + " err " + t); }
                    }
                }
                Thread.sleep(1500L);
            } catch (Throwable t) {
                Log.w(TAG, "bridge loop err " + t);
                try { Thread.sleep(3000L); } catch (Throwable ignore) { }
            }
        }
    }

    /** 读一个目标应用的请求文件，翻完写回结果文件。 */
    private static int ticks;
    private static long lastProbeAt;

    /** 外部触发：往模块自己的 files 目录写 preload-now.txt（内容=包名）即可启动一次预翻。 */
    private static void checkPreloadTrigger() {
        try {
            if (loopCtx == null) return;
            java.io.File f = new java.io.File(loopCtx.getFilesDir(), "preload-now.txt");
            if (!f.exists()) return;
            String body = readSmall(f);
            f.delete();
            if (body == null) return;
            String pkg = body.trim();
            int nl = pkg.indexOf('\n');
            if (nl > 0) pkg = pkg.substring(0, nl).trim();
            Log.i(TAG, "external preload trigger: [" + pkg + "]");
            if (pkg.length() > 0) Preload.start(loopCtx, pkg);
        } catch (Throwable t) {
            Log.w(TAG, "preload trigger err " + t);
        }
    }

    private static String readSmall(java.io.File f) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    private static void pumpOne(String pkg) throws Exception {
        String base = "/data/user/0/" + pkg + "/files";
        String req = base + "/fdtrans-req.tsv";
        String res = base + "/fdtrans-res.tsv";
        boolean probe = false;
        long now = System.currentTimeMillis();
        if (now - lastProbeAt > 30000L) { lastProbeAt = now; probe = true; }
        if (probe) {
            Log.i(TAG, "bridge probe id=" + Scope.suMM("id") + " ls=" + Scope.suMM("ls /data/user/0 | wc -l"));
        }
        String out = Scope.suMM("if [ -s " + req + " ]; then cat " + req + "; fi");
        if (probe) {
            String head = out == null ? "null" : out.substring(0, Math.min(50, out.length()));
            Log.i(TAG, "bridge probe req=[" + head + "]");
        }
        if (out == null || out.startsWith("ERR ")) { Log.w(TAG, "bridge " + pkg + " su err: " + out); return; }
        if (out.trim().length() == 0) return;
        Log.i(TAG, "bridge " + pkg + " req bytes=" + out.length());

        java.util.List<String> srcs = new ArrayList<String>();
        for (String line : out.split("\n")) {
            if (line.length() == 0) continue;
            String src = Translator.unesc(line.trim());
            if (src.length() == 0) continue;
            if (Translator.get(src) != null) continue;
            if (!srcs.contains(src)) srcs.add(src);
            if (srcs.size() >= 60) break;
        }
        if (srcs.isEmpty()) { clearReq(pkg, base, req); return; }

        int wrote = 0;
        java.util.List<String> chunk = new ArrayList<String>();
        int chunkLen = 0;
        for (String one : srcs) {
            if (!chunk.isEmpty() && (chunkLen + one.length() > 1200 || chunk.size() >= 12)) {
                wrote += flushChunk(pkg, base, res, chunk);
                chunk = new ArrayList<String>();
                chunkLen = 0;
            }
            chunk.add(one);
            chunkLen += one.length() + 6;
        }
        if (!chunk.isEmpty()) wrote += flushChunk(pkg, base, res, chunk);
        Log.i(TAG, "bridge " + pkg + " wrote=" + wrote + " of " + srcs.size());
        if (wrote > 0) {
            clearReq(pkg, base, req);
            if (appCtxCache != null) {
                Intent upd = new Intent(Config.STAT);
                upd.setPackage(Config.SELF);
                appCtxCache.sendBroadcast(upd);
            }
        } else {
            Log.w(TAG, "bridge " + pkg + " all failed, keep req for retry");
        }
    }

    private static Context appCtxCache;


    /** 翻一块并追加写回结果文件，返回写入条数。 */
    private static int flushChunk(String pkg, String base, String res, java.util.List<String> chunk) {
        StringBuilder sb = new StringBuilder();
        String[] got = Translator.translateBatch(chunk);
        Log.i(TAG, "bridge " + pkg + " chunk=" + chunk.size() + " got=" + (got == null ? "null" : got.length));
        if (got == null && chunk.size() > 1) {
            // 整块被拒（体积/限流），对半拆开重试
            int mid = chunk.size() / 2;
            int w = flushChunk(pkg, base, res, new ArrayList<String>(chunk.subList(0, mid)));
            w += flushChunk(pkg, base, res, new ArrayList<String>(chunk.subList(mid, chunk.size())));
            return w;
        }
        for (int i = 0; i < chunk.size(); i++) {
            String src = chunk.get(i);
            String dst = (got != null && i < got.length && got[i] != null && got[i].length() > 0)
                    ? got[i] : Translator.translateSync(src);
            if (dst == null || dst.length() == 0) continue;
            sb.append(Translator.esc(src)).append('\t').append(Translator.esc(dst)).append('\n');
        }
        if (sb.length() == 0) return 0;
        String uid = Scope.suMM("stat -c %u " + base + " 2>/dev/null").trim();
        String payload = sb.toString().replace("'", "'\\''");
        Scope.suMM("printf '%s' '" + payload + "' >> " + res
                + "; chown " + uid + ":" + uid + " " + res + "; chmod 660 " + res);
        return sb.toString().split("\n").length;
    }

    private static void clearReq(String pkg, String base, String req) {
        Scope.suMM(": > " + req);
    }
}
