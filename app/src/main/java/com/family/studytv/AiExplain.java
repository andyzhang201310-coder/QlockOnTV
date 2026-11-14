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
import java.util.regex.Pattern;

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

    /** 讲解详细程度：1 简明 / 2 标准 / 3 详细（与后台 aiDetail 一致） */
    public static final int DETAIL_BRIEF = 1;
    public static final int DETAIL_STANDARD = 2;
    public static final int DETAIL_DEEP = 3;

    private static final String SYS_PROMPT =
            "你是这道题对应学科的科任老师，正在教室里给刚答错的学生当面讲评。"
                    + "全程用本学科老师的专业视角和课堂口吻，像在黑板前一步步把道理讲明白；"
                    + "不要通用助手腔，不写「作为 AI」「我来帮你分析」「希望对你有帮助」。"
                    + "讲题用孩子听得懂的口语，句子短、分步骤、有例子，会把知识点拆开讲明白，"
                    + "不把题目原文复述一遍，要求的每一段都写全，不写空泛的夸奖和安慰，"
                    + "不使用「本题考查」这类书面语。"
                    + "不讲废话：不把数字用中文读一遍、不复述题干和选项原文、"
                    + "不写「我们来看」「先看题目」这类过场话，每段第一句就给有效内容。";

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
     * v1.10.6 起按四段结构生成分层讲解（结论 / 拆解 / 看其他选项 / 易错点），默认标准档。
     */
    public static String buildPrompt(String subject, int grade, String question,
                                     List<String> options, int answerIdx, int chosenIdx) {
        return buildPrompt(subject, grade, question, options, answerIdx, chosenIdx,
                DETAIL_STANDARD);
    }

    public static String buildPrompt(String subject, int grade, String question,
                                     List<String> options, int answerIdx, int chosenIdx,
                                     int detail) {
        int lv = detailLevelValue(detail);
        boolean wrong = options != null && chosenIdx >= 0 && chosenIdx < options.size()
                && chosenIdx != answerIdx;
        StringBuilder sb = new StringBuilder();
        String who = grade > 0 ? "小学" + grade + "年级" : "小学";
        String subj = subject == null ? "" : subject;
        String view = subj.contains("英语")
                ? "先带学生认单词、再拼句子、最后点语法（时态、单复数、介词、人称）"
                : subj.contains("数学")
                ? "先说清数量关系，再一步一步算给看，最后落到运算里的坑"
                : "先讲字音字形字义，再讲句子或诗句的意思，最后落到作者、朝代和写法";
        String pit = subj.contains("英语") ? "英语题盯单复数、时态、介词、人称、冠词"
                : subj.contains("数学") ? "数学题盯进位退位、单位、运算顺序、符号"
                : "语文题盯多音字、形近字、笔画笔顺、作者朝代";
        sb.append("你是").append(who).append(subj).append("老师，也就是这个班的")
                .append(subj).append("科任老师，正在教室里给刚答错这道题的学生当面讲评。\n");
        sb.append("请用").append(subj).append("老师上课的口吻，按本学科的思路讲：")
                .append(view).append("。不要写成通用 AI 助手，不要自称 AI。\n\n");
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
        if (wrong) {
            sb.append("孩子选了：").append(letter(chosenIdx)).append(". ")
                    .append(options.get(chosenIdx)).append("\n");
        }
        sb.append("\n请按下面四段讲，全程保持科任老师的身份和课堂口吻，句子要短，每段单独一段：\n");
        sb.append("【结论】直接说正确答案是什么、是什么意思，一句话给结果；")
                .append("禁止把题目复述一遍，禁止写「这道题问的是…」。\n");
        sb.append("【拆解】把知识点一个一个拆开讲，第一句就进正题：")
                .append("英语题逐个词说意思（如 He is a boy. → He 他、is 是、a 一个、boy 男孩，")
                .append("合起来「他是个男孩」）并讲清句子怎么拼；")
                .append("数学题直接讲数量关系与分步演算（如「减 199 可以先减 200 再补回 1」），")
                .append("禁止把题干里的数字用中文读一遍，不写「487 是四百八十七，199 是一百九十九」这种空话；")
                .append("语文题说清字的音形义、笔画笔顺或诗句字词为什么这么用。\n");
        sb.append("【看其他选项】除正确答案外，把其余每个选项按字母逐个点名，")
                .append("说清它是什么意思、对应哪句英文／哪个算法／哪个作者，为什么不对；");
        if (wrong) {
            sb.append("其中孩子选的「").append(options.get(chosenIdx))
                    .append("」要重点讲，说清最容易混在哪。\n");
        } else {
            sb.append("点出最容易和正确答案混的那个。\n");
        }
        sb.append("【易错点】点出这道题真正的坑，").append(pit)
                .append("；最后给一句能记住的判断口诀，这一段写完就收尾。\n");
        sb.append("四段都必须写出来，不许合并、不许省略，也不许再加【举个例子】【举一反三】")
                .append("或任何新段落；讲完【易错点】立刻结束，不要另写总结、练习和鼓励。")
                .append("每段都以【】里的标签开头，不要另写标题，")
                .append("不要写「小朋友」「别着急」这类空泛安慰。\n");
        sb.append("所有段落一律不写废话：不把数字读成中文、不把题目或选项原文再抄一遍、")
                .append("不写「题目给的是」「我们来看」「先看题目」这类开场白。\n");
        sb.append("每一段都要收在完整句子上，【易错点】必须写完并以句号结束，")
                .append("不要停在「所以答案是」这种半句；字数吃紧时先保证四段齐全，")
                .append("别在任意一段中间收尾。\n");
        if (lv == DETAIL_BRIEF) {
            sb.append("每段一两句话，总字数 90–130 字。");
        } else if (lv == DETAIL_DEEP) {
            sb.append("每段讲透，总字数 320–420 字。");
        } else {
            sb.append("总字数 200–280 字。");
        }
        return sb.toString();
    }

    /** 后台配置里的讲解档位，缺字段或越界一律回落标准档 */
    public static int detailLevel(JSONObject cfg) {
        return detailLevelValue(cfg == null ? DETAIL_STANDARD
                : cfg.optInt("aiDetail", DETAIL_STANDARD));
    }

    static int detailLevelValue(int v) {
        return v < DETAIL_BRIEF || v > DETAIL_DEEP ? DETAIL_STANDARD : v;
    }

    /**
     * 中文一字约 1～1.5 token，但分层讲解下模型常写到字数上限的两三倍，
     * token 给紧了会在最后一段的半句处被服务端腰斩（finish_reason=length）。
     * 这里按目标字数的 5～6 倍留余量，实际消耗由模型自己控制。
     */
    static int maxTokens(int detail) {
        int lv = detailLevelValue(detail);
        return lv == DETAIL_BRIEF ? 900 : (lv == DETAIL_DEEP ? 3200 : 2400);
    }

    /** 面板兜底长度：正文本来就能翻页，这里只挡模型彻底跑飞，正常讲解碰不到 */
    static int textLimit(int detail) {
        int lv = detailLevelValue(detail);
        return lv == DETAIL_BRIEF ? 700 : (lv == DETAIL_DEEP ? 2600 : 1800);
    }

    static String letter(int i) {
        return i >= 0 && i < 26 ? String.valueOf((char) ('A' + i)) : "?";
    }

    // ------------------------------------------------------------------ 正文清洗

    /** 「487 是四百八十七，」这类把数字读一遍的废话片段 */
    private static final Pattern READ_NUMBER = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*是\\s*[零〇一二三四五六七八九十百千万亿两]{2,}\\s*[，,、；;]?\\s*");

    /** 整句只在罗列数字读法时，连句子一起丢掉，免得留下孤立标点 */
    private static final Pattern READ_ONLY_SENTENCE = Pattern.compile(
            "(?:\\d+(?:\\.\\d+)?\\s*是\\s*[零〇一二三四五六七八九十百千万亿两]{2,}\\s*[，,、；;]?\\s*)+[。！？]?");

    /** 已取消的【举个例子】【举一反三】：旧缓存或模型习惯性补写时整段丢掉 */
    private static final Pattern DROPPED_SECTIONS = Pattern.compile(
            "(?s)[ \\t]*【(?:举个例子|举一反三)】.*?(?=【|$)");

    /**
     * 提示词已明令禁止读数字和额外的两段，这里再做一道兜底：
     * 只删「数字 + 是 + 中文读数」这一种确定的废话，不碰公式、词义和演算步骤。
     * 旧缓存读出来时也过一遍，家长不用为了改版专门清缓存。
     */
    static String tidy(String text) {
        String s = text == null ? "" : text.trim();
        if (s.length() == 0) {
            return "";
        }
        s = DROPPED_SECTIONS.matcher(s).replaceAll("");
        s = READ_ONLY_SENTENCE.matcher(s).replaceAll("");
        s = READ_NUMBER.matcher(s).replaceAll("");
        s = s.replaceAll("[，,、；;]\\s*(?=[。！？])", "");
        s = s.replaceAll("(?m)^[ \\t]*[，,、；;。！？]+", "");
        s = s.replaceAll("[。！？]{2,}", "。");
        s = s.replaceAll("[ \\t]{2,}", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    /** 模型是不是因为 token 用完才停的（这种回答末尾往往是半句） */
    static boolean lengthCut(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray ch = o.optJSONArray("choices");
            return ch != null && ch.length() > 0
                    && "length".equals(ch.getJSONObject(0).optString("finish_reason", ""));
        } catch (Throwable e) {
            return false;
        }
    }

    /** 万一重试后仍被腰斩，宁可少显示半句，也别把「变式：623 减」这种断句给孩子看 */
    static String dropHalfSentence(String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() == 0) {
            return t;
        }
        char last = t.charAt(t.length() - 1);
        if (last == '。' || last == '！' || last == '？' || last == '”' || last == '」') {
            return t;
        }
        int cut = -1;
        for (int pass = 0; pass < 2 && cut < 0; pass++) {
            for (int i = t.length() - 2; i >= 0; i--) {
                char c = t.charAt(i);
                boolean stop = pass == 0
                        ? (c == '。' || c == '！' || c == '？' || c == '\n')
                        : (c == '，' || c == '；');
                if (stop) {
                    cut = i + 1;
                    break;
                }
            }
        }
        return cut > 0 ? t.substring(0, cut).trim() : t;
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
            return t.length() == 0 ? null : tidy(t);
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
        /** 模型返回 finish_reason=length：正文是半句停下的，需要换更大额度重试 */
        public boolean truncated;

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

    /**
     * 先按档位要一次；若模型是因 token 用完停在半句（finish_reason=length），
     * 把额度翻倍再要一次，尽量把四段讲完整，仍不完整才由 dropHalfSentence 收尾。
     */
    static Res request(JSONObject cfg, String prompt) {
        int base = maxTokens(detailLevel(cfg));
        Res r = requestOnce(cfg, prompt, base);
        if (r.ok() && r.truncated) {
            int bigger = Math.min(6000, base * 2);
            if (bigger > base) {
                Res again = requestOnce(cfg, prompt, bigger);
                if (again.ok() && again.text.length() > r.text.length()) {
                    r = again;
                }
            }
        }
        if (r.ok()) {
            String t = clip(r.text, detailLevel(cfg));
            if (r.truncated) {
                t = dropHalfSentence(t);
                if (t.length() < 40) {
                    r.text = null;
                    r.error = "模型这次没把讲解写完整，点「重试」再来一次";
                    return r;
                }
            }
            r.text = t;
        }
        return r;
    }

    private static Res requestOnce(JSONObject cfg, String prompt, int tokens) {
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
            body.put("max_tokens", tokens);
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
            r.text = text;
            r.truncated = lengthCut(raw);
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
        return clip(text, DETAIL_STANDARD);
    }

    /**
     * 正文能翻页，这里只挡模型彻底跑飞；真要截也截在完整句子末尾，
     * 不再用「硬切字符 + …」把孩子留在半句话上。
     */
    static String clip(String text, int detail) {
        String t = tidy(text);
        int max = textLimit(detail);
        if (t.length() <= max) {
            return t;
        }
        String cut = t.substring(0, max);
        int floor = Math.max(0, max - 200);
        int end = -1;
        for (int pass = 0; pass < 2 && end < 0; pass++) {
            for (int i = cut.length() - 1; i >= floor; i--) {
                char c = cut.charAt(i);
                boolean stop = pass == 0
                        ? (c == '。' || c == '！' || c == '？' || c == '\n')
                        : (c == '，' || c == '；');
                if (stop) {
                    end = i + 1;
                    break;
                }
            }
        }
        return (end > max / 2 ? cut.substring(0, end) : cut).trim();
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
