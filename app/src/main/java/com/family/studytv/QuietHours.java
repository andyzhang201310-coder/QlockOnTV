package com.family.studytv;

import java.util.Calendar;

/**
 * 免打扰时段（quiet hours）的时间计算。
 *
 * <p>只处理「一天中的第几分钟」，不碰 SharedPreferences，方便单测。
 * 支持跨午夜窗口（例如 22:00–07:00）；{@code start == end} 视为关闭，永不静默。
 *
 * <p>为什么单独抽出来：电视待机时被唤醒完全是闹钟造成的，
 * 免打扰期间必须「既不弹题、也不留唤醒闹钟」，这段判断一点错都不能有。
 */
public final class QuietHours {

    public static final int MINUTES_PER_DAY = 24 * 60;

    private QuietHours() {
    }

    /** "HH:mm" → 一天中的分钟数；非法返回 -1 */
    public static int parse(String hhmm) {
        if (hhmm == null) {
            return -1;
        }
        String s = hhmm.trim();
        int i = s.indexOf(':');
        if (i <= 0) {
            return -1;
        }
        try {
            int h = Integer.parseInt(s.substring(0, i).trim());
            int m = Integer.parseInt(s.substring(i + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 一天中的分钟数 → "HH:mm" */
    public static String format(int minuteOfDay) {
        int m = normalize(minuteOfDay);
        return two(m / 60) + ":" + two(m % 60);
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static int normalize(int minuteOfDay) {
        int m = minuteOfDay % MINUTES_PER_DAY;
        return m < 0 ? m + MINUTES_PER_DAY : m;
    }

    /** 某个时刻属于一天中的第几分钟 */
    public static int minuteOfDay(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /**
     * 该分钟数是否落在 [start, end) 窗口内。
     * 左闭右开：22:00–07:00 时，07:00 整点已经不算免打扰。
     */
    public static boolean inWindow(int minuteOfDay, int start, int end) {
        int s = normalize(start);
        int e = normalize(end);
        if (s == e) {
            return false;
        }
        int m = normalize(minuteOfDay);
        if (s < e) {
            return m >= s && m < e;
        }
        // 跨午夜
        return m >= s || m < e;
    }

    public static boolean inQuiet(long ts, int start, int end) {
        return inWindow(minuteOfDay(ts), start, end);
    }

    /**
     * 把某个时刻顺延到「最近的允许弹窗时刻」：
     * 不在免打扰窗口内原样返回；在窗口内则返回本次窗口结束的那一刻。
     */
    public static long nextAllowedAt(long ts, int start, int end) {
        int s = normalize(start);
        int e = normalize(end);
        if (s == e || !inQuiet(ts, s, e)) {
            return ts;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, e / 60);
        c.set(Calendar.MINUTE, e % 60);
        if (c.getTimeInMillis() <= ts) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }
}
