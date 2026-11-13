package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Calendar;

/**
 * 日历计划（v1.7.31）：上学日 / 周末 / 法定假期、调休上班、手动覆盖、
 * 网络数据解析以及「关掉日历计划后回到旧规则」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CalendarPlanTest {

    private Context ctx;

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
        ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE).edit()
                .remove("rules_json").remove("calendarDays")
                .remove("calendarSyncAt").remove("calendarSyncStatus").apply();
        Prefs.setQuietEnabled(false);
    }

    private boolean allowed(long ts) {
        return Rules.quizAllowedAt(Rules.get(ctx), ts, false, 0, 0);
    }

    @Test
    public void defaultTemplateMatchesParentsRequest() {
        // 2026-11-13 是周五（上学日）
        assertFalse("上学日 07:00–17:30 大人豁免", allowed(at(2026, 11, 13, 11, 0)));
        assertFalse("豁免起点算在窗口内", allowed(at(2026, 11, 13, 7, 0)));
        assertTrue("放学后弹题", allowed(at(2026, 11, 13, 18, 0)));
        assertTrue("弹到 23:00 前", allowed(at(2026, 11, 13, 22, 59)));
        assertFalse("23:00 起不弹", allowed(at(2026, 11, 13, 23, 0)));

        // 周六按周末模板 06:00–23:00
        assertFalse("周末清晨不弹", allowed(at(2026, 11, 14, 5, 59)));
        assertTrue("周末 06:00 起弹题", allowed(at(2026, 11, 14, 6, 0)));
        assertTrue("周末白天弹题", allowed(at(2026, 11, 14, 11, 0)));
        assertFalse("周末 23:00 后不弹", allowed(at(2026, 11, 14, 23, 30)));
    }

    @Test
    public void officialHolidayUsesHolidayPlanEvenOnWeekday() throws Exception {
        long ts = at(2027, 2, 8, 11, 0);   // 春节假期里的周一
        JSONObject cfg = Rules.get(ctx);
        assertEquals(CalendarPlan.HOLIDAY, CalendarPlan.dayType(cfg, ts));
        assertTrue("法定假期白天照常弹题", allowed(ts));
        assertEquals("2027-02-08", CalendarPlan.dateKey(ts));
    }

    @Test
    public void makeupWorkdayIsTreatedAsSchool() {
        long ts = at(2027, 1, 31, 11, 0);  // 周日，但官方通知要求上班
        JSONObject cfg = Rules.get(ctx);
        assertEquals(CalendarPlan.SCHOOL, CalendarPlan.dayType(cfg, ts));
        assertFalse("调休上学日仍是大人豁免时段", allowed(ts));
        assertTrue("调休上学日放学后弹题", allowed(at(2027, 1, 31, 18, 0)));
    }

    @Test
    public void manualOverrideBeatsBothCalendarAndWeekday() throws Exception {
        long ts = at(2026, 11, 14, 11, 0);  // 周六
        JSONObject cfg = Rules.get(ctx);
        JSONObject ov = new JSONObject();
        ov.put("2026-11-14", CalendarPlan.SCHOOL);
        cfg.put("calendarOverrides", ov);
        Rules.save(ctx, cfg);
        assertEquals(CalendarPlan.SCHOOL, CalendarPlan.dayType(Rules.get(ctx), ts));
        assertFalse("手动标成上学日后走大人豁免", allowed(ts));
    }

    @Test
    public void parseRemoteHolidayJson() {
        String json = "{\"year\":2027,\"papers\":[\"x\"],\"days\":["
                + "{\"name\":\"元旦\",\"date\":\"2027-01-01\",\"isOffDay\":true},"
                + "{\"name\":\"春节调休\",\"date\":\"2027-02-14\",\"isOffDay\":false},"
                + "{\"name\":\"坏数据\",\"date\":\"not-a-date\",\"isOffDay\":true}]}";
        JSONArray a = CalendarPlan.parseRemote(json);
        assertEquals(2, a.length());
        assertEquals("2027-01-01", a.optJSONObject(0).optString("date"));
        assertTrue(a.optJSONObject(0).optBoolean("off"));
        assertFalse(a.optJSONObject(1).optBoolean("off"));
        assertEquals(0, CalendarPlan.parseRemote("{\"days\":[]}").length());
    }

    @Test
    public void syncedDataCanOverrideBuiltin() throws Exception {
        JSONArray remote = new JSONArray();
        JSONObject one = new JSONObject();
        one.put("date", "2027-01-31");
        one.put("off", true);       // 内置是「调休上班」，同步结果改为放假
        one.put("name", "测试");
        remote.put(one);
        Prefs.setCalendarDays(remote.toString());
        JSONObject cfg = Rules.get(ctx);
        assertEquals(CalendarPlan.HOLIDAY, CalendarPlan.dayType(cfg, at(2027, 1, 31, 11, 0)));
    }

    @Test
    public void sanitizePlansRejectsIllegalTimes() throws Exception {
        JSONObject bad = new JSONObject();
        JSONObject weekday = new JSONObject();
        weekday.put("quizStart", "99:99");
        weekday.put("quizEnd", "23:00");
        weekday.put("exemptStart", "");
        bad.put("school", weekday);
        JSONObject clean = CalendarPlan.sanitizePlans(bad);
        assertEquals("17:30", clean.optJSONObject("school").optString("quizStart"));
        assertEquals("23:00", clean.optJSONObject("school").optString("quizEnd"));
        assertEquals("", clean.optJSONObject("school").optString("exemptStart"));
    }

    @Test
    public void turningCalendarOffFallsBackToLegacyRules() throws Exception {
        JSONObject cfg = Rules.get(ctx);
        cfg.put("dayPlansEnabled", false);
        cfg.put("allDay", true);
        cfg.put("scheduleMode", "control");
        Rules.save(ctx, cfg);
        // 旧规则 allDay + control = 全天允许弹题，包括上学日 11:00
        assertTrue(allowed(at(2026, 11, 13, 11, 0)));
        assertTrue(Rules.explainNow(ctx, at(2026, 11, 13, 11, 0)).contains("旧版时间计划"));
    }

    @Test
    public void explainShowsWhyItWillNotPop() {
        String why = Rules.explainNow(ctx, at(2026, 11, 13, 11, 0));
        assertTrue(why.contains("上学日"));
        assertTrue(why.contains("大人豁免"));
        assertTrue(why.contains("不弹题"));
    }

    @Test
    public void defaultsEnableCalendarPlan() {
        JSONObject d = Rules.defaults();
        assertTrue(d.optBoolean("dayPlansEnabled"));
        assertEquals("07:00", d.optJSONObject("dayPlans")
                .optJSONObject("school").optString("exemptStart"));
        assertEquals("17:30", d.optJSONObject("dayPlans")
                .optJSONObject("school").optString("exemptEnd"));
        assertEquals("06:00", d.optJSONObject("dayPlans")
                .optJSONObject("weekend").optString("quizStart"));
        assertEquals("23:00", d.optJSONObject("dayPlans")
                .optJSONObject("holiday").optString("quizEnd"));
    }
}
