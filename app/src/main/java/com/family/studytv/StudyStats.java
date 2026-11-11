package com.family.studytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 学习统计：按「科目 + 题型」累计答题数与错题数，供后台出柱状图 / 雷达图。
 *
 * <p>存两段小 JSON 在 {@code studytv} 偏好里，量级很小（题型固定二十来个、日榜只留 14 天），
 * 每次答题只写一次 {@code commit()}，不会影响答题节奏。
 *
 * <p>口径：<b>答题次数</b>按「每次点选项」计；同一道题答错后再答对，会各记一次
 * （答错 +1 错题、答对 +1 答对），这样统计的是「这类题孩子错得多不多」而不是「错题库里剩几道」。
 */
public final class StudyStats {

    private static final String FILE = "studytv";
    private static final String K_SINCE = "stats_since";
    private static final String K_TOTAL = "stats_total";
    private static final String K_CORRECT = "stats_correct";
    private static final String K_TYPES = "stats_types";
    private static final String K_DAYS = "stats_days";
    /** 日榜保留天数 */
    private static final int KEEP_DAYS = 14;

    private static final Object LOCK = new Object();

    private StudyStats() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 记一次作答。type 传 null 时按题干现算。 */
    public static void record(Context c, String subject, String type, boolean correct) {
        String sub = subject == null || subject.trim().length() == 0 ? QType.OTHER : subject.trim();
        String ty = type == null || type.trim().length() == 0 ? QType.OTHER : type.trim();
        String key = sub + "|" + ty;
        String day = day(System.currentTimeMillis());
        synchronized (LOCK) {
            SharedPreferences p = sp(c);
            JSONObject types = readObject(p.getString(K_TYPES, ""));
            JSONObject one = types.optJSONObject(key);
            if (one == null) {
                one = new JSONObject();
                try {
                    one.put("s", sub);
                    one.put("n", ty);
                    one.put("t", 0);
                    one.put("w", 0);
                } catch (Throwable ignored) {
                }
            }
            int t = one.optInt("t", 0) + 1;
            int w = one.optInt("w", 0) + (correct ? 0 : 1);
            try {
                one.put("t", t);
                one.put("w", w);
                types.put(key, one);
            } catch (Throwable ignored) {
            }

            JSONObject days = readObject(p.getString(K_DAYS, ""));
            JSONObject d = days.optJSONObject(day);
            if (d == null) {
                d = new JSONObject();
            }
            try {
                d.put("t", d.optInt("t", 0) + 1);
                d.put("w", d.optInt("w", 0) + (correct ? 0 : 1));
                days.put(day, d);
            } catch (Throwable ignored) {
            }
            pruneDays(days);

            SharedPreferences.Editor e = p.edit()
                    .putString(K_TYPES, types.toString())
                    .putString(K_DAYS, days.toString())
                    .putInt(K_TOTAL, p.getInt(K_TOTAL, 0) + 1)
                    .putInt(K_CORRECT, p.getInt(K_CORRECT, 0) + (correct ? 1 : 0));
            if (!p.contains(K_SINCE) || p.getString(K_SINCE, "").length() == 0) {
                e.putString(K_SINCE, day);
            }
            e.commit();   // 同步落盘：答题中途被系统杀掉也不丢这次的统计
        }
    }

    /** 后台一次性取走的所有统计口径。 */
    public static JSONObject snapshot(Context c) {
        SharedPreferences p = sp(c);
        int total = p.getInt(K_TOTAL, 0);
        int correct = p.getInt(K_CORRECT, 0);
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("since", p.getString(K_SINCE, ""));
            out.put("total", total);
            out.put("correct", correct);
            out.put("wrong", Math.max(0, total - correct));
            out.put("rate", total <= 0 ? 0d : round1(correct * 100d / total));

            JSONObject types = readObject(p.getString(K_TYPES, ""));
            List<JSONObject> rows = new ArrayList<JSONObject>();
            Iterator<String> it = types.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject one = types.optJSONObject(key);
                if (one == null) {
                    continue;
                }
                int t = one.optInt("t", 0);
                int w = one.optInt("w", 0);
                if (t <= 0) {
                    continue;
                }
                JSONObject row = new JSONObject();
                row.put("key", key);
                row.put("subject", one.optString("s", key.contains("|")
                        ? key.substring(0, key.indexOf('|')) : ""));
                row.put("type", one.optString("n", key.contains("|")
                        ? key.substring(key.indexOf('|') + 1) : key));
                row.put("total", t);
                row.put("wrong", w);
                row.put("wrongRate", round1(w * 100d / t));
                rows.add(row);
            }
            Collections.sort(rows, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    int d = b.optInt("wrong", 0) - a.optInt("wrong", 0);
                    if (d != 0) {
                        return d;
                    }
                    return b.optInt("total", 0) - a.optInt("total", 0);
                }
            });
            out.put("types", new JSONArray(rows));

            JSONObject bySub = new JSONObject();
            for (JSONObject r : rows) {
                String s = r.optString("subject", QType.OTHER);
                JSONObject o = bySub.optJSONObject(s);
                if (o == null) {
                    o = new JSONObject();
                }
                try {
                    o.put("total", o.optInt("total", 0) + r.optInt("total", 0));
                    o.put("wrong", o.optInt("wrong", 0) + r.optInt("wrong", 0));
                    bySub.put(s, o);
                } catch (Throwable ignored) {
                }
            }
            JSONArray subs = new JSONArray();
            Iterator<String> sit = bySub.keys();
            while (sit.hasNext()) {
                String s = sit.next();
                JSONObject o = bySub.optJSONObject(s);
                JSONObject one = new JSONObject();
                one.put("subject", s);
                one.put("total", o == null ? 0 : o.optInt("total", 0));
                one.put("wrong", o == null ? 0 : o.optInt("wrong", 0));
                subs.put(one);
            }
            out.put("subjects", subs);

            JSONObject days = readObject(p.getString(K_DAYS, ""));
            JSONArray dl = new JSONArray();
            List<String> keys = new ArrayList<String>();
            Iterator<String> dit = days.keys();
            while (dit.hasNext()) {
                keys.add(dit.next());
            }
            Collections.sort(keys);
            int from = Math.max(0, keys.size() - KEEP_DAYS);
            for (String k : keys.subList(from, keys.size())) {
                JSONObject d = days.optJSONObject(k);
                JSONObject one = new JSONObject();
                one.put("date", k);
                one.put("total", d == null ? 0 : d.optInt("t", 0));
                one.put("wrong", d == null ? 0 : d.optInt("w", 0));
                dl.put(one);
            }
            out.put("days", dl);
        } catch (Throwable e) {
            try {
                out.put("ok", false);
                out.put("error", String.valueOf(e.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static void reset(Context c) {
        synchronized (LOCK) {
            sp(c).edit()
                    .remove(K_SINCE).remove(K_TOTAL).remove(K_CORRECT)
                    .remove(K_TYPES).remove(K_DAYS).commit();
        }
    }

    private static JSONObject readObject(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return new JSONObject();
        }
        try {
            JSONObject o = new JSONObject(raw);
            return o == null ? new JSONObject() : o;
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    /** 日榜超过 14 天就把最老的删掉，避免偏好文件无限长大 */
    private static void pruneDays(JSONObject days) {
        if (days.length() <= KEEP_DAYS) {
            return;
        }
        List<String> keys = new ArrayList<String>();
        Iterator<String> it = days.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        Collections.sort(keys);
        for (int i = 0; i < keys.size() - KEEP_DAYS; i++) {
            days.remove(keys.get(i));
        }
    }

    private static String day(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(Locale.CHINA, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
