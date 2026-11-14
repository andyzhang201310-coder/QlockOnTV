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

/** 管理规则的时段优先级、玩法参数夹取、错题库与每日轮数。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RulesTest {

    private Context ctx;

    private static long at(int dayOfWeek, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_WEEK, dayOfWeek);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static JSONObject window(int day, String start, String end) throws Exception {
        JSONObject w = new JSONObject();
        w.put("id", "w1");
        w.put("name", "测试窗口");
        w.put("days", new JSONArray().put(day));   // 0=周一
        w.put("start", start);
        w.put("end", end);
        return w;
    }

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
        ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE).edit()
                .remove("rules_json").remove("rules_version")
                .remove("rules_round_date").remove("rules_round_count")
                .remove("rules_wrong_date").remove("rules_wrong_total")
                .remove("rules_wrong_ids").apply();
        Prefs.setQuietEnabled(false);
    }

    @Test
    public void defaultsCoverEveryTab() {
        JSONObject d = Rules.defaults();
        assertEquals("round", d.optString("playMode"));
        assertEquals(5, d.optInt("questionsPerRound"));
        assertEquals(100, d.optInt("fontScale"));
        assertEquals(7, d.optJSONArray("weekSlots").length());
        assertEquals(48, d.optJSONArray("weekSlots").optString(0).length());
        assertTrue(d.optBoolean("allDay"));
        assertFalse(d.optBoolean("reviewEnabled"));
        assertEquals("AI 讲解默认标准档", 2, d.optInt("aiDetail"));
    }

    @Test
    public void aiDetailIsClampedToThreeLevels() throws Exception {
        assertEquals(1, Rules.sanitize(new JSONObject().put("aiDetail", -5)).optInt("aiDetail"));
        assertEquals(3, Rules.sanitize(new JSONObject().put("aiDetail", 99)).optInt("aiDetail"));
        assertEquals(2, Rules.sanitize(new JSONObject().put("aiDetail", "oops")).optInt("aiDetail"));
    }

    @Test
    public void sanitizeClampsOutOfRangeValues() throws Exception {
        JSONObject in = new JSONObject();
        in.put("questionsPerRound", 9999);
        in.put("fontScale", 1);
        in.put("wrongReviewSeconds", -3);
        in.put("playMode", "not-a-mode");
        in.put("passRatio", 3.5);
        in.put("windowStart", "99:99");
        in.put("weekSlots", new JSONArray().put("bad"));
        JSONObject o = Rules.sanitize(in);
        assertEquals(100, o.optInt("questionsPerRound"));
        assertEquals(75, o.optInt("fontScale"));
        assertEquals(0, o.optInt("wrongReviewSeconds"));
        assertEquals("round", o.optString("playMode"));
        assertEquals(1.0d, o.optDouble("passRatio"), 0.0001d);
        assertEquals("18:30", o.optString("windowStart"));
        assertEquals(7, o.optJSONArray("weekSlots").length());
    }

    @Test
    public void namedWindowsDropInvalidEntries() throws Exception {
        JSONObject in = new JSONObject();
        in.put("timePoints", new JSONArray()
                .put(window(0, "19:00", "20:00"))
                .put(window(0, "21:00", "21:00"))   // 起止相同，丢弃
                .put(window(0, "bad", "20:00")));   // 非法时间，丢弃
        JSONObject o = Rules.sanitize(in);
        assertEquals(1, o.optJSONArray("timePoints").length());
    }

    @Test
    public void weekSlotsUseMondayFirstIndex() {
        JSONArray slots = new JSONArray();
        String off = new String(new char[48]).replace('\0', '0');
        String on = new String(new char[48]).replace('\0', '1');
        for (int d = 0; d < 7; d++) {
            slots.put(d == 0 ? on : off);   // 只有周一全天
        }
        assertEquals(0, Rules.weekIndex(at(Calendar.MONDAY, 10, 0)));
        assertEquals(6, Rules.weekIndex(at(Calendar.SUNDAY, 10, 0)));
        assertTrue(Rules.weekSlotOn(slots, at(Calendar.MONDAY, 10, 0)));
        assertFalse(Rules.weekSlotOn(slots, at(Calendar.TUESDAY, 10, 0)));
    }

    @Test
    public void namedWindowSameDayAndCrossMidnight() throws Exception {
        JSONArray a = new JSONArray().put(window(0, "19:00", "20:30"));
        assertTrue(Rules.inNamedWindow(a, at(Calendar.MONDAY, 19, 0)));
        assertTrue(Rules.inNamedWindow(a, at(Calendar.MONDAY, 20, 29)));
        assertFalse(Rules.inNamedWindow(a, at(Calendar.MONDAY, 20, 30)));
        assertFalse(Rules.inNamedWindow(a, at(Calendar.TUESDAY, 19, 30)));

        // 周六 22:00 → 周日 02:00：周六夜晚与周日凌晨都命中
        JSONArray b = new JSONArray().put(window(5, "22:00", "02:00"));
        assertTrue(Rules.inNamedWindow(b, at(Calendar.SATURDAY, 23, 0)));
        assertTrue(Rules.inNamedWindow(b, at(Calendar.SUNDAY, 1, 59)));
        assertFalse(Rules.inNamedWindow(b, at(Calendar.SUNDAY, 2, 0)));
    }

    @Test
    public void exemptAndClassWindowsBeatSchedule() throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("scheduleMode", "control");
        cfg.put("allDay", false);
        cfg.put("windowStart", "18:00");
        cfg.put("windowEnd", "21:00");
        long mondayEvening = at(Calendar.MONDAY, 19, 0);
        assertTrue(Rules.quizAllowedAt(cfg, mondayEvening, false, 0, 0));

        cfg.put("timePoints", new JSONArray().put(window(0, "18:30", "19:30")));
        assertFalse("网课时段不弹题", Rules.quizAllowedAt(cfg, mondayEvening, false, 0, 0));

        cfg.put("timePoints", new JSONArray());
        cfg.put("exemptWindows", new JSONArray().put(window(0, "18:30", "19:30")));
        assertFalse("大人豁免不弹题", Rules.quizAllowedAt(cfg, mondayEvening, false, 0, 0));
    }

    @Test
    public void scheduleModeControlVsAllow() throws Exception {
        JSONObject cfg = Rules.defaults();
        // v1.7.31：日历计划默认开启会接管时段判定，这里专测旧版 scheduleMode
        cfg.put("dayPlansEnabled", false);
        cfg.put("allDay", false);
        cfg.put("windowStart", "18:00");
        cfg.put("windowEnd", "21:00");
        long inside = at(Calendar.WEDNESDAY, 19, 0);
        long outside = at(Calendar.WEDNESDAY, 10, 0);

        cfg.put("scheduleMode", "control");
        assertTrue(Rules.quizAllowedAt(cfg, inside, false, 0, 0));
        assertFalse(Rules.quizAllowedAt(cfg, outside, false, 0, 0));

        cfg.put("scheduleMode", "allow");
        assertFalse(Rules.quizAllowedAt(cfg, inside, false, 0, 0));
        assertTrue(Rules.quizAllowedAt(cfg, outside, false, 0, 0));
    }

    @Test
    public void quietHoursStillBlockAndNextQuizAtSkipsThem() throws Exception {
        JSONObject cfg = Rules.defaults();
        long night = at(Calendar.FRIDAY, 23, 0);
        assertFalse(Rules.quizAllowedAt(cfg, night, true, 22 * 60, 7 * 60));
        long next = Rules.nextQuizAt(cfg, night, true, 22 * 60, 7 * 60);
        assertTrue(next > night);
        assertTrue("顺延后必须落在允许时段", Rules.quizAllowedAt(cfg, next, true, 22 * 60, 7 * 60));
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(next);
        assertEquals(7, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, c.get(Calendar.MINUTE));
    }

    @Test
    public void alignDueLeavesAllowedTimeUntouched() throws Exception {
        // v1.7.31：旧版「全天可用」语义，显式关掉日历计划后再验证
        JSONObject off = Rules.defaults();
        off.put("dayPlansEnabled", false);
        Rules.save(ctx, off);
        long t = at(Calendar.THURSDAY, 14, 0);
        // 全天可用 + 免打扰关闭：任何时刻都允许
        assertEquals(t, Rules.alignDue(ctx, t));
    }

    @Test
    public void saveSyncsHomeSettings() throws Exception {
        JSONObject in = new JSONObject();
        in.put("questionsPerRound", 7);
        in.put("sessionMinutes", 45);
        in.put("quizSubjects", new JSONArray().put("数学"));
        in.put("quizLevels", new JSONArray().put("3").put("4"));
        JSONObject saved = Rules.save(ctx, in);
        assertEquals(7, saved.optInt("questionsPerRound"));
        assertEquals(7, Prefs.quizCount());
        assertEquals(45, Prefs.intervalMinutes());
        assertEquals("数学", Prefs.subjects());
        assertEquals("3,4", Prefs.grades());
        assertEquals(2, Rules.version(ctx));
        assertEquals(7, Rules.get(ctx).optInt("questionsPerRound"));
    }

    @Test
    public void roundsAndWrongPoolDriveExtraQuestions() throws Exception {
        JSONObject cfg = Rules.defaults();
        assertEquals(0, Rules.roundsToday(ctx));
        Rules.noteRound(ctx);
        Rules.noteRound(ctx);
        assertEquals(2, Rules.roundsToday(ctx));
        assertTrue(Rules.roundsLeft(ctx, cfg));   // 默认不启用上限

        JSONObject limited = new JSONObject(cfg.toString());
        limited.put("roundsLimitEnabled", true);
        limited.put("roundsPerDay", 2);
        assertFalse(Rules.roundsLeft(ctx, limited));

        for (int i = 0; i < 3; i++) {
            Rules.noteWrong(ctx, "q" + i);
        }
        assertEquals(3, Rules.wrongToday(ctx));
        assertEquals(3, Rules.wrongIds(ctx).size());
        Rules.clearWrong(ctx, "q0");
        assertEquals(2, Rules.wrongIds(ctx).size());

        JSONObject extra = new JSONObject(cfg.toString());
        extra.put("extraEnabled", true);
        extra.put("wrongThreshold", 3);
        extra.put("extraQuestions", 2);
        assertEquals(2, Rules.extraQuestions(ctx, extra));
        assertEquals(7, Rules.questionCount(ctx, extra));

        Rules.clearWrongPool(ctx);
        assertEquals(0, Rules.wrongIds(ctx).size());
        assertEquals(0, Rules.wrongToday(ctx));
    }

    // ---------------------------------------------------------------- v6 按轮冷却

    @Test
    public void wrongQuestionWaitsAtLeastThreeRounds() throws Exception {
        assertEquals(0, Rules.roundSeq(ctx));
        assertEquals("默认冷却 3 轮", 3, Rules.reviewGap(Rules.defaults()));

        int r1 = Rules.nextRoundSeq(ctx);            // 第 1 轮
        assertEquals(1, r1);
        Rules.noteWrong(ctx, "w1");

        // 同一轮刚错完：必须回避，不能立刻重出
        assertTrue(Rules.coolingIds(ctx, 3).contains("w1"));
        assertFalse(Rules.dueWrongIds(ctx, 3).contains("w1"));

        Rules.nextRoundSeq(ctx);                     // 第 2 轮
        assertFalse("第 2 轮仍未到期", Rules.dueWrongIds(ctx, 3).contains("w1"));

        Rules.nextRoundSeq(ctx);                     // 第 3 轮
        assertFalse("第 3 轮仍未到期", Rules.dueWrongIds(ctx, 3).contains("w1"));

        Rules.nextRoundSeq(ctx);                     // 第 4 轮：满 3 轮，可以再出现
        assertTrue("隔 3 轮后应可复习", Rules.dueWrongIds(ctx, 3).contains("w1"));
        assertTrue(Rules.coolingIds(ctx, 3).isEmpty());
        assertTrue(Rules.wrongDue(ctx, "w1", 3));

        // 再答错一次就从当前轮重新计时
        Rules.noteWrong(ctx, "w1");
        assertFalse(Rules.dueWrongIds(ctx, 3).contains("w1"));

        // 答对后立刻离开错题库，不再受冷却限制
        Rules.clearWrong(ctx, "w1");
        assertTrue(Rules.wrongIds(ctx).isEmpty());
        assertTrue(Rules.wrongDue(ctx, "w1", 3));

        Rules.clearWrongPool(ctx);
    }

    @Test
    public void reviewGapIsClampedAndLegacyIdsStillWork() throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("reviewGapRounds", 99);
        assertEquals("上限 20 轮", 20, Rules.reviewGap(Rules.sanitize(cfg)));
        cfg.put("reviewGapRounds", 0);
        assertEquals("至少 1 轮", 1, Rules.reviewGap(Rules.sanitize(cfg)));

        // 老版本只存了 id 列表（rules_wrong_ids）：升级后应立刻可复习，不能丢错题
        Rules.clearWrongPool(ctx);
        ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE).edit()
                .putString("rules_wrong_ids", "old1,old2").apply();
        assertEquals(2, Rules.wrongIds(ctx).size());
        assertTrue("老数据不带轮次，按第 0 轮处理", Rules.dueWrongIds(ctx, 3).contains("old1"));
        Rules.clearWrongPool(ctx);
    }

    @Test
    public void syncFromPrefsKeepsHomeSettingsAndRulesAligned() throws Exception {        JSONObject cfg = Rules.get(ctx);
        cfg.put("questionsPerRound", 5);
        cfg.put("sessionMinutes", 30);
        cfg.put("quizSubjects", new JSONArray());
        cfg.put("quizLevels", new JSONArray());
        Rules.save(ctx, cfg);

        // 首页「参数设置」保存时只写 Prefs，这四个字段必须与规则页保持一致
        Prefs.setQuizCount(3);
        Prefs.setIntervalMinutes(45);
        Prefs.setSubjects("语文,数学");
        Prefs.setGrades("2,3");
        Rules.syncFromPrefs(ctx);

        JSONObject after = Rules.get(ctx);
        assertEquals(3, after.optInt("questionsPerRound"));
        assertEquals(45, after.optInt("sessionMinutes"));
        assertEquals(2, after.optJSONArray("quizSubjects").length());
        assertEquals(2, after.optJSONArray("quizLevels").length());
        // 答题页按规则配置出题，不能被规则里的旧值 5 覆盖
        assertEquals(3, Rules.questionCount(ctx, after));

        // 之后规则页再自动保存，也不会把首页改过的值冲掉
        JSONObject again = new JSONObject(after.toString());
        again.put("reviewEnabled", true);
        Rules.save(ctx, again);
        assertEquals(3, Prefs.quizCount());
        assertEquals(45, Prefs.intervalMinutes());
    }
}
