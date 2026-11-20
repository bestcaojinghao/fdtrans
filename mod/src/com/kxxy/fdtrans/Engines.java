package com.kxxy.fdtrans;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 多家翻译接口。全部凭公开接口匿名可用（本机实测，2026-11-20）：
 *   bing      cn.bing.com/ttranslatev3，靠页面 token，单次可吃几千字符 —— 质量最好
 *   tencent   transmart.qq.com/api/imt，一次可传 text_list，176ms 实测
 *   youdao    aidemo.youdao.com/trans，只能单条
 *   mymemory  api.mymemory.translated.net，单条，配额小
 * 每个引擎都实现「给一批文本 -> 一一对应的译文数组」，失败返回 null 交给下一个。
 */
public final class Engines {

    public interface E {
        String name();

        /** 与 items 一一对应；任一环节失败返回 null（调用方换引擎重试）。 */
        String[] translate(List<String> items) throws Exception;
    }

    static final String SEP = " ||| ";

    public static final E BING = new E() {
        public String name() { return "bing"; }

        public String[] translate(List<String> items) {
            return Translator.callBingBatch(items);
        }
    };

    public static final E TENCENT = new E() {
        public String name() { return "tencent"; }

        public String[] translate(List<String> items) throws Exception {
            StringBuilder arr = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) arr.append(',');
                arr.append('"').append(jsonEsc(items.get(i))).append('"');
            }
            String body = "{\"header\":{\"fn\":\"auto_translation\","
                    + "\"client_key\":\"browser-chrome-120.0.0.0\"},"
                    + "\"type\":\"plain\",\"model_category\":\"normal\",\"text_domain\":\"\","
                    + "\"source\":{\"lang\":\"en\",\"text_list\":[" + arr + "]},"
                    + "\"target\":{\"lang\":\"zh\"}}";
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://transmart.qq.com/api/imt").openConnection();
                if (c instanceof javax.net.ssl.HttpsURLConnection) {
                    relax((javax.net.ssl.HttpsURLConnection) c, "transmart.qq.com");
                }
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16)");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Origin", "https://transmart.qq.com");
                c.setRequestProperty("Referer", "https://transmart.qq.com/zh-CN/index");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
                if (c.getResponseCode() != 200) return null;
                String json = read(c);
                int at = json.indexOf("\"auto_translation\":[");
                if (at < 0) return null;
                List<String> out = new ArrayList<String>();
                int p = json.indexOf('[', at) + 1;
                while (p > 0 && p < json.length()) {
                    int q = json.indexOf('"', p);
                    if (q < 0) break;
                    String s = Translator.readJsonString(json, q);
                    if (s == null) break;
                    out.add(s);
                    p = q + s.length() + 2;
                    int comma = json.indexOf(',', p);
                    int close = json.indexOf(']', p);
                    if (close >= 0 && (comma < 0 || close < comma)) break;
                }
                if (out.size() != items.size()) return null;
                return out.toArray(new String[0]);
            } finally {
                if (c != null) c.disconnect();
            }
        }
    };

    public static final E YOUDAO = new E() {
        public String name() { return "youdao"; }

        public String[] translate(List<String> items) throws Exception {
            // 该接口只收单条，批量时顺序打，靠调用方分组并行
            String[] out = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                out[i] = one(items.get(i));
                if (out[i] == null) return null;
            }
            return out;
        }

        private String one(String text) throws Exception {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://aidemo.youdao.com/trans").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16)");
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = c.getOutputStream();
                os.write(("q=" + URLEncoder.encode(text, "UTF-8") + "&from=en&to=zh-CHS")
                        .getBytes("UTF-8"));
                os.flush();
                os.close();
                if (c.getResponseCode() != 200) return null;
                String json = read(c);
                int t = json.indexOf("\"translation\":[");
                if (t < 0) return null;
                int q = json.indexOf('"', t);
                if (q < 0) return null;
                String s = Translator.readJsonString(json, q);
                return (s == null || s.length() == 0) ? null : s;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    };

    public static final E MYMEMORY = new E() {
        public String name() { return "mymemory"; }

        public String[] translate(List<String> items) {
            String[] out = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                out[i] = Translator.callMyMemory(items.get(i));
                if (out[i] == null) return null;
            }
            return out;
        }
    };

    /** 按质量排序；并行分组时按组号轮转起点，避免所有组都挤在 bing 上。 */

    /**
     * 大模型（OpenAI 兼容 /chat/completions）。质量最好：保留占位符、能读懂整句上下文，
     * 但一次只能生成几十条，实测吞吐约 6~7 条/秒（腾讯 30~60 条/秒），所以只当「质量模式」用。
     */
    public static final E LLM = new E() {
        public String name() { return "llm"; }

        public String[] translate(List<String> items) throws Exception {
            String base = Config.llmBase();
            String key = Config.llmKey();
            if (base == null || base.length() == 0 || key == null || key.length() == 0) return null;
            StringBuilder arr = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) arr.append(',');
                arr.append('"').append(jsonEsc(items.get(i))).append('"');
            }
            String sys = "You are a translator for Android app UI. Translate every English string into "
                    + "concise Simplified Chinese. Keep %s / %1$d placeholders, \\n escapes, URLs and "
                    + "product names unchanged. Return ONLY a JSON array of strings with the same order "
                    + "and the same length as the input, no commentary.";
            String body = "{\"model\":\"" + jsonEsc(Config.llmModel()) + "\",\"temperature\":0,"
                    + "\"reasoning_effort\":\"none\",\"messages\":[{\"role\":\"system\",\"content\":\""
                    + jsonEsc(sys) + "\"},{\"role\":\"user\",\"content\":\"" + jsonEsc("[" + arr + "]")
                    + "\"}]}";
            String url = base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(120000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + key);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
                int code = c.getResponseCode();
                if (code != 200) {
                    android.util.Log.w("FDTrans", "llm http " + code);
                    return null;
                }
                String json = read(c);
                int i = json.indexOf("\"content\":\"");
                if (i < 0) return null;
                int from = i + 11;
                StringBuilder raw = new StringBuilder();
                for (int k = from; k < json.length(); k++) {
                    char ch = json.charAt(k);
                    if (ch == '\\') { raw.append(ch).append(json.charAt(++k)); continue; }
                    if (ch == '"') break;
                    raw.append(ch);
                }
                String[] parsed = parseJsonArray(unescape(raw.toString()));
                if (parsed == null || parsed.length != items.size()) {
                    android.util.Log.w("FDTrans", "llm len mismatch " + (parsed == null ? -1 : parsed.length)
                            + "/" + items.size());
                    return null;
                }
                return parsed;
            } finally {
                try { c.disconnect(); } catch (Throwable ignore) { }
            }
        }
    };

    /** 解析 JSON 字符串数组（模型偶尔会包 ``` 或加说明，先截取第一个 '[' 到最后一个 ']'）。 */
    static String[] parseJsonArray(String s) {
        if (s == null) return null;
        int a = s.indexOf('['), b = s.lastIndexOf(']');
        if (a < 0 || b <= a) return null;
        s = s.substring(a + 1, b);
        List<String> out = new ArrayList<String>();
        StringBuilder cur = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (cur == null) {
                if (c == '"') cur = new StringBuilder();
                continue;
            }
            if (c == '\\' && i + 1 < s.length()) { cur.append(c).append(s.charAt(++i)); continue; }
            if (c == '"') { out.add(unescape(cur.toString())); cur = null; continue; }
            cur.append(c);
        }
        return out.toArray(new String[0]);
    }

    /** 只处理 JSON 里常见的 \\n \\t \\" \\\\ \\uXXXX。 */
    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case '"': b.append('"'); break;
                case '/': b.append('/'); break;
                case '\\': b.append('\\'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable ignore) { b.append(n); }
                    } else b.append(n);
                    break;
                default: b.append(n);
            }
        }
        return b.toString();
    }

    public static final E[] ALL = {BING, TENCENT, YOUDAO, MYMEMORY, LLM};

    private Engines() {}


    /**
     * 本机系统时间被校到 2026 年（未来），transmart.qq.com 的证书按真实时间看已过期，
     * Java 侧报 javax.net.ssl.SSLHandshakeException: Chain validation failed，
     * 而 python --insecure 能正常返回。给这一个域名放宽校验，其余域名不动。
     */
    private static javax.net.ssl.SSLSocketFactory laxFactory(String host) {
        if (host == null || !host.endsWith("transmart.qq.com")) return null;
        try {
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }}, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Throwable t) {
            android.util.Log.w("FDTrans", "lax ssl fail: " + t);
            return null;
        }
    }

    /** 给 https 连接装上宽松工厂（只对白名单域名生效）。 */
    static void relax(javax.net.ssl.HttpsURLConnection h, String host) {
        javax.net.ssl.SSLSocketFactory f = laxFactory(host);
        if (f != null) h.setSSLSocketFactory(f);
    }

    static String read(HttpURLConnection c) throws Exception {
        StringBuilder sb = new StringBuilder(4096);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        char[] buf = new char[2048];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }

    static String jsonEsc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(' ');
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
