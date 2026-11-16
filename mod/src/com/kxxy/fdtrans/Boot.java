package com.kxxy.fdtrans;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 模块更新后自动拉起后台代翻服务，省得用户每次手动点按钮。
 */
public class Boot extends BroadcastReceiver {
    private static final String TAG = "FDTrans";

    @Override
    public void onReceive(Context ctx, Intent i) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("fdtrans", Context.MODE_PRIVATE);
            if (!sp.getBoolean("bridgeAuto", true)) return;
            Intent s = new Intent(ctx, Bridge.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s);
            else ctx.startService(s);
            Log.i(TAG, "boot receiver started bridge: " + (i == null ? "null" : i.getAction()));
        } catch (Throwable t) {
            Log.w(TAG, "boot start bridge err " + t);
        }
    }
}
