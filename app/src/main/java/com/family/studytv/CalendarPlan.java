package com.family.studytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 日历计划（v1.7.31）：按「上学日 / 周末 / 法定节假日」三种日类型套用不同弹题时段。
 *
 * <p>家长要的规则是：孩子上学期间大人看电视不被打扰，放学后和周末/假期才管：
 * <ul>
 *   <li>上学日：07:00–17:30 大人豁免（不弹题），17:30–23:00 弹题；</li>
 *   <li>周末 / 法定节假日：06:00–23:00 弹题，其余时间不弹。</li>
 * </ul>
 * 三段起止时间都能在后台改，日类型也能手动覆盖（寒暑假、临时调课）。
 *
 * <p>日类型优先级：家长手动覆盖 ＞ 网络同步/内置放假调休表 ＞ 按星期推断。
 * 内置表是国务院办公厅公布的 2027 年安排，电视断网时照样能正确区分假期与调休上班日；
 * 联网后可从 holiday-cn 之类的 JSON 数据源按年同步，自动覆盖到后续年份。
 *
 * <p>本类只做纯计算与数据解析，网络拉取也全部由调用方放在子线程执行；
 * 不直接持有任何界面或生命周期对象，方便 Robolectric 单测。
 */
public final class CalendarPlan {

    public static final String SCHOOL = "school";
    public static final String WEEKEND = "weekend";
    public static final String HOLIDAY = "holiday";

    /** holiday-cn 每年一个 JSON，{year} 由同步时替换 */
    public static final String DEFAULT_URL =
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json";

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final long SYNC_INTERVAL_MS = 24L * 3600 * 1000;

    /**
     * 国务院办公厅《关于 2027 年部分节假日安排的通知》（国办发明电〔2026〕10 号，2026-11-05）。
     * 这里把整段连休日都记作 off（含与周末连休的周末），把明确「上班」的调休日记作 work。
     */
    private static final String[] BUILTIN_OFF = {
            "2027-01-01", "2027-01-02", "2027-01-03",
            "2027-02-05", "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09",
            "2027-02-10", "2027-02-11", "2027-02-12", "2027-02-13",
            "2027-04-03", "2027-04-04", "2027-04-05",
            "2027-05-01", "2027-05-02", "2027-05-03", "2027-05-04", "2027-05-05",
            "2027-06-09", "2027-09-15",
            "2027-10-01", "2027-10-02", "2027-10-03", "2027-10-04",
            "2027-10-05", "2027-10-06", "2027-10-07"
    };
    private static final String[] BUILTIN_WORK = {
            "2027-01-31", "2027-02-14", "2027-05-08", "2027-09-26", "2027-10-09"
    };

    private CalendarPlan() {
    }

    // ------------------------------------------------------------------ 默认模板

    public static JSONObject defaultPlans() {
        JSONObject root = new JSONObject();
        try {
            root.put(SCHOOL, plan("17:30", "23:00", "07:00", "17:30"));
            root.put(WEEKEND, plan("06:00", "23:00", "", ""));
            root.put(HOLIDAY, plan("06:00", "23:00", "", ""));
        } catch (Throwable ignored) {
        }
        return root;
    }

