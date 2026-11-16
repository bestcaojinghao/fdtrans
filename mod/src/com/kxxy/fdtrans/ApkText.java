package com.kxxy.fdtrans;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 不打开目标应用，直接从它的安装包里把「可能显示在界面上的英文」挖出来。
 *
 * 两条来源：
 *  1. resources.arsc 的全局字符串池 —— 界面文案绝大多数在这里（%1$d apps installed 之类）
 *  2. classes*.dex 的字符串池 —— 代码里硬编码的文案
 *
 * 挖出来之后交给 Translator 批量翻，写进目标应用的 fdtrans-cache.tsv。
 * 这样用户第一次打开该应用时，大部分文字直接命中缓存，不用等网络。
 */
public final class ApkText {

    private ApkText() {}

    private static final Pattern HAS_ASCII_WORD = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern HAS_CJK = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]");
    /** 形如 com.foo.Bar / http://x / a.b.c / resource 名：不像给人看的话 */
    private static final Pattern CODEISH = Pattern.compile("^[A-Za-z0-9_.$/:@#%&*+=~|\\\\\\[\\]{}<>-]+$");
    private static final Pattern NUMBERISH = Pattern.compile("^[\\d.,%\\s:+-]+$");

    /** 从安装包里提取候选英文串（去重、过滤掉代码符号/纯数字/中文）。 */
    public static List<String> extract(Context ctx, String pkg) {
        Set<String> out = new LinkedHashSet<String>();
        List<File> apks = copyApks(ctx, pkg);
        for (File f : apks) {
            try {
                ZipFile zf = new ZipFile(f);
                try {
                    java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String n = e.getName();
                        byte[] b = null;
                        if ("resources.arsc".equals(n)) {
                            b = readAll(zf.getInputStream(e));
                            arscStrings(b, out);
                        } else if (n.startsWith("classes") && n.endsWith(".dex")) {
                            b = readAll(zf.getInputStream(e));
                            dexStrings(b, out);
                        }
                    }
                } finally {
                    try { zf.close(); } catch (Throwable ignore) { }
                }
            } catch (Throwable t) {
                android.util.Log.w("FDTrans", "apktext " + f + " fail: " + t);
            }
            try { f.delete(); } catch (Throwable ignore) { }
        }
        return new ArrayList<String>(out);
    }

    /** 用 root 把目标应用的 base.apk（和 split）拷到本 app 的 cache 目录。 */
    private static List<File> copyApks(Context ctx, String pkg) {
        List<File> out = new ArrayList<File>();
        File dir = ctx.getCacheDir();
        // pm path 会列出 base + split
        String paths = Scope.suMM("pm path " + pkg + " | sed 's/package://'");
        if (paths == null) return out;
        String[] lines = paths.split("\n");
        int i = 0;
        for (String p : lines) {
            p = p.trim();
            if (p.length() == 0 || !p.endsWith(".apk")) continue;
            File dst = new File(dir, "scan-" + pkg + "-" + (i++) + ".apk");
            String r = Scope.suMM("cp '" + p + "' '" + dst.getAbsolutePath() + "' && chmod 666 '" + dst.getAbsolutePath() + "' && echo OK");
            if (r != null && r.contains("OK") && dst.length() > 0) out.add(dst);
            else android.util.Log.w("FDTrans", "apktext copy fail " + p + " -> " + r);
        }
        return out;
    }

    // ---------------- resources.arsc ----------------

    private static void arscStrings(byte[] d, Set<String> out) {
        try {
            if (d.length < 12) return;
            int type = u16(d, 0);
            if (type != 0x0002) return;                 // RES_TABLE_TYPE
            int headerSize = u16(d, 2);
            int poolOff = headerSize;                   // 紧跟 ResTable_header 的就是全局字符串池
            if (poolOff + 28 > d.length) return;
            int ptype = u16(d, poolOff);
            if (ptype != 0x0001) return;                // RES_STRING_POOL_TYPE
            int pHeader = u16(d, poolOff + 2);
            int count = u32(d, poolOff + 8);
            int flags = u32(d, poolOff + 16);
            int stringsStart = u32(d, poolOff + 20);
            boolean utf8 = (flags & 0x100) != 0;
            int base = poolOff + stringsStart;
            if (count <= 0 || count > 400000) return;
            for (int i = 0; i < count; i++) {
                try {
                    int off = u32(d, poolOff + pHeader + i * 4);
                    int p = base + off;
                    String s;
                    if (utf8) {
                        int n = d[p++] & 0xff;
                        if ((n & 0x80) != 0) p++;                  // 跳过 UTF-16 长度字段（2 字节）
                        n = d[p++] & 0xff;
                        if ((n & 0x80) != 0) {
                            n = ((n & 0x7f) << 8) | (d[p++] & 0xff);
                        }
                        s = new String(d, p, n, "UTF-8");
                    } else {
                        int n = u16(d, p); p += 2;
                        if ((n & 0x8000) != 0) {
                            n = ((n & 0x7fff) << 16) | u16(d, p); p += 2;
                        }
                        s = new String(d, p, n * 2, "UTF-16LE");
                    }
                    add(s, out);
                } catch (Throwable ignore) { }
            }
        } catch (Throwable t) {
            android.util.Log.w("FDTrans", "arsc parse fail: " + t);
        }
    }

    // ---------------- dex ----------------

    private static void dexStrings(byte[] d, Set<String> out) {
        try {
            if (d.length < 112 || d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return;
            int stringIdsSize = u32(d, 56);
            int stringIdsOff = u32(d, 60);
            if (stringIdsSize <= 0 || stringIdsSize > 2000000) return;
            for (int i = 0; i < stringIdsSize; i++) {
                try {
                    int dataOff = u32(d, stringIdsOff + i * 4);
                    int p = dataOff;
                    int len = 0, shift = 0, b;
                    do {                                        // uleb128 字符数
                        b = d[p++] & 0xff;
                        len |= (b & 0x7f) << shift;
                        shift += 7;
                    } while ((b & 0x80) != 0 && shift < 35);
                    if (len <= 0 || len > 400) continue;
                    int end = p;
                    while (end < d.length && d[end] != 0) end++;
                    if (end <= p) continue;
                    String s = new String(d, p, end - p, "UTF-8");
                    add(s, out);
                } catch (Throwable ignore) { }
            }
        } catch (Throwable t) {
            android.util.Log.w("FDTrans", "dex strings parse fail: " + t);
        }
    }

    // ---------------- 过滤 ----------------

    private static void add(String raw, Set<String> out) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.length() < 6 || s.length() > 200) return;
        if (HAS_CJK.matcher(s).find()) return;                 // 已经是中文
        if (!HAS_ASCII_WORD.matcher(s).find()) return;          // 没有英文单词
        if (NUMBERISH.matcher(s).matches()) return;
        if (CODEISH.matcher(s).matches()) return;               // com.foo.Bar / http://… / a_b_c
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) letters++;
        }
        if (letters < (s.length() / 3)) return;                 // 符号太多
        if (out.size() >= 4000) return;                         // 单个应用最多收 4000 条
        out.add(s);
    }

    // ---------------- 小工具 ----------------

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8);
    }

    private static int u32(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
            byte[] buf = new byte[1 << 15];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
        }
    }
}
