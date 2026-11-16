package com.kxxy.fdtrans;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

/**
 * 打开本模块界面时顺便把后台代翻服务拉起来（比每次手动点按钮省事）。
 */
public class App extends Application {
    private static final String TAG = "FDTrans";

    @Override
    public void onCreate() {
        super.onCreate();
        // 上次没跑完的「安装包预翻」接着跑（进程被杀/开机后自动续）
        try { Preload.resume(this); } catch (Throwable t) { Log.w(TAG, "resume err " + t); }
        try {
            android.content.SharedPreferences sp = getSharedPreferences("fdtrans", MODE_PRIVATE);
            if (!sp.getBoolean("bridgeAuto", true)) return;
            // 只在这里起循环线程；服务由前台界面（Settings.onResume）启动。
            // 后台进程直接 startForegroundService 会被系统拒绝（Background start not allowed），
            // 而且会消耗掉这一次的启动配额，导致随后界面里再启动也被拒。
            Bridge.startLoop(this);
            Log.i(TAG, "App.onCreate started bridge loop (service left to foreground UI)");
        } catch (Throwable t) {
            Log.w(TAG, "App.onCreate bridge err " + t);
        }
    }
}
