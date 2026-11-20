package com.kxxy.fdtrans;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 结构化的 Compose 文本类发现。
 *
 * 名字靠不住：R8 会把 androidx.compose.ui.text.AnnotatedString 改名成 l1.g
 * （Komi Store 实测：dex 的 class_def 里根本没有 androidx/compose/ui/text/AnnotatedString，
 *  只有一堆 ui/text/a、ui/text/b 这种短名，而它的构造函数签名没变）。
 * 所以按签名找：同时具备 (String)V 和 (String,List)V / (String,List,List)V 的类，
 * 整包通常只有一个，就是 AnnotatedString。
 */
public final class Dex {

    private Dex() {}

    /** 返回候选类名（点分，如 l1.g）。 */
    public static List<String> annotatedStringCandidates(Context ctx) {
        Set<String> out = new LinkedHashSet<String>();
        for (String path : apks(ctx)) {
            try {
                scan(new File(path), out);
            } catch (Throwable t) {
                android.util.Log.i("FDTrans", "dex struct scan fail " + path + ": " + t);
            }
        }
        return new ArrayList<String>(out);
    }

    /**
     * 这个应用能不能被翻？把结论压成一行人能读的话。
     * Compose 类数 / 是否有未混淆的 AnnotatedString / WebView 类数 / Flutter·Unity·RN 原生库。
     */
    public static String probe(Context ctx, boolean tvOk) {
        int compose = 0, webkit = 0;
        boolean named = false;
        boolean flutter = false, unity = false, rn = false, tauri = false;
        for (String path : apks(ctx)) {
            try {
                ZipFile zf = new ZipFile(new File(path));
                try {
                    java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String n = e.getName();
                        if (n.startsWith("lib/")) {
                            if (n.endsWith("libflutter.so")) flutter = true;
                            else if (n.endsWith("libunity.so")) unity = true;
                            else if (n.endsWith("libreactnativejni.so")) rn = true;
                            else if (n.endsWith("libapp.so") && n.contains("arm64")) tauri = true;
                            continue;
                        }
                        if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                        byte[] b = readAll(zf.getInputStream(e));
                        if (b.length < 112) continue;
                        int strN = u32(b, 56), strOff = u32(b, 60);
                        int typeN = u32(b, 64), typeOff = u32(b, 68);
                        int clsN = u32(b, 96), clsOff = u32(b, 100);
                        String[] types = new String[typeN];
                        for (int i = 0; i < typeN; i++) {
                            types[i] = strAt(b, u32(b, strOff + 4 * u32(b, typeOff + 4 * i)));
                        }
                        for (int i = 0; i < clsN; i++) {
                            int t = u32(b, clsOff + 32 * i);
                            if (t < 0 || t >= typeN) continue;
                            String d = types[t];
                            if (d == null) continue;
                            if (d.startsWith("Landroidx/compose/ui/")) compose++;
                            if (d.startsWith("Landroid/webkit/")) webkit++;
                            if ("Landroidx/compose/ui/text/AnnotatedString;".equals(d)) named = true;
                        }
                    }
                } finally {
                    try { zf.close(); } catch (Throwable ignore) {}
                }
            } catch (Throwable t) {
                android.util.Log.i("FDTrans", "probe fail " + path + ": " + t);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Compose 类 ").append(compose)
          .append(named ? "（标准命名）" : "（已混淆）")
          .append(" · WebView 类 ").append(webkit);
        if (flutter) sb.append(" · Flutter");
        if (unity) sb.append(" · Unity");
        if (rn) sb.append(" · ReactNative");
        if (tauri) sb.append(" · Tauri/Rust");
        if (flutter || unity) {
            sb.append(" · 自绘界面，多半翻不到");
        } else if (compose == 0 && webkit == 0 && !tvOk) {
            sb.append(" · 找不到可 hook 的文本通道");
        } else if (compose == 0 && tvOk) {
            sb.append(" · 只有 TextView 通道");
        }
        return sb.toString();
    }

    private static List<String> apks(Context ctx) {        List<String> out = new ArrayList<String>();
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0);
            if (ai.sourceDir != null) out.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) if (s != null) out.add(s);
        } catch (Throwable t) {
            android.util.Log.i("FDTrans", "apks() fail: " + t);
        }
        return out;
    }

    private static void scan(File apk, Set<String> out) throws Exception {
        ZipFile zf = new ZipFile(apk);
        try {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                byte[] b = readAll(zf.getInputStream(e));
                if (b.length > 12 && new String(b, 0, 4).equals("dex\n")) {
                    parse(b, out);
                }
            }
        } finally {
            try { zf.close(); } catch (Throwable ignore) {}
        }
    }

    // ---------- dex 解析 ----------

    private static void parse(byte[] b, Set<String> out) {
        int p = 56;
        int strN = u32(b, p), strOff = u32(b, p + 4);
        int typeN = u32(b, p + 8), typeOff = u32(b, p + 12);
        int protoN = u32(b, p + 16), protoOff = u32(b, p + 20);
        int methN = u32(b, p + 32), methOff = u32(b, p + 36);

        String[] types = new String[typeN];
        for (int i = 0; i < typeN; i++) {
            int strIdx = u32(b, typeOff + 4 * i);
            types[i] = strAt(b, u32(b, strOff + 4 * strIdx));
        }
        // proto idx -> 参数+返回值的描述串
        String[] protos = new String[protoN];
        for (int i = 0; i < protoN; i++) {
            int o = protoOff + 12 * i;
            int retType = u32(b, o + 4), paramsOff = u32(b, o + 8);
            StringBuilder sb = new StringBuilder("(");
            if (paramsOff != 0) {
                int n = u32(b, paramsOff);
                for (int k = 0; k < n; k++) {
                    int t = u16(b, paramsOff + 4 + 2 * k);
                    if (t >= 0 && t < typeN) sb.append(types[t]);
                }
            }
            sb.append(')');
            if (retType < typeN) sb.append(types[retType]);
            protos[i] = sb.toString();
        }
        // 收集每个类自身的 <init> 签名集合
        Map<String, Set<String>> ctor = new HashMap<String, Set<String>>();
        for (int i = 0; i < methN; i++) {
            int o = methOff + 8 * i;
            int cls = u16(b, o), proto = u16(b, o + 2), nameIdx = u32(b, o + 4);
            if (nameIdx >= strN) continue;
            String name = strAt(b, u32(b, strOff + 4 * nameIdx));
            if (!"<init>".equals(name)) continue;
            if (cls >= typeN || proto >= protoN) continue;
            Set<String> s = ctor.get(types[cls]);
            if (s == null) {
                s = new HashSet<String>();
                ctor.put(types[cls], s);
            }
            s.add(protos[proto]);
        }
        match(ctor, out);
    }

    private static final String STR = "Ljava/lang/String;";
    private static final String LIST = "Ljava/util/List;";

    /**
     * 找 AnnotatedString 的类（可能被 R8 改名）。
     * 混淆版常见只剩 (String,List,List)V、或 (String,List)V + (List,String)V，
     * 所以不能强求 (String)V —— 之前强制要求它，导致 yuki 这类混淆应用只捞到一个假候选。
     */
    private static void match(Map<String, Set<String>> ctor, Set<String> out) {
        for (Map.Entry<String, Set<String>> e : ctor.entrySet()) {
            Set<String> s = e.getValue();
            boolean three = s.contains("(" + STR + LIST + LIST + ")V");
            boolean two = s.contains("(" + STR + LIST + ")V");
            boolean rev = s.contains("(" + LIST + STR + ")V");
            boolean ok = three || (two && rev);
            if (!ok) continue;
            String desc = e.getKey();
            if (desc == null || !desc.startsWith("L") || !desc.endsWith(";")) continue;
            out.add(desc.substring(1, desc.length() - 1).replace('/', '.'));
        }
    }

    private static String strAt(byte[] b, int dataOff) {
        int[] r = uleb(b, dataOff);
        int len = r[0], off = r[1];
        StringBuilder sb = new StringBuilder(len);
        int end = Math.min(b.length, off + len * 3);
        int i = off;
        while (i < end && sb.length() < len) {
            int a = b[i++] & 0xff;
            if (a < 0x80) {
                sb.append((char) a);
            } else if ((a & 0xe0) == 0xc0 && i < end) {
                int c = b[i++] & 0x3f;
                sb.append((char) (((a & 0x1f) << 6) | c));
            } else if ((a & 0xf0) == 0xe0 && i + 1 < end) {
                int c1 = b[i++] & 0x3f, c2 = b[i++] & 0x3f;
                sb.append((char) (((a & 0x0f) << 12) | (c1 << 6) | c2));
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static int[] uleb(byte[] b, int off) {
        int r = 0, s = 0, i = off;
        while (true) {
            int x = b[i++] & 0xff;
            r |= (x & 0x7f) << s;
            if (x < 0x80) break;
            s += 7;
            if (i >= b.length) break;
        }
        return new int[]{r, i};
    }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] t = new byte[1 << 16];
            int r;
            while ((r = in.read(t)) > 0) bo.write(t, 0, r);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
        }
    }
}
