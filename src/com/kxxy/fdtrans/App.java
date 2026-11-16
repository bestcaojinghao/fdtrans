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
        try {
            android.content.SharedPreferences sp = getSharedPreferences("fdtrans", MODE_PRIVATE);
            if (!sp.getBoolean("bridgeAuto", true)) return;
            Bridge.startLoop(this);
            try {
                Intent i = new Intent(this, Bridge.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "App.onCreate started bridge");
        } catch (Throwable t) {
            Log.w(TAG, "App.onCreate bridge err " + t);
        }
    }
}
