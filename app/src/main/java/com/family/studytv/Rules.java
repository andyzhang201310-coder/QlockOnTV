package com.family.studytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「管理规则」：家长在局域网后台设置的整套学习规则，参考 StudyOnTV 的 Rules 数据模型。
 *
 * <p>配置以一份 JSON 存在 studytv 偏好里，字段名尽量与参考项目一致，方便对照。
 * 这里分两类方法：
 * <ul>
 *   <li>纯计算（{@link #quizAllowedAt} / {@link #nextQuizAt} / {@link #inWindow} 等）：
 *       不碰 SharedPreferences，可直接单测；</li>
 *   <li>读写（{@link #get(Context)} / {@link #save(Context, JSONObject)} / 错题与轮数）：需要 Context。</li>
 * </ul>
 *
 * <p>本项目是「到点弹出全屏答题页 → 通关后退回电视继续计时」的模型，
 * 没有整机使用时长统计与系统设置拦截层，因此参考项目里依赖 UsageStats / 无障碍 /
 * 播放器的规则（免费时长、每日上限、强制休息、视频学习、星级奖励）不在本类里伪装实现。
 */
public final class Rules {

    private static final String FILE = "studytv";
    private static final String KEY_CONFIG = "rules_json";
    private static final String KEY_VERSION = "rules_version";
    private static final String KEY_ROUND_DATE = "rules_round_date";
    private static final String KEY_ROUND_COUNT = "rules_round_count";
    private static final String KEY_WRONG_TOTAL = "rules_wrong_total";
    private static final String KEY_WRONG_DATE = "rules_wrong_date";
    private static final String KEY_WRONG_IDS = "rules_wrong_ids";

    private static final int SLOTS_PER_DAY = 48;
    private static final int SLOT_MINUTES = 30;
    private static final int MAX_WRONG_IDS = 300;

    private Rules() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 默认值 / 存取

    /** 与参考项目 schema.ts 的 defaults 对齐；不适用的字段不写入。 */
    public static JSONObject defaults() {
        JSONObject o = new JSONObject();
        try {
            o.put("playMode", "round");              // round / streak / count / practice
            o.put("questionsPerRound", 5);
            o.put("passRatio", 1.0);                 // 1.0 = 全对才通关（保留题锁原有行为）
            o.put("shuffleOptions", true);
            o.put("wrongReviewSeconds", 5);
            o.put("fontScale", 100);
            o.put("roundsLimitEnabled", false);
            o.put("roundsPerDay", 8);
            o.put("sessionMinutes", 30);             // 通关后放行分钟，与「弹题间隔」同一个值
            o.put("scheduleMode", "control");        // control=只在可用时段弹题 / allow=可用时段免弹题
            o.put("allDay", true);
            o.put("windowStart", "18:30");
            o.put("windowEnd", "20:00");
            JSONArray week = new JSONArray();
            for (int d = 0; d < 7; d++) {
                week.put(repeat('1', SLOTS_PER_DAY));
            }
            o.put("weekSlots", week);
            o.put("quizSubjects", new JSONArray());  // 空 = 全部科目
            o.put("quizLevels", new JSONArray());    // 空 = 1-6 年级全部
            o.put("childSelectLevel", false);
            o.put("extraEnabled", false);
            o.put("wrongThreshold", 3);
            o.put("extraQuestions", 2);
            o.put("reviewEnabled", false);
            o.put("timePoints", new JSONArray());    // 网课时段：期间不弹题
            o.put("exemptWindows", new JSONArray());// 大人豁免：期间不弹题
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 读配置；任何字段缺失都用默认值补齐，老版本升级不会崩。 */
    public static JSONObject get(Context c) {
        JSONObject o = defaults();
        try {
            String raw = sp(c).getString(KEY_CONFIG, "");
            if (raw == null || raw.length() == 0) {
                return o;
            }
            JSONObject saved = new JSONObject(raw);
            for (java.util.Iterator<String> it = saved.keys(); it.hasNext(); ) {
                String k = it.next();
                o.put(k, saved.get(k));
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static int version(Context c) {
        return sp(c).getInt(KEY_VERSION, 1);
    }

    /**
     * 首页「参数设置」直接写 Prefs，而答题页的题量与出题范围读的是规则配置。
     * 把两边共享的四个字段从 Prefs 回写进规则 JSON，避免首页改完被规则页的旧值覆盖。
     */
    public static synchronized void syncFromPrefs(Context c) {
        try {
            JSONObject o = get(c);
            o.put("questionsPerRound", Math.max(1, Math.min(100, Prefs.quizCount())));
            o.put("sessionMinutes", Math.max(1, Math.min(720, Prefs.intervalMinutes())));
            JSONArray subs = new JSONArray();
            String s = Prefs.subjects();
            if (s != null) {
                for (String v : s.split(",")) {
                    if (v.trim().length() > 0) {
                        subs.put(v.trim());
                    }
                }
            }
            o.put("quizSubjects", subs);
            JSONArray lv = new JSONArray();
            String g = Prefs.grades();
            if (g != null) {
                for (String v : g.split(",")) {
                    if (v.trim().length() > 0) {
                        lv.put(v.trim());
                    }
                }
            }
            o.put("quizLevels", lv);
            SharedPreferences.Editor e = sp(c).edit();
            e.putString(KEY_CONFIG, o.toString());
            e.putInt(KEY_VERSION, version(c) + 1);
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 保存整份规则：白名单字段 + 逐项夹取范围，防止网页端传进畸形值把服务弄崩。
     * 范围（科目/年级）同时写回 Prefs，保证旧「参数设置」页与规则页看到的是同一份数据。
     */
    public static synchronized JSONObject save(Context c, JSONObject in) {
        JSONObject o = sanitize(in == null ? new JSONObject() : in);
        SharedPreferences.Editor e = sp(c).edit();
        e.putString(KEY_CONFIG, o.toString());
        e.putInt(KEY_VERSION, version(c) + 1);
        e.apply();
        try {
            Prefs.setQuizCount(o.getInt("questionsPerRound"));
            Prefs.setIntervalMinutes(o.getInt("sessionMinutes"));
            // 空数组表示「全部」，必须一并写回空串，否则规则页清空筛选后旧的科目/年级仍在生效
            JSONArray subs = o.optJSONArray("quizSubjects");
            if (subs != null) {
                Prefs.setSubjects(join(strList(subs), ","));
            }
            JSONArray lv = o.optJSONArray("quizLevels");
            if (lv != null) {
                Prefs.setGrades(join(strList(lv), ","));
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 只校验 / 补默认值，不落盘（给单测和 API 回显用）。 */
    public static JSONObject sanitize(JSONObject in) {
        JSONObject o = defaults();
        try {
            if (in == null) {
                return o;
            }
            str(o, in, "playMode", "round", "streak", "count", "practice");
            num(o, in, "questionsPerRound", 1, 100);
            ratio(o, in);
            bool(o, in, "shuffleOptions");
            num(o, in, "wrongReviewSeconds", 0, 300);
            num(o, in, "fontScale", 75, 200);
            bool(o, in, "roundsLimitEnabled");
            num(o, in, "roundsPerDay", 1, 100);
            num(o, in, "sessionMinutes", 1, 720);
            str(o, in, "scheduleMode", "control", "allow");
            bool(o, in, "allDay");
            time(o, in, "windowStart");
            time(o, in, "windowEnd");
            week(o, in);
            arr(o, in, "quizSubjects");
            arr(o, in, "quizLevels");
            bool(o, in, "childSelectLevel");
            bool(o, in, "extraEnabled");
            num(o, in, "wrongThreshold", 1, 100);
            num(o, in, "extraQuestions", 1, 100);
            bool(o, in, "reviewEnabled");
            windows(o, in, "timePoints");
            windows(o, in, "exemptWindows");
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static void str(JSONObject o, JSONObject in, String k, String... allowed) {
        try {
            String v = in.optString(k, "");
            for (String a : allowed) {
                if (a.equals(v)) {
                    o.put(k, a);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void num(JSONObject o, JSONObject in, String k, int min, int max) {
        try {
            if (!in.has(k)) {
                return;
            }
            int v = in.optInt(k, o.optInt(k));
            o.put(k, Math.max(min, Math.min(max, v)));
        } catch (Throwable ignored) {
        }
    }

    private static void ratio(JSONObject o, JSONObject in) {
        try {
            if (!in.has("passRatio")) {
                return;
            }
            double v = in.optDouble("passRatio", o.optDouble("passRatio", 1.0));
            if (Double.isNaN(v)) {
                return;
            }
            o.put("passRatio", Math.max(0.1d, Math.min(1.0d, v)));
        } catch (Throwable ignored) {
        }
    }

    private static void bool(JSONObject o, JSONObject in, String k) {
        try {
            if (in.has(k)) {
                o.put(k, in.optBoolean(k, o.optBoolean(k)));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void time(JSONObject o, JSONObject in, String k) {
        try {
            if (!in.has(k)) {
                return;
            }
            String v = in.optString(k, "");
            if (QuietHours.parse(v) >= 0) {
                o.put(k, v);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void arr(JSONObject o, JSONObject in, String k) {
        try {
            if (!in.has(k)) {
                return;
            }
            JSONArray a = in.optJSONArray(k);
            if (a == null) {
                return;
            }
            JSONArray clean = new JSONArray();
            for (int i = 0; i < a.length() && clean.length() < 40; i++) {
                Object v = a.opt(i);
                if (v instanceof String && ((String) v).length() <= 24) {
                    clean.put(v);
                } else if (v instanceof Integer || v instanceof Long) {
                    clean.put(String.valueOf(v));
                }
            }
            o.put(k, clean);
        } catch (Throwable ignored) {
        }
    }

    private static void week(JSONObject o, JSONObject in) {
        try {
            JSONArray a = in.optJSONArray("weekSlots");
            if (a == null || a.length() != 7) {
                return;
            }
            JSONArray clean = new JSONArray();
            for (int d = 0; d < 7; d++) {
                String s = a.optString(d, "");
                if (s.length() != SLOTS_PER_DAY || !s.matches("[01]{48}")) {
                    s = repeat('0', SLOTS_PER_DAY);
                }
                clean.put(s);
            }
            o.put("weekSlots", clean);
        } catch (Throwable ignored) {
        }
    }

    /** 网课时段 / 大人豁免：{id,name,days:[0..6],start:"HH:mm",end:"HH:mm"} */
    private static void windows(JSONObject o, JSONObject in, String k) {
        try {
            JSONArray a = in.optJSONArray(k);
            if (a == null) {
                return;
            }
            JSONArray clean = new JSONArray();
            for (int i = 0; i < a.length() && clean.length() < 50; i++) {
                Object raw = a.opt(i);
                if (!(raw instanceof JSONObject)) {
                    continue;
                }
                JSONObject w = (JSONObject) raw;
                String s = w.optString("start", "");
                String e = w.optString("end", "");
                if (QuietHours.parse(s) < 0 || QuietHours.parse(e) < 0 || s.equals(e)) {
                    continue;
                }
                JSONArray days = w.optJSONArray("days");
                if (days == null || days.length() == 0) {
                    continue;
                }
                JSONArray ds = new JSONArray();
                for (int d = 0; d < days.length(); d++) {
                    int v = days.optInt(d, -1);
                    if (v >= 0 && v <= 6) {
                        ds.put(v);
                    }
                }
                if (ds.length() == 0) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("id", w.optString("id", ""));
                item.put("name", w.optString("name", "未命名").substring(0,
                        Math.min(20, w.optString("name", "未命名").length())));
                item.put("days", ds);
                item.put("start", s);
                item.put("end", e);
                clean.put(item);
            }
            o.put(k, clean);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 纯计算：时段

    /** Calendar 的周日=1 … 周六=7，这里转成 0=周一 … 6=周日 */
    public static int weekIndex(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        int d = c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        return d < 0 ? d + 7 : d;
    }

    /** 当天第几分钟（0-1439） */
    public static int minuteOfDay(long ts) {
        return QuietHours.minuteOfDay(ts);
    }

    /** 单个时段时间窗口，支持跨午夜；start==end 视为无效 */
    public static boolean inWindow(long ts, int startMin, int endMin) {
        return QuietHours.inWindow(minuteOfDay(ts), startMin, endMin);
    }

    /** 一周 48 格（30 分钟一格）的可用时段是否命中此刻 */
    public static boolean weekSlotOn(JSONArray slots, long ts) {
        if (slots == null || slots.length() != 7) {
            return true;
        }
        String day = slots.optString(weekIndex(ts), "");
        if (day.length() != SLOTS_PER_DAY) {
            return true;
        }
        int slot = minuteOfDay(ts) / SLOT_MINUTES;
        return day.charAt(slot) == '1';
    }

    /**
     * 具名窗口（网课时段 / 大人豁免）是否命中。
     * 跨午夜时 days 表示「窗口开始那天」；例如周六 22:00–周日 07:00 只勾选周六。
     * 为了让家长按直觉勾选「周六、周日」也能覆盖，额外接受「结束日的星期」命中前一天窗口。
     */
    public static boolean inNamedWindow(JSONArray windows, long ts) {
        if (windows == null) {
            return false;
        }
        int today = weekIndex(ts);
        int yesterday = (today + 6) % 7;
        int now = minuteOfDay(ts);
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = windows.optJSONObject(i);
            if (w == null) {
                continue;
            }
            int s = QuietHours.parse(w.optString("start", ""));
            int e = QuietHours.parse(w.optString("end", ""));
            if (s < 0 || e < 0 || s == e) {
                continue;
            }
            JSONArray days = w.optJSONArray("days");
            if (days == null) {
                continue;
            }
            boolean dayHit = false;
            for (int d = 0; d < days.length(); d++) {
                if (days.optInt(d, -1) == today) {
                    dayHit = true;
                    break;
                }
            }
            // 同一天的窗口（不跨午夜）
            if (dayHit && QuietHours.inWindow(now, s, e)) {
                return true;
            }
            // 跨午夜窗口的后半段落在今天：要求昨天被勾选，且今天是结束日
            if (s > e) {
                boolean prevHit = false;
                for (int d = 0; d < days.length(); d++) {
                    if (days.optInt(d, -1) == yesterday) {
                        prevHit = true;
                        break;
                    }
                }
                if (prevHit && now < e) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 生效的「可用时段」：allDay 时全天；否则按起止时间或周计划。 */
    public static boolean inActiveWindow(JSONObject cfg, long ts) {
        if (cfg == null) {
            return true;
        }
        if (cfg.optBoolean("allDay", true)) {
            return true;
        }
        // 起止时间是每天的基础范围，周计划表（7×48 格）再按 30 分钟细化，两者取交集。
        // 默认周计划表全亮，等价于「只按起止时间」；点「清空」后整周都不弹题。
        int s = QuietHours.parse(cfg.optString("windowStart", ""));
        int e = QuietHours.parse(cfg.optString("windowEnd", ""));
        boolean inRange = s < 0 || e < 0 || s == e || inWindow(ts, s, e);
        return inRange && weekSlotOn(cfg.optJSONArray("weekSlots"), ts);
    }

    /**
     * 此刻是否允许自动弹题。
     * 优先级（由高到低）：免打扰 / 大人豁免 / 网课时段 → 一律不弹；其后看可用时段与「时段用途」。
     * 总开关 enabled / autoQuiz 由调用方先判断。
     */
    public static boolean quizAllowedAt(JSONObject cfg, long ts,
                                        boolean quietEnabled, int quietStart, int quietEnd) {
        if (cfg == null) {
            cfg = defaults();
        }
        if (quietEnabled && QuietHours.inQuiet(ts, quietStart, quietEnd)) {
            return false;
        }
        if (inNamedWindow(cfg.optJSONArray("exemptWindows"), ts)) {
            return false;
        }
        if (inNamedWindow(cfg.optJSONArray("timePoints"), ts)) {
            return false;
        }
        boolean active = inActiveWindow(cfg, ts);
        // control = 只在可用时段内需要先答题；allow = 可用时段是自由观看时间，其余时间才弹题
        boolean control = !"allow".equals(cfg.optString("scheduleMode", "control"));
        return control ? active : !active;
    }

    /**
     * 把时刻顺延到下一次允许弹题的整分钟，最多找 8 天。
     * 用于 dueAt 对齐和非唤醒闹钟，保证待机中的电视不会在禁止时段被点亮。
     */
    public static long nextQuizAt(JSONObject cfg, long ts,
                                  boolean quietEnabled, int quietStart, int quietEnd) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MINUTE, 1);
        for (int i = 0; i < 8 * 24 * 60; i++) {
            long t = c.getTimeInMillis();
            if (quizAllowedAt(cfg, t, quietEnabled, quietStart, quietEnd)) {
                return t;
            }
            c.add(Calendar.MINUTE, 1);
        }
        return ts + 24L * 3600 * 1000;
    }

    /** 常用封装：把「下一次弹题时刻」对齐到规则允许的最近时刻。 */
    public static long alignDue(Context c, long raw) {
        JSONObject cfg = get(c);
        boolean q = Prefs.quietEnabled();
        int qs = Prefs.quietStartMin();
        int qe = Prefs.quietEndMin();
        return quizAllowedAt(cfg, raw, q, qs, qe) ? raw : nextQuizAt(cfg, raw, q, qs, qe);
    }

    // ------------------------------------------------------------------ 每日轮数 / 加练 / 错题库

    private static String today(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    public static int roundsToday(Context c) {
        SharedPreferences p = sp(c);
        return today(System.currentTimeMillis()).equals(p.getString(KEY_ROUND_DATE, ""))
                ? p.getInt(KEY_ROUND_COUNT, 0) : 0;
    }

    public static void noteRound(Context c) {
        SharedPreferences p = sp(c);
        String d = today(System.currentTimeMillis());
        int n = d.equals(p.getString(KEY_ROUND_DATE, "")) ? p.getInt(KEY_ROUND_COUNT, 0) : 0;
        p.edit().putString(KEY_ROUND_DATE, d).putInt(KEY_ROUND_COUNT, n + 1).apply();
    }

    public static boolean roundsLeft(Context c, JSONObject cfg) {
        if (!cfg.optBoolean("roundsLimitEnabled", false)) {
            return true;
        }
        return roundsToday(c) < Math.max(1, cfg.optInt("roundsPerDay", 8));
    }

    /** 今天累计答错的题数（用于「答错累计加练」） */
    public static int wrongToday(Context c) {
        SharedPreferences p = sp(c);
        return today(System.currentTimeMillis()).equals(p.getString(KEY_WRONG_DATE, ""))
                ? p.getInt(KEY_WRONG_TOTAL, 0) : 0;
    }

    public static void noteWrong(Context c, String id) {
        if (id == null || id.length() == 0) {
            return;
        }
        SharedPreferences p = sp(c);
        String d = today(System.currentTimeMillis());
        int n = d.equals(p.getString(KEY_WRONG_DATE, "")) ? p.getInt(KEY_WRONG_TOTAL, 0) : 0;
        LinkedHashSet<String> ids = new LinkedHashSet<String>(wrongIds(c));
        ids.add(id);
        while (ids.size() > MAX_WRONG_IDS) {
            java.util.Iterator<String> it = ids.iterator();
            it.next();
            it.remove();
        }
        p.edit().putString(KEY_WRONG_DATE, d)
                .putInt(KEY_WRONG_TOTAL, n + 1)
                .putString(KEY_WRONG_IDS, join(new ArrayList<String>(ids), ","))
                .apply();
    }

    public static void clearWrong(Context c, String id) {
        Set<String> ids = new LinkedHashSet<String>(wrongIds(c));
        if (ids.remove(id)) {
            sp(c).edit().putString(KEY_WRONG_IDS, join(new ArrayList<String>(ids), ",")).apply();
        }
    }

    /** 家长手动清空错题库（间隔重复重新开始） */
    public static void clearWrongPool(Context c) {
        sp(c).edit().putString(KEY_WRONG_IDS, "")
                .putInt(KEY_WRONG_TOTAL, 0)
                .putString(KEY_WRONG_DATE, "")
                .apply();
    }

    public static Set<String> wrongIds(Context c) {
        String raw = sp(c).getString(KEY_WRONG_IDS, "");
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        Collections.addAll(out, raw.split(","));
        out.remove("");
        return out;
    }

    /** 累计答错到阈值后，每组额外加练几题 */
    public static int extraQuestions(Context c, JSONObject cfg) {
        if (!cfg.optBoolean("extraEnabled", false)) {
            return 0;
        }
        int threshold = Math.max(1, cfg.optInt("wrongThreshold", 3));
        int each = Math.max(1, cfg.optInt("extraQuestions", 2));
        int groups = wrongToday(c) / threshold;
        return Math.min(20, groups * each);
    }

    public static int questionCount(Context c, JSONObject cfg) {
        return Math.max(1, Math.min(100,
                cfg.optInt("questionsPerRound", 5) + extraQuestions(c, cfg)));
    }

    // ------------------------------------------------------------------ 小工具

    public static List<String> strList(JSONArray a) {
        List<String> out = new ArrayList<String>();
        if (a == null) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (s.length() > 0) {
                out.add(s);
            }
        }
        return out;
    }

    public static String join(List<String> v, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : v) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String repeat(char ch, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}
