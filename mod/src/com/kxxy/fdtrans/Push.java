package com.kxxy.fdtrans;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Set;

/** 把配置推送到所有已加入作用域的进程（每个进程各自落盘一份 prefs）。 */
public final class Push {

    private static final String TAG = "FDTrans";

    private Push() {}

    public static void bool(Context ctx, Set<String> pkgs, String key, boolean v) {
        for (String p : pkgs) send(ctx, p, key, v, null);
    }

    public static void num(Context ctx, Set<String> pkgs, String key, long v) {
        for (String p : pkgs) send(ctx, p, key, null, Long.valueOf(v));
    }

    public static void str(Context ctx, Set<String> pkgs, String key, String v) {
        for (String p : pkgs) {
            try {
                Intent i = new Intent(Config.ACTION);
                i.setPackage(p);
                i.putExtra(key, v);
                ctx.sendBroadcast(i);
                Log.i(TAG, "push " + key + " -> " + p);
            } catch (Throwable t) {
                Log.w(TAG, "push failed " + p + ": " + t);
            }
        }
    }

    private static void send(Context ctx, String pkg, String key, Boolean b, Long l) {
        try {
            Intent i = new Intent(Config.ACTION);
            i.setPackage(pkg);
            if (b != null) i.putExtra(key, b.booleanValue());
            if (l != null) i.putExtra(key, l.longValue());
            ctx.sendBroadcast(i);
            Log.i(TAG, "push " + key + " -> " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "push failed " + pkg + ": " + t);
        }
    }
}
