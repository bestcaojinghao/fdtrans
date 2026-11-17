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

/**
 * 预翻的宿主服务：让「不打开目标应用也能翻一遍」这件事在进程被系统回收后还能继续——
 * 前台服务 + 常驻通知，把长任务钉在进程里，并且把进度画在通知上。
 */
public class PreloadService extends Service {

    private static final String CH = "fdtrans-preload";
    private static final int NID = 2;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        Preload.attach(this);
        String pkg = Preload.runningPkg();
        if (pkg == null) pkg = Preload.pending(this);
        if (pkg != null && Preload.runningPkg() == null) Preload.start(this, pkg);
        android.util.Log.i("FDTrans", "preload service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Preload.runningPkg() == null) {
            String pkg = intent != null && intent.getStringExtra("pkg") != null
                    ? intent.getStringExtra("pkg") : Preload.pending(this);
            if (pkg != null) Preload.start(this, pkg);
            else stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Preload.detach();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "安装包预翻译", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Intent open = new Intent(this, Settings.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("正在预翻译安装包文字")
                .setContentText("准备中…")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        startTyped(NID, n);
    }

    /** Android 10+ 必须带 type 才能成为真正的前台服务（否则 isForeground=false，进程随时被杀）。 */
    private void startTyped(int id, Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(id, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(id, n);
            }
            android.util.Log.i("FDTrans", "preload fg ok id=" + id + " sdk=" + Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            android.util.Log.w("FDTrans", "preload fg err " + t);
            startForeground(id, n);
        }
    }

    /** 由 Preload 每批调用，刷新通知进度。 */
    public void update(String pkg, int done, int total, String phase) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setContentTitle("正在预翻译：" + pkg)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true)
                .setContentText((phase == null ? "" : phase + "  ") + done + " / " + total);
        if (total > 0) b.setProgress(total, Math.min(done, total), false);
        try { nm.notify(NID, b.build()); } catch (Throwable ignore) { }
    }

    public void finish() {
        try { stopForeground(true); } catch (Throwable ignore) { }
        stopSelf();
    }
}
