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
    /** 资源/资产路径：res/0M.xml、assets/foo.png —— 不是给人看的 */
    private static final Pattern RESPATH = Pattern.compile("^(res|assets|META-INF)/.*");
    private static final Pattern FILE_EXT = Pattern.compile("\\.(xml|png|webp|jpg|jpeg|gif|json|so|ttf|otf|db|bin|zip|9\\.png)$");
    /** 带变音符的拉丁字母：德语/法语/波兰语/匈牙利语等，不是英文界面串 */
    private static final Pattern DIACRITIC = Pattern.compile("[\\u00c0-\\u024f\\u1e00-\\u1eff]");
    /** 英文功能词 + 常见界面词：用来判断"这串是不是英文文案" */
    private static final java.util.Set<String> EN_WORDS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "from", "by", "with", "without",
            "is", "are", "was", "were", "be", "been", "being", "you", "your", "yours", "we", "our", "it", "its",
            "this", "that", "these", "those", "not", "no", "if", "then", "than", "when", "while", "will", "would",
            "can", "could", "should", "may", "might", "must", "do", "does", "did", "have", "has", "had", "as", "so",
            "all", "any", "more", "most", "other", "some", "such", "only", "own", "same", "too", "very", "just",
            "also", "use", "used", "using", "select", "tap", "swipe", "screen", "app", "apps", "application",
            "applications", "settings", "setting", "battery", "network", "privacy", "history", "home", "search",
            "update", "updates", "install", "installed", "uninstall", "remove", "removed", "permission",
            "permissions", "version", "size", "about", "open", "close", "enable", "disable", "enabled",
            "disabled", "allow", "allowed", "block", "blocked", "changes", "change", "undo", "safe", "dangerous",
            "download", "downloads", "available", "requires", "required", "support", "supported", "warning",
            "error", "failed", "success", "loading", "please", "try", "again", "back", "next", "done", "cancel",
            "delete", "save", "saved", "reset", "default", "details", "description", "more", "less", "total",
            "days", "day", "hours", "minutes", "seconds", "ago", "now", "never", "always", "unknown", "none"));
    private static final Pattern WORDLABEL = Pattern.compile("^[A-Za-z][A-Za-z'\\-]*$");

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
        // 先把带空格的句子排前面（真正给人看的文案），单词标签垫后
        List<String> all = new ArrayList<String>(out);
        java.util.Collections.sort(all, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int pa = a.indexOf(' ') >= 0 ? 0 : 1, pb = b.indexOf(' ') >= 0 ? 0 : 1;
                if (pa != pb) return pa - pb;
                return b.length() - a.length();
            }
        });
        if (all.size() > 4000) all = new ArrayList<String>(all.subList(0, 4000));
        return all;
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
        if (DIACRITIC.matcher(s).find()) return;                // 德语/法语/波兰语等
        if (RESPATH.matcher(s).matches()) return;               // res/0M.xml
        if (s.indexOf('/') >= 0) return;                        // 路径
        if (FILE_EXT.matcher(s).find()) return;                 // 文件名
        if (s.indexOf(' ') < 0) {                               // 单个词
            if (!Character.isUpperCase(s.charAt(0))) return;    // 小写标识符
            if (!WORDLABEL.matcher(s).matches()) return;        // a_b_c / x=1
        } else if (!looksEnglish(s)) {                          // 整句：必须是英文
            return;
        }
        if (out.size() >= 20000) return;                        // 收集上限，最终再筛 4000
        out.add(s);
    }

    /** 粗判英文：至少命中一个英文功能词/界面词，或含有格式占位符。 */
    private static boolean looksEnglish(String s) {
        if (s.indexOf('%') >= 0 || s.indexOf('{') >= 0) return true;
        int hits = 0, words = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : ' ';
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '\'') {
                sb.append(Character.toLowerCase(c));
            } else if (sb.length() > 0) {
                words++;
                if (EN_WORDS.contains(sb.toString())) hits++;
                sb.setLength(0);
            }
        }
        if (words == 0) return false;
        return hits >= (words > 3 ? 2 : 1);
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
