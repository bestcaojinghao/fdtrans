package com.kxxy.fdtrans;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「一键体检」：判断作用域里的每个应用到底能不能翻，以及卡在哪一步。
 *
 * 三个判定点（都有实测依据）：
 * 1) 有没有 android.permission.INTERNET —— 没有的话，模块在它的进程里根本发不出请求，
 *    日志是 `SecurityException: Permission denied (missing INTERNET permission?)`，
 *    必须靠「后台代翻服务」把请求搬到模块自己的进程。
 * 2) 译文缓存目录能不能写 —— 目标应用的 files 目录若被 root 或别的 uid 建过，
 *    模块写缓存会 EACCES，翻译成果落不了盘。
 * 3) 缓存里已经有多少条 —— 0 条意味着「打开也全是英文」，需要预翻。
 */
public final class Checkup {

    private Checkup() {}

    public static String run(Context ctx, Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        long t0 = System.currentTimeMillis();
        sb.append("体检时间：").append(new java.text.SimpleDateFormat("MM-dd HH:mm")
                .format(new java.util.Date())).append('\n');
        sb.append("作用域应用：").append(pkgs.size()).append(" 个\n");
        sb.append("后台代翻服务：").append(Bridge.running ? "运行中 ✓" : "未运行 ✗（没联网权限的应用会翻不了）").append('\n');
        sb.append("------------------------------\n");

        int ok = 0, needBridge = 0, bad = 0;
        List<String> problems = new ArrayList<String>();

        for (String pkg : pkgs) {
            StringBuilder line = new StringBuilder();
            line.append(pkg).append('\n');

            String net = Scope.suMM("pm dump " + pkg + " 2>/dev/null | grep -c 'android.permission.INTERNET'");
            boolean hasInet = net != null && net.trim().matches("[1-9][0-9]*");
            line.append("  联网权限：").append(hasInet ? "有 → 可实时翻" : "没有 → 需要后台代翻").append('\n');
            if (!hasInet) needBridge++;

            String lines = Scope.suMM("wc -l < /data/user/0/" + pkg + "/files/fdtrans-cache.tsv 2>/dev/null");
            int cache = parseInt(lines, 0);
            line.append("  已有译文：").append(cache).append(" 条").append('\n');

            String writeTest = Scope.suMM("d=/data/user/0/" + pkg + "/files; [ -d $d ] && [ -w $d ] && echo W || echo N");
            boolean writable = writeTest != null && writeTest.contains("W");
            line.append("  缓存可写：").append(writable ? "能 ✓" : "不能 ✗（翻译成果存不下来）").append('\n');

            int last = Preload.lastDone(ctx, pkg);
            line.append("  上次预翻：").append(last > 0 ? last + " 条" : "还没预翻过").append('\n');

            String verdict;
            if (!writable) { verdict = "✗ 卡在缓存可写"; bad++; problems.add(pkg + "：缓存目录不可写"); }
            else if (!hasInet && !Bridge.running) { verdict = "✗ 缺联网且后台代翻没开"; bad++; problems.add(pkg + "：没有联网权限，请开启后台代翻"); }
            else if (cache == 0) { verdict = "⚠ 没翻过，建议预翻"; problems.add(pkg + "：缓存是空的，建议点预翻"); }
            else { verdict = "✓ 正常"; ok++; }
            line.append("  结论：").append(verdict).append('\n');
            sb.append(line).append('\n');
        }

        sb.append("------------------------------\n");
        sb.append("正常：").append(ok).append(" 个｜需要后台代翻：").append(needBridge)
          .append(" 个｜有问题：").append(bad).append(" 个\n");
        if (!problems.isEmpty()) {
            sb.append("\n要处理的：\n");
            for (String p : problems) sb.append("• ").append(p).append('\n');
        }
        sb.append("\n耗时 ").append(System.currentTimeMillis() - t0).append(" ms\n");
        android.util.Log.i("FDTrans", "checkup done apps=" + pkgs.size() + " ok=" + ok + " bad=" + bad);
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        String t = s.trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return def;
        try { return Integer.parseInt(digits.toString()); } catch (Throwable e) { return def; }
    }
}