    private static JSONObject plan(String quizStart, String quizEnd,
                                   String exemptStart, String exemptEnd) {
        JSONObject o = new JSONObject();
        try {
            o.put("quizStart", quizStart);
            o.put("quizEnd", quizEnd);
            o.put("exemptStart", exemptStart);
            o.put("exemptEnd", exemptEnd);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static String typeName(String type) {
        if (WEEKEND.equals(type)) {
            return "周末";
        }
        if (HOLIDAY.equals(type)) {
            return "法定假期";
        }
        return "上学日";
    }

    /** 白名单校验后台传来的日计划，非法时间一律回退到默认模板，避免把服务判死。 */
    public static JSONObject sanitizePlans(JSONObject in) {
        JSONObject out = defaultPlans();
        if (in == null) {
            return out;
        }
        for (String type : new String[]{SCHOOL, WEEKEND, HOLIDAY}) {
            JSONObject src = in.optJSONObject(type);
            if (src == null) {
                continue;
            }
            JSONObject dst = out.optJSONObject(type);
            if (dst == null) {
                continue;
            }
            copyTime(src, dst, "quizStart");
            copyTime(src, dst, "quizEnd");
            copyTime(src, dst, "exemptStart");
            copyTime(src, dst, "exemptEnd");
        }
        return out;
    }

    /** 允许清空（豁免时段可留空），但非空必须是合法 HH:mm。 */
    private static void copyTime(JSONObject src, JSONObject dst, String key) {
        try {
            if (!src.has(key)) {
                return;
            }
            String v = src.optString(key, "").trim();
            if (v.length() == 0) {
                dst.put(key, "");
                return;
            }
            if (QuietHours.parse(v) >= 0) {
                dst.put(key, v);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 日期与日类型

    public static String dateKey(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(ts));
    }

    /** 0=周一 … 6=周日，与 Rules.weekIndex 保持一致 */
    public static int weekIndex(long ts) {
        return Rules.weekIndex(ts);
    }

    /** 内置表：{date,off,name} 数组，off=true 放假、off=false 调休上班 */
    public static JSONArray builtinDays() {
        JSONArray a = new JSONArray();
        putSpan(a, BUILTIN_OFF, true, "2027年法定假期");
        putSpan(a, BUILTIN_WORK, false, "调休上班");
        return a;
    }

    private static void putSpan(JSONArray a, String[] dates, boolean off, String name) {
        for (String d : dates) {
            try {
                JSONObject o = new JSONObject();
                o.put("date", d);
                o.put("off", off);
                o.put("name", name);
                a.put(o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 内置表 + 网络同步结果，按日期去重；同步数据后写，优先于内置表。 */
    public static JSONArray mergedDays(String syncedJson) {
        Map<String, JSONObject> map = new LinkedHashMap<String, JSONObject>();
        addAll(map, builtinDays());
        if (syncedJson != null && syncedJson.trim().length() > 0) {
            try {
                addAll(map, new JSONArray(syncedJson));
            } catch (Throwable ignored) {
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : map.values()) {
            out.put(o);
        }
        return out;
    }

    private static void addAll(Map<String, JSONObject> map, JSONArray a) {
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String d = o.optString("date", "");
            if (DATE.matcher(d).matches()) {
                map.put(d, o);
            }
        }
    }

    /** 手动覆盖：{"2027-02-20":"holiday"}；只接受三种合法类型与合法日期。 */
    public static JSONObject sanitizeOverrides(JSONObject in) {
        JSONObject out = new JSONObject();
        if (in == null) {
            return out;
        }
        for (java.util.Iterator<String> it = in.keys(); it.hasNext() && out.length() < 200; ) {
            try {
                String k = it.next();
                String v = in.optString(k, "");
                if (DATE.matcher(k).matches()
                        && (SCHOOL.equals(v) || WEEKEND.equals(v) || HOLIDAY.equals(v))) {
                    out.put(k, v);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 该日期属于哪种日类型 */
    public static String dayType(JSONObject cfg, long ts) {
        String key = dateKey(ts);
        JSONObject overrides = cfg == null ? null : cfg.optJSONObject("calendarOverrides");
        if (overrides != null && overrides.has(key)) {
            String v = overrides.optString(key, "");
            if (SCHOOL.equals(v) || WEEKEND.equals(v) || HOLIDAY.equals(v)) {
                return v;
            }
        }
        JSONArray days = cfg == null ? null : cfg.optJSONArray("calendarDays");
        if (days != null) {
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d != null && key.equals(d.optString("date", ""))) {
                    return d.optBoolean("off", true) ? HOLIDAY : SCHOOL;
                }
            }
        }
        int w = weekIndex(ts);
        return (w == 5 || w == 6) ? WEEKEND : SCHOOL;
    }

    // ------------------------------------------------------------------ 时段判定

    /**
     * 日历计划下此刻是否允许弹题：先判该日类型的「大人豁免」，再判「弹题时段」。
     * 免打扰 / 按星期的大人豁免 / 网课时段由 Rules 在外层先拦掉，这里不重复。
     */
    public static boolean quizAllowedAt(JSONObject cfg, long ts) {
        JSONObject plans = cfg == null ? null : cfg.optJSONObject("dayPlans");
        if (plans == null) {
            plans = defaultPlans();
        }
        String type = dayType(cfg, ts);
        JSONObject p = plans.optJSONObject(type);
        if (p == null) {
            p = defaultPlans().optJSONObject(type);
        }
        if (p == null) {
            return false;
        }
        int now = QuietHours.minuteOfDay(ts);
        int es = QuietHours.parse(p.optString("exemptStart", ""));
        int ee = QuietHours.parse(p.optString("exemptEnd", ""));
        if (es >= 0 && ee >= 0 && es != ee && QuietHours.inWindow(now, es, ee)) {
            return false;
        }
        int qs = QuietHours.parse(p.optString("quizStart", ""));
        int qe = QuietHours.parse(p.optString("quizEnd", ""));
        if (qs < 0 || qe < 0 || qs == qe) {
            return false;
        }
        return QuietHours.inWindow(now, qs, qe);
    }

    /** 给家长看的命中原因，解决「配了规则却看不出生没生效」。 */
    public static String explain(JSONObject cfg, long ts) {
        String type = dayType(cfg, ts);
        JSONObject plans = cfg == null ? null : cfg.optJSONObject("dayPlans");
        if (plans == null) {
            plans = defaultPlans();
        }
        JSONObject p = plans.optJSONObject(type);
        if (p == null) {
            p = defaultPlans().optJSONObject(type);
        }
        String name = typeName(type) + " · " + dateKey(ts);
        int now = QuietHours.minuteOfDay(ts);
        int es = QuietHours.parse(p.optString("exemptStart", ""));
        int ee = QuietHours.parse(p.optString("exemptEnd", ""));
        if (es >= 0 && ee >= 0 && es != ee && QuietHours.inWindow(now, es, ee)) {
            return name + "：大人豁免 " + p.optString("exemptStart") + "–"
                    + p.optString("exemptEnd") + "，不弹题";
        }
        int qs = QuietHours.parse(p.optString("quizStart", ""));
        int qe = QuietHours.parse(p.optString("quizEnd", ""));
        if (qs >= 0 && qe >= 0 && qs != qe && QuietHours.inWindow(now, qs, qe)) {
            return name + "：弹题时段 " + p.optString("quizStart") + "–"
                    + p.optString("quizEnd") + "，会弹题";
        }
        return name + "：不在弹题时段（" + p.optString("quizStart") + "–"
                + p.optString("quizEnd") + "），不弹题";
    }

    // ------------------------------------------------------------------ 网络日历同步

    /** 解析 holiday-cn 的 {year,days:[{date,isOffDay,name}]}；空 days 视为「尚未公布」。 */
    public static JSONArray parseRemote(String json) {
        JSONArray out = new JSONArray();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray days = root.optJSONArray("days");
            if (days == null) {
                return out;
            }
            for (int i = 0; i < days.length() && out.length() < 400; i++) {
                JSONObject d = days.optJSONObject(i);
                if (d == null) {
                    continue;
                }
                String date = d.optString("date", "").trim();
                if (!DATE.matcher(date).matches()) {
                    continue;
                }
                String nm = d.optString("name", "");
                if (nm.length() > 20) {
                    nm = nm.substring(0, 20);
                }
                JSONObject item = new JSONObject();
                item.put("date", date);
                item.put("off", d.optBoolean("isOffDay", false));
                item.put("name", nm);
                out.put(item);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "QlockOnTV/1.7.31");
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /**
     * 拉取「今年 + 明年」两份 JSON 并合并。必须在子线程调用。
     * 返回可直接展示给家长的状态文字；任一年失败不影响另一年，全失败时保留旧缓存与内置表。
     */
    public static synchronized String sync(Context c, String urlPattern) {
        String tpl = (urlPattern == null || urlPattern.trim().length() == 0)
                ? DEFAULT_URL : urlPattern.trim();
        int year = Calendar.getInstance().get(Calendar.YEAR);
        JSONArray all = new JSONArray();
        int years = 0;
        for (int y = year; y <= year + 1; y++) {
            try {
                JSONArray part = parseRemote(fetch(tpl.replace("{year}", String.valueOf(y))));
                if (part.length() == 0) {
                    continue;      // 空 days 是「当年安排未公布」占位，不能覆盖已缓存数据
                }
                for (int i = 0; i < part.length(); i++) {
                    all.put(part.opt(i));
                }
                years++;
            } catch (Throwable ignored) {
            }
        }
        if (years == 0) {
            String msg = "同步失败，继续使用内置日历";
            Prefs.setCalendarSyncStatus(msg);
            return msg;
        }
        Prefs.setCalendarDays(all.toString());
        Prefs.setCalendarSyncAt(System.currentTimeMillis());
        String status = "已同步 " + years + " 年 · " + all.length() + " 条放假/调休";
        Prefs.setCalendarSyncStatus(status);
        return status;
    }

    /** 距上次成功同步超过 24 小时才拉一次，供开机/服务启动调用（调用方负责放到子线程）。 */
    public static boolean syncIfDue(Context c, String urlPattern) {
        if (System.currentTimeMillis() - Prefs.calendarSyncAt() < SYNC_INTERVAL_MS) {
            return false;
        }
        sync(c, urlPattern);
        return true;
    }
}
