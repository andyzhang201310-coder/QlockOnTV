package com.family.studytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * AI 错题讲解：把「题目 + 正确答案 + 孩子选的错项」发给 OpenAI 兼容接口，换一段讲给孩子听的话。
 *
 * <p>三个约束决定了这里的写法：
 * <ul>
 *   <li>题库没有解析字段，每次都得现场生成 → 按题号做本地缓存，同一题第二次直接命中；</li>
 *   <li>孩子等不了，所以先查缓存再发请求，且整段请求放在后台线程，回调回主线程；</li>
 *   <li>电视端零依赖（不用 OkHttp），出网走 {@link HttpURLConnection}，与节假日同步同一套。</li>
 * </ul>
 *
 * <p>Key 只存在设备本地配置里，不进 APK；后台拿到的永远是打码串（{@link #mask}）。
 */
public final class AiExplain {

    /** 结果回调：成功给 text，失败给 error，二者必有其一是非空的 */
    public interface Listener {
        void onResult(String text, String error);
    }

    /** 缓存条数上限：一题百来字，400 条约 80KB，够覆盖错题集又不至于让文件无限长 */
    private static final int CACHE_MAX = 400;
    private static final int CACHE_TRIM_TO = 380;
    private static final String CACHE_FILE = "ai_cache.json";

    /** 讲给孩子听的话超过这个长度就截断，避免把面板撑爆 */
    private static final int TEXT_MAX = 300;

    private static final String SYS_PROMPT =
            "你是一位耐心的小学老师，讲题口语化、简短、以鼓励为主。";

    private AiExplain() {
    }

    // ------------------------------------------------------------------ 配置

    /** 四项都齐了才算可用：开关打开 + 地址 + 模型 + Key */
    public static boolean ready(JSONObject cfg) {
        if (cfg == null || !cfg.optBoolean("aiEnabled", false)) {
            return false;
        }
        return cfg.optString("aiBaseUrl", "").trim().length() > 0
                && cfg.optString("aiModel", "").trim().length() > 0
                && cfg.optString("aiApiKey", "").trim().length() > 0;
    }

    /** 打码：sk-abcdefghijklmn → sk-****klmn。后台只拿这个，看不到真 Key */
    public static String mask(String key) {
        String k = key == null ? "" : key.trim();
        if (k.length() == 0) {
            return "";
        }
        if (k.length() <= 10) {
            return "****";
        }
        return k.substring(0, Math.min(3, k.length())) + "****" + k.substring(k.length() - 4);
    }

    /** 打码串回写的识别：包含四个星号即认为家长没改过这个输入框 */
    public static boolean isMasked(String key) {
        return key != null && key.indexOf("****") >= 0;
    }

    // ------------------------------------------------------------------ 提示词

    /**
     * 组 prompt：只给模型「题目 / 四个选项 / 正确答案 / 孩子选了哪个」，
     * 其余判断都交给模型 —— 题库里没有解析原文，硬凑反而会编。
     */
    public static String buildPrompt(String subject, int grade, String question,
                                     List<String> options, int answerIdx, int chosenIdx) {
        StringBuilder sb = new StringBuilder();
        String who = grade > 0 ? "小学" + grade + "年级" : "小学";
        sb.append("你是").append(who).append(subject).append("老师，正在给一个刚答错题的孩子讲这道题。\n\n");
        sb.append("题目：").append(question).append("\n");
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                sb.append(letter(i)).append(". ").append(options.get(i)).append("\n");
            }
        }
        if (options != null && answerIdx >= 0 && answerIdx < options.size()) {
            sb.append("\n正确答案：").append(letter(answerIdx)).append(". ")
                    .append(options.get(answerIdx)).append("\n");
        }
        if (options != null && chosenIdx >= 0 && chosenIdx < options.size()
                && chosenIdx != answerIdx) {
            sb.append("孩子选了：").append(letter(chosenIdx)).append(". ")
                    .append(options.get(chosenIdx)).append("\n");
        }
        sb.append("\n请用孩子能听懂的口语讲清楚两件事：\n");
        sb.append("1) 正确答案为什么对，能举个小例子就举一个；\n");
        sb.append("2) 孩子选的那个为什么不对，点出最容易混淆的地方。\n");
        sb.append("不要重复题目原文，不要用「本题考查」这类书面语，总字数不超过 90 字。");
        return sb.toString();
    }

    static String letter(int i) {
        return i >= 0 && i < 26 ? String.valueOf((char) ('A' + i)) : "?";
    }

    // ------------------------------------------------------------------ 缓存

    private static File cacheFile(Context c) {
        return new File(c.getFilesDir(), CACHE_FILE);
    }

    /** 命中返回解析文本，没命中返回 null（调用方据此决定要不要发请求） */
    public static String cached(Context c, String qid) {
        if (c == null || qid == null || qid.length() == 0) {
            return null;
        }
        synchronized (AiExplain.class) {
            JSONObject items = readItems(c);
            JSONObject e = items.optJSONObject(qid);
            if (e == null) {
                return null;
            }
            String t = e.optString("t", "");
            return t.length() == 0 ? null : t;
        }
    }

    public static synchronized void putCache(Context c, String qid, String text) {
        if (c == null || qid == null || qid.length() == 0
                || text == null || text.length() == 0) {
            return;
        }
        JSONObject items = readItems(c);
        try {
            JSONObject e = new JSONObject();
            e.put("t", text);
            e.put("at", System.currentTimeMillis());
            items.put(qid, e);
            trim(items);
            writeItems(c, items);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized int cacheSize(Context c) {
        return readItems(c).length();
    }

    public static synchronized void clearCache(Context c) {
        if (c == null) {
            return;
        }
        try {
            File f = cacheFile(c);
            if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject readItems(Context c) {
        JSONObject root = null;
        File f = cacheFile(c);
        if (f.exists()) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                root = new JSONObject(new String(readAll(in), Charset.forName("UTF-8")));
            } catch (Throwable ignored) {
                root = null;
            } finally {
                close(in);
            }
        }
        JSONObject items = root == null ? null : root.optJSONObject("items");
        return items == null ? new JSONObject() : items;
    }

    private static void writeItems(Context c, JSONObject items) {
        FileOutputStream out = null;
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("items", items);
            byte[] data = root.toString().getBytes(Charset.forName("UTF-8"));
            out = new FileOutputStream(cacheFile(c));
            out.write(data);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            close(out);
        }
    }

    /** 超上限时按写入时间从旧到新淘汰 */
    private static void trim(JSONObject items) {
        if (items.length() <= CACHE_MAX) {
            return;
        }
        List<String[]> all = new ArrayList<String[]>();
        for (Iterator<String> it = items.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject e = items.optJSONObject(k);
            all.add(new String[]{k, String.valueOf(e == null ? 0L : e.optLong("at", 0L))});
        }
        Collections.sort(all, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                long x = parseLong(a[1]);
                long y = parseLong(b[1]);
                return x < y ? -1 : (x > y ? 1 : 0);
            }
        });
        int drop = all.size() - CACHE_TRIM_TO;
        for (int i = 0; i < drop && i < all.size(); i++) {
            items.remove(all.get(i)[0]);
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ 请求

    /** 解析结果：text 与 error 只有一个是非空的 */
    public static final class Res {
        public String text;
        public String error;

        public boolean ok() {
            return text != null && text.length() > 0;
        }
    }

    /**
     * 异步取解析：先查缓存（同步、极快），未命中再开线程请求。
     *
     * <p>回调一定在主线程；调用方要自己判断 Activity 是否还活着。
     */
    public static void explain(final Context ctx, final JSONObject cfg, final String qid,
                               final String prompt, final Listener listener) {
        String hit = cached(ctx, qid);
        if (hit != null) {
            post(listener, hit, null);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Res r = request(cfg, prompt);
                if (r.ok()) {
                    putCache(ctx, qid, r.text);
                }
                post(listener, r.text, r.error);
            }
        }, "ai-explain").start();
    }

    /** 后台「测连通」用：发一句最短的请求，只看能不能通 */
    public static Res ping(JSONObject cfg) {
        return request(cfg, "只回答两个字：收到");
    }

    private static void post(final Listener l, final String text, final String error) {
        if (l == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                l.onResult(text, error);
            }
        });
    }

    static Res request(JSONObject cfg, String prompt) {
        Res r = new Res();
        HttpURLConnection conn = null;
        try {
            if (cfg == null) {
                r.error = "没有 AI 配置";
                return r;
            }
            String base = cfg.optString("aiBaseUrl", "").trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                r.error = "接口地址要以 http:// 或 https:// 开头";
                return r;
            }
            int timeout = Math.max(5, Math.min(60, cfg.optInt("aiTimeoutSeconds", 20))) * 1000;

            JSONObject body = new JSONObject();
            body.put("model", cfg.optString("aiModel", "").trim());
            JSONArray msgs = new JSONArray();
            msgs.put(msg("system", SYS_PROMPT));
            msgs.put(msg("user", prompt == null ? "" : prompt));
            body.put("messages", msgs);
            body.put("temperature", 0.3);
            body.put("max_tokens", 400);
            body.put("stream", false);
            byte[] data = body.toString().getBytes(Charset.forName("UTF-8"));

            URL u = new URL(base + "/chat/completions");
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(Math.min(timeout, 10000));
            conn.setReadTimeout(timeout);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization",
                    "Bearer " + cfg.optString("aiApiKey", "").trim());
            conn.setFixedLengthStreamingMode(data.length);

            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            close(os);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                r.error = httpError(code, errorBody(conn));
                return r;
            }
            String raw = new String(readAll(conn.getInputStream()), Charset.forName("UTF-8"));
            String text = pickContent(raw);
            if (text.length() == 0) {
                r.error = "模型返回了空内容，换个模型试试";
                return r;
            }
            r.text = clip(text);
            return r;
        } catch (SocketTimeoutException e) {
            r.error = "等太久了：模型没在 "
                    + Math.max(5, Math.min(60, cfg == null ? 20 : cfg.optInt("aiTimeoutSeconds", 20)))
                    + " 秒内回话";
            return r;
        } catch (java.net.UnknownHostException e) {
            r.error = "电视连不上网（域名解析失败）";
            return r;
        } catch (javax.net.ssl.SSLException e) {
            r.error = "HTTPS 握手失败，检查接口地址是否为 https";
            return r;
        } catch (Throwable e) {
            r.error = "请求失败：" + e.getClass().getSimpleName();
            return r;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static JSONObject msg(String role, String content) throws Exception {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("content", content);
        return o;
    }

    /** 从 OpenAI 兼容响应里取正文；取不到返回空串 */
    static String pickContent(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) {
                return "";
            }
            JSONObject m = ch.getJSONObject(0).optJSONObject("message");
            return m == null ? "" : m.optString("content", "").trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 错误体里的 message，用来把「模型名写错」这类问题说清楚 */
    private static String errorBody(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            if (es == null) {
                return "";
            }
            String raw = new String(readAll(es), Charset.forName("UTF-8"));
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            String m = err == null ? o.optString("message", "") : err.optString("message", "");
            return m == null ? "" : m.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    static String httpError(int code, String detail) {
        String base;
        switch (code) {
            case 400:
                base = "请求被拒绝（400），多半是模型名写错了";
                break;
            case 401:
                base = "API Key 无效（401），检查后台填的 Key";
                break;
            case 403:
                base = "没有权限（403），这个 Key 可能没开通该模型";
                break;
            case 404:
                base = "接口地址不对（404），Base URL 一般以 /v1 结尾";
                break;
            case 429:
                base = "请求太频繁（429），等一会儿再试";
                break;
            case 500:
            case 502:
            case 503:
                base = "模型服务暂时不可用（" + code + "）";
                break;
            default:
                base = "接口返回 HTTP " + code;
                break;
        }
        if (detail != null && detail.length() > 0) {
            String d = detail.length() > 80 ? detail.substring(0, 80) + "…" : detail;
            base = base + "｜" + d;
        }
        return base;
    }

    static String clip(String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() <= TEXT_MAX) {
            return t;
        }
        return t.substring(0, TEXT_MAX) + "…";
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
