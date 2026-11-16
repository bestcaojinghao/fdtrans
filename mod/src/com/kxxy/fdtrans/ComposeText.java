package com.kxxy.fdtrans;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 在目标应用自己的 APK 里找 Compose 文本类。
 *
 * 不能写死 androidx.compose.ui.text.AnnotatedString：
 * Compose Multiplatform（Kotlin Multiplatform）打出来的类名是
 * androidx$compose$ui$text$AnnotatedString，写死就 ClassNotFoundException（Komi Store 就是）。
 * 所以直接把 classes*.dex 当字节流扫类型描述 "L...AnnotatedString;"
 */
public final class ComposeText {

    private static final String NEEDLE = "AnnotatedString;";
    private static final int MAX_BACK = 120;

    private ComposeText() {}

    public static List<String> discover(Context ctx) {
        List<String> apks = apks(ctx);
        LinkedHashSet<String> found = new LinkedHashSet<String>();
        for (String path : apks) {
            try {
                scan(new File(path), found);
            } catch (Throwable t) {
                android.util.Log.i("FDTrans", "dex scan fail " + path + ": " + t);
            }
        }
        return new ArrayList<String>(found);
    }

    private static List<String> apks(Context ctx) {
        List<String> out = new ArrayList<String>();
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0);
            if (ai.sourceDir != null) out.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) {
                for (String s : ai.splitSourceDirs) if (s != null) out.add(s);
            }
        } catch (Throwable t) {
            android.util.Log.i("FDTrans", "apks() fail: " + t);
        }
        android.util.Log.i("FDTrans", "scan apks=" + out);
        return out;
    }

    private static void scan(File apk, LinkedHashSet<String> out) throws Exception {
        ZipFile zf = new ZipFile(apk);
        try {
            android.util.Log.i("FDTrans", "zip open " + apk.getName() + " " + apk.canRead());
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.startsWith("classes") || !name.endsWith(".dex")) continue;
                android.util.Log.i("FDTrans", "scan dex " + name + " size=" + e.getSize());
                byte[] buf = readAll(zf.getInputStream(e));
                int before = out.size();
                collect(buf, out);
                android.util.Log.i("FDTrans", "dex " + name + " bytes=" + buf.length
                        + " hit=" + (out.size() - before));
            }
        } finally {
            try { zf.close(); } catch (Throwable ignore) {}
        }
    }

    /** 在 dex 字节里找 "AnnotatedString;"，往回走到类型描述开头的 'L'。 */
    private static void collect(byte[] buf, LinkedHashSet<String> out) {
        int idx = 0;
        while (true) {
            int hit = indexOf(buf, NEEDLE, idx);
            if (hit < 0) return;
            idx = hit + NEEDLE.length();
            int start = -1;
            int back = Math.max(0, hit - MAX_BACK);
            for (int i = hit - 1; i >= back; i--) {
                if (buf[i] == 'L') { start = i; break; }
                if (!isNameByte(buf[i])) break;
            }
            if (start < 0) continue;
            String desc = new String(buf, start, hit + NEEDLE.length() - start);
            if (!desc.startsWith("L") || !desc.endsWith(";")) continue;
            String cls = desc.substring(1, desc.length() - 1).replace('/', '.');
            if (cls.length() < 12 || cls.indexOf('.') < 0) continue;
            out.add(cls);
        }
    }

    private static boolean isNameByte(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                || (b >= '0' && b <= '9') || b == '/' || b == '$' || b == '_';
    }

    private static int indexOf(byte[] h, String needle, int from) {
        int n = needle.length();
        byte[] p = needle.getBytes();
        outer:
        for (int i = from; i + n <= h.length; i++) {
            for (int j = 0; j < n; j++) if (h[i + j] != p[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] tmp = new byte[1 << 16];
            int r;
            while ((r = in.read(tmp)) > 0) bo.write(tmp, 0, r);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
        }
    }
}
