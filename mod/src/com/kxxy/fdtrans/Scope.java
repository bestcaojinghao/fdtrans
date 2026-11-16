package com.kxxy.fdtrans;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读写 LSPosed 作用域（modules_config.db 的 scope 表）。
 *
 * 不依赖设备上有 sqlite3 命令：先把 db 拷到 /data/local/tmp，用 Android 自带的
 * SQLiteDatabase 打开改，再拷回去。root 命令只用 /system/bin 下的 cp / chmod / rm / kill。
 */
public final class Scope {

    public static final String MOD = "com.kxxy.fdtrans";
    private static final String TAG = "FDTrans";
    private static final String DB = "/data/adb/lspd/config/modules_config.db";
    /** 副本必须放在本应用自己的 cache 里：/data/local/tmp 是 shell 私有，普通 app 进不去。 */
    private static String tmp(Context ctx) {
        return ctx.getCacheDir().getAbsolutePath() + "/scope.db";
    }

    private Scope() {}

    /** 跑一条 root 命令，返回合并后的输出（失败返回 "ERR <异常>"）。 */
    public static String su(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            // 授权框没点的话 su 会一直挂着，不能把界面线程也拖死
            if (!p.waitFor(9, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                Log.w(TAG, "su timeout: " + cmd);
                return "ERR timeout";
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.w(TAG, "su failed: " + t);
            return "ERR " + t;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** root 但进入全局 mount 命名空间（跨 app 读文件必须，-mm 是 Magisk su 的开关）。 */
    public static String suMM(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-mm", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            if (!p.waitFor(9, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                Log.w(TAG, "suMM timeout: " + cmd);
                return "ERR timeout";
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "ERR " + t;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** 把 lspd 配置库（含 -wal/-shm，否则 wal 模式只拷主文件会读到旧数据）拷到可读位置。 */
    public static boolean pull(Context ctx) {
        String T = tmp(ctx);
        int uid = android.os.Process.myUid();
        String out = su("rm -f " + T + " " + T + "-wal " + T + "-shm; "
                + "cp " + DB + " " + T + "; "
                + "[ -f " + DB + "-wal ] && cp " + DB + "-wal " + T + "-wal; "
                + "[ -f " + DB + "-shm ] && cp " + DB + "-shm " + T + "-shm; "
                + "chmod 666 " + T + "* 2>/dev/null; "
                + "chown " + uid + ":" + uid + " " + T + "* 2>/dev/null; echo PULLED");
        Log.i(TAG, "scope pull: " + out);
        return out.contains("PULLED");
    }

    /** 读当前作用域；失败返回 null（区分"空集合"和"读不到"）。 */
    public static Set<String> read(Context ctx) {
        if (!pull(ctx)) return null;
        Set<String> out = new LinkedHashSet<String>();
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(tmp(ctx), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery(
                    "select app_pkg_name from scope where module_pkg_name=? order by app_pkg_name",
                    new String[]{MOD});
            while (c.moveToNext()) {
                String s = c.getString(0);
                if (s != null && s.length() > 0) out.add(s);
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "scope read failed: " + t);
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    /** 用给定集合整体替换本模块的作用域，写完拷回并重启 lspd。 */
    public static boolean apply(Context ctx, Set<String> want) {
        // 先停 lspd：它是 db 的唯一写入者，边写边改会把 wal 弄坏
        su("rm -f /data/adb/lspd/monitor; kill -9 $(pidof lspd) 2>/dev/null; echo KILLED");
        if (!pull(ctx)) return false;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(tmp(ctx), null, SQLiteDatabase.OPEN_READWRITE);
            db.beginTransaction();
            db.execSQL("delete from scope where module_pkg_name=?", new Object[]{MOD});
            for (String pkg : want) {
                if (pkg == null) continue;
                pkg = pkg.trim();
                if (pkg.length() < 2 || pkg.indexOf('\'') >= 0) continue;
                db.execSQL("insert or replace into scope(module_pkg_name,app_pkg_name,user_id) values(?,?,0)",
                        new Object[]{MOD, pkg});
            }
            db.setTransactionSuccessful();
            db.endTransaction();
            // 把 wal 合并进主文件，拷回去时只带主文件也不会丢改动
            Cursor ck = db.rawQuery("pragma wal_checkpoint(truncate)", null);
            if (ck != null) { ck.moveToFirst(); ck.close(); }
        } catch (Throwable t) {
            Log.w(TAG, "scope write failed: " + t);
            return false;
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
        String T = tmp(ctx);
        String out = su("cp " + T + " " + DB + " && chmod 660 " + DB + " && chown 0:0 " + DB
                + " && rm -f " + DB + "-wal " + DB + "-shm"
                + " && rm -f " + T + " " + T + "-wal " + T + "-shm && echo PUSHED");
        Log.i(TAG, "scope push: " + out);
        return out.contains("PUSHED");
    }

    /** 杀掉 lspd 让新作用域生效；service.d 里的守卫会在 15-22 秒内把它拉回来。 */
    public static void restartLspd() {
        String out = su("rm -f /data/adb/lspd/monitor; kill -9 $(pidof lspd); echo KILLED");
        Log.i(TAG, "lspd restart: " + out);
    }

    public static String shortName(String pkg) {
        File f = new File(pkg);
        return f.getName();
    }
}
