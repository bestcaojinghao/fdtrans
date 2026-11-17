package com.kxxy.fdtrans;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

/**
 * 温控闸门：翻译是纯 CPU + 网络的工作，设备发烫时继续跑只会把热变成卡顿。
 * 这里只读 sysfs / 系统广播，不引 Xposed，模块进程与 hook 进程都能用。
 */
public final class Thermal {

    /** 本机空闲时 CPU 各核就有 55–60℃，所以暂停阈值必须高于这个基线 */
    private static final int HOT_SOC_C = 68;
    /** 降到这个温度才恢复，避免在阈值上反复抖 */
    private static final int COOL_SOC_C = 62;
    private static final int HOT_BATT_C = 42;
    private static final int COOL_BATT_C = 39;
    private static volatile boolean paused;
    /** 暂停期间每 3 秒复检一次，最长挂 5 分钟后强行继续（避免永远卡住） */
    private static final int PAUSE_STEP_MS = 3000;
    private static final int PAUSE_MAX_STEPS = 100;

    private static volatile boolean enabled = true;
    private static volatile long tempAt;
    private static volatile int tempC = -1;

    private Thermal() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    /** 所有 thermal_zone 里的最高温度（℃），5 秒缓存。 */
    public static int socTempC() {
        long now = System.currentTimeMillis();
        if (now - tempAt < 5000) return tempC;
        int best = -1;
        try {
            java.io.File dir = new java.io.File("/sys/class/thermal");
            String[] names = dir.list();
            if (names != null) {
                for (String n : names) {
                    if (!n.startsWith("thermal_zone")) continue;
                    String s = read(new java.io.File(dir, n + "/temp"));
                    if (s == null) continue;
                    int v;
                    try { v = Integer.parseInt(s.trim()); } catch (Throwable ignore) { continue; }
                    if (v <= 0) continue;
                    if (v > 1000) v /= 1000;
                    if (v > best) best = v;
                }
            }
        } catch (Throwable ignore) { }
        tempC = best;
        tempAt = now;
        return best;
    }

    public static int batteryTempC(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            return t > 0 ? t / 10 : -1;
        } catch (Throwable ignore) {
            return -1;
        }
    }

    public static boolean screenOn(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignore) {
            return true;
        }
    }

    public static boolean hot(Context ctx) {
        if (!enabled) { paused = false; return false; }
        int soc = socTempC();
        int b = ctx == null ? -1 : batteryTempC(ctx);
        if ((soc > 0 && soc >= HOT_SOC_C) || (b > 0 && b >= HOT_BATT_C)) {
            paused = true;
        } else if (soc > 0 && soc <= COOL_SOC_C && (b <= 0 || b <= COOL_BATT_C)) {
            paused = false;
        }
        return paused;
    }

    /**
     * 批与批之间的节流点。
     * 正常：睡 baseMs 后返回；发烫：每 3 秒复检，降温才继续。
     * 息屏不暂停（预翻本来就是要在没人用时跑完），只是把节奏放慢。
     */
    public static void pace(Context ctx, long baseMs) {
        sleep(baseMs);
        if (!enabled) return;
        if (!hot(ctx)) return;
        android.util.Log.i("FDTrans", "thermal pause: soc=" + socTempC() + "C batt=" + batteryTempC(ctx) + "C");
        for (int i = 0; i < PAUSE_MAX_STEPS; i++) {
            sleep(PAUSE_STEP_MS);
            if (!hot(ctx)) {
                android.util.Log.i("FDTrans", "thermal resume: soc=" + socTempC() + "C");
                return;
            }
        }
        android.util.Log.w("FDTrans", "thermal pause timeout, continue anyway");
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String read(java.io.File f) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n).trim() : null;
        } catch (Throwable ignore) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }
}
