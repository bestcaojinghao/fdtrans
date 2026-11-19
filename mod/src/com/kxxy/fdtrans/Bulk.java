package com.kxxy.fdtrans;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量导出 / 导入。
 *
 * 用法（不需要应用自己在跑，也不需要联网）：
 *   1. 详情页点「导出英文清单」→ /sdcard/Download/fdtrans-export/<pkg>.src.tsv（每行一条英文）
 *   2. 拿这个文件去任何地方批量翻（大模型、网页翻译、脚本都行），
 *      产出一份同样行数的 <pkg>.res.tsv，格式：原文 \t 译文
 *   3. 放回同一个目录，点「导入译文」→ 直接写进目标应用的译文缓存
 *
 * 这样一次能翻几千上万条，不受运行时逐条翻译的吞吐限制。
 */
public final class Bulk {

    private static final String TAG = "FDTrans";
    public static final String DIR = "/sdcard/Download/fdtrans-export";

    private Bulk() {}

    /** 导出：返回 [导出条数, 已缓存跳过, 文件路径]；失败返回 null。 */
    public static Object[] export(Context ctx, String pkg) {
        try {
            List<String> all = ApkText.extract(ctx, pkg, true);
            if (all == null) all = new ArrayList<String>();
            Set<String> have = cached(ctx, pkg);
            List<String> todo = new ArrayList<String>();
            for (String s : all) {
                if (!have.contains(s)) todo.add(s);
            }
            StringBuilder sb = new StringBuilder(todo.size() * 48 + 64);
            for (String s : todo) sb.append(Translator.esc(s)).append('\n');

            File tmp = new File(ctx.getCacheDir(), "export-" + pkg + ".tsv");
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
            String dest = DIR + "/" + pkg + ".src.tsv";
            Scope.suMM("mkdir -p " + DIR + "; cp '" + tmp.getAbsolutePath() + "' '" + dest
                    + "'; chmod 666 '" + dest + "'; echo BULKOK");
            Log.i(TAG, "bulk export " + pkg + " todo=" + todo.size() + " cached=" + have.size()
                    + " -> " + dest);
            return new Object[]{todo.size(), have.size(), dest};
        } catch (Throwable t) {
            Log.w(TAG, "bulk export failed " + pkg + ": " + t);
            return null;
        }
    }

    /** 导入：读 <pkg>.res.tsv，返回 [写入条数, 总行数]；失败返回 null。 */
    public static int[] importRes(Context ctx, String pkg) {
        try {
            String path = DIR + "/" + pkg + ".res.tsv";
            String body = Scope.suMM("if [ -s '" + path + "' ]; then cat '" + path + "'; fi");
            if (body == null || body.trim().length() == 0) {
                Log.w(TAG, "bulk import: empty or missing " + path);
                return null;
            }
            int total = 0, ok = 0;
            StringBuilder payload = new StringBuilder(1 << 16);
            for (String line : body.split("\n")) {
                if (line.length() == 0) continue;
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                total++;
                String src = Translator.unesc(line.substring(0, t));
                String dst = Translator.unesc(line.substring(t + 1));
                if (src.length() == 0 || dst.length() == 0) continue;
                payload.append(Translator.esc(src)).append('\t')
                       .append(Translator.esc(dst)).append('\n');
                Translator.put(src, dst);
                ok++;
                if (payload.length() > 40000) {
                    flush(pkg, payload);
                    payload.setLength(0);
                }
            }
            if (payload.length() > 0) flush(pkg, payload);
            Log.i(TAG, "bulk import " + pkg + " wrote=" + ok + " total=" + total);
            return new int[]{ok, total};
        } catch (Throwable t) {
            Log.w(TAG, "bulk import failed " + pkg + ": " + t);
            return null;
        }
    }

    private static void flush(String pkg, StringBuilder payload) {
        String cache = "/data/user/0/" + pkg + "/files/fdtrans-cache.tsv";
        String uid = Scope.suMM("stat -c %u /data/user/0/" + pkg + "/files 2>/dev/null").trim();
        String esc = payload.toString().replace("'", "'\\''");
        Scope.suMM("printf '%s' '" + esc + "' >> " + cache
                + "; chown " + (uid.isEmpty() ? "1023" : uid) + ":" + (uid.isEmpty() ? "1023" : uid)
                + " " + cache + "; chmod 660 " + cache + "; echo FDOK");
    }

    /** 目标应用缓存里已有的原文。 */
    private static Set<String> cached(Context ctx, String pkg) {
        Set<String> out = new HashSet<String>();
        String body = Scope.suMM("if [ -s /data/user/0/" + pkg
                + "/files/fdtrans-cache.tsv ]; then cat /data/user/0/" + pkg
                + "/files/fdtrans-cache.tsv; fi");
        if (body == null) return out;
        BufferedReader br = new BufferedReader(new java.io.StringReader(body));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t > 0) out.add(Translator.unesc(line.substring(0, t)));
            }
        } catch (Throwable ignore) {
        }
        return out;
    }
}
