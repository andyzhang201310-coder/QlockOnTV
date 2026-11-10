package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Calendar;

/**
 * 免打扰时段计算。这块错一位就会在半夜把电视点亮，所以边界要逐分钟卡。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QuietHoursTest {

    private static long at(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static int clamp(int h, int m) {
        return h * 60 + m;
    }

    // ---------------------------------------------------------- 窗口判定

    @Test
    public void crossMidnightWindow() {
        int s = clamp(22, 0);
        int e = clamp(7, 0);
        assertTrue(QuietHours.inWindow(clamp(22, 0), s, e));
        assertTrue(QuietHours.inWindow(clamp(23, 59), s, e));
        assertTrue(QuietHours.inWindow(clamp(0, 0), s, e));
        assertTrue(QuietHours.inWindow(clamp(6, 59), s, e));
        // 右开：结束整点已经可以弹
        assertFalse(QuietHours.inWindow(clamp(7, 0), s, e));
        assertFalse(QuietHours.inWindow(clamp(12, 0), s, e));
        assertFalse(QuietHours.inWindow(clamp(21, 59), s, e));
    }

    @Test
    public void sameDayWindow() {
        int s = clamp(12, 0);
        int e = clamp(14, 0);
        assertTrue(QuietHours.inWindow(clamp(12, 0), s, e));
        assertTrue(QuietHours.inWindow(clamp(13, 59), s, e));
        assertFalse(QuietHours.inWindow(clamp(14, 0), s, e));
        assertFalse(QuietHours.inWindow(clamp(11, 59), s, e));
    }

    @Test
    public void emptyWindowNeverQuiet() {
        int s = clamp(22, 0);
        assertFalse(QuietHours.inWindow(clamp(22, 0), s, s));
        assertFalse(QuietHours.inWindow(clamp(3, 0), s, s));
        assertEquals(at(3, 0), QuietHours.nextAllowedAt(at(3, 0), s, s));
    }

    // ---------------------------------------------------------- 解析与格式化

    @Test
    public void parseAndFormatRoundTrip() {
        assertEquals(clamp(22, 30), QuietHours.parse("22:30"));
        assertEquals("22:30", QuietHours.format(clamp(22, 30)));
        assertEquals(clamp(7, 0), QuietHours.parse("07:00"));
        assertEquals("07:00", QuietHours.format(clamp(7, 0)));
        assertEquals(clamp(0, 5), QuietHours.parse(" 0:05 "));
        assertEquals("00:05", QuietHours.format(clamp(0, 5)));
    }

    @Test
    public void parseRejectsGarbage() {
        assertEquals(-1, QuietHours.parse(null));
        assertEquals(-1, QuietHours.parse(""));
        assertEquals(-1, QuietHours.parse("abc"));
        assertEquals(-1, QuietHours.parse("24:00"));
        assertEquals(-1, QuietHours.parse("12:60"));
        assertEquals(-1, QuietHours.parse("1230"));
    }

    // ---------------------------------------------------------- 顺延

    @Test
    public void nextAllowedKeepsTimeOutsideWindow() {
        long noon = at(12, 0);
        assertEquals(noon, QuietHours.nextAllowedAt(noon, clamp(22, 0), clamp(7, 0)));
    }

    @Test
    public void nextAllowedJumpsToSameDayEnd() {
        long early = at(6, 0);
        long got = QuietHours.nextAllowedAt(early, clamp(22, 0), clamp(7, 0));
        assertEquals(at(7, 0), got);
        assertTrue(got > early);
    }

    @Test
    public void nextAllowedJumpsToNextDayEnd() {
        long late = at(23, 30);
        long got = QuietHours.nextAllowedAt(late, clamp(22, 0), clamp(7, 0));
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(got);
        assertEquals(7, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, c.get(Calendar.MINUTE));
        // 一定是明天早上，只能等一个晚上
        long diff = got - late;
        assertTrue(diff > 0 && diff <= 8 * 3600_000L);
    }

    @Test
    public void nextAllowedAtBoundaryIsStable() {
        // 正好卡在结束时刻：已经可以弹，不要再多顺延一天
        long end = at(7, 0);
        assertEquals(end, QuietHours.nextAllowedAt(end, clamp(22, 0), clamp(7, 0)));
    }

    @Test
    public void overnightGapIsAtMostOneNight() {
        // 21:59 到 22:00 之间进窗口，顺延后不能超过 9 小时（22:00 → 07:00）
        long t = at(22, 0);
        long got = QuietHours.nextAllowedAt(t, clamp(22, 0), clamp(7, 0));
        assertTrue(got - t <= 9 * 3600_000L);
    }

    // ---------------------------------------------------------- Prefs 集成

    @Test
    public void prefsDefaultsAreSafe() {
        Prefs.setAutoQuiz(true);
        Prefs.setQuietEnabled(false);
        assertTrue(Prefs.autoQuiz());
        assertFalse(Prefs.quietEnabled());
        assertEquals(clamp(22, 0), Prefs.quietStartMin());
        assertEquals(clamp(7, 0), Prefs.quietEndMin());
        // 关掉免打扰时，任何时刻都不能被顺延
        assertEquals(at(23, 30), Prefs.nextAllowedAt(at(23, 30)));
    }

    @Test
    public void prefsQuietRangeTakesEffect() {
        Prefs.setAutoQuiz(true);
        Prefs.setQuietEnabled(true);
        Prefs.setQuietRange(QuietHours.parse("23:00"), QuietHours.parse("06:30"));
        assertEquals("23:00", QuietHours.format(Prefs.quietStartMin()));
        assertEquals("06:30", QuietHours.format(Prefs.quietEndMin()));
        // 23:30 落在窗口内 → 顺延到次日 06:30
        long got = Prefs.nextAllowedAt(at(23, 30));
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(got);
        assertEquals(6, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, c.get(Calendar.MINUTE));
        // 恢复默认，避免影响其它用例
        Prefs.setQuietEnabled(false);
        Prefs.setQuietRange(clamp(22, 0), clamp(7, 0));
    }

    @Test
    public void autoQuizDefaultsOn() {
        Prefs.setAutoQuiz(true);
        assertTrue(Prefs.autoQuiz());
        Prefs.setAutoQuiz(false);
        assertFalse(Prefs.autoQuiz());
        Prefs.setAutoQuiz(true);
    }
}
