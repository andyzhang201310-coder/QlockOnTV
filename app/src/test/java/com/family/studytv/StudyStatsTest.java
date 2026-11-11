package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

/** 题型分类 + 按题型累计的统计口径。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StudyStatsTest {

    @Before
    public void setUp() {
        QuestionBank.resetForTest();
        Prefs.setBankCustom(false);
        QuestionBank.get().ensureSeeded();
        StudyStats.reset(RuntimeEnvironment.getApplication());
    }

    @Test
    public void classifiesTypicalQuestions() {
        assertEquals("古诗词", type("语文", "q1", "「床前明月光」的作者和朝代是？"));
        assertEquals("古诗词", type("语文", "poem-3-1", "下列「诗句 — 作者 — 朝代」搭配正确的是？"));
        assertEquals("拼音", type("语文", "q2", "「莺」的正确拼音是？"));
        assertEquals("拼音", type("语文", "q3", "「门」的读音是第几声？"));
        assertEquals("多音字", type("语文", "q4", "「种」在「种子」中读作？"));
        assertEquals("笔画", type("语文", "q5", "「种」字一共有几画？"));
        assertEquals("部首", type("语文", "q6", "「抽」的部首是？"));
        assertEquals("同音字", type("语文", "q7", "下列汉字中，与「它」读音相同的是？"));
        assertEquals("近义词", type("语文", "q8", "「出现」的近义词是？"));
        assertEquals("反义词", type("语文", "q9", "「安静」的反义词是？"));
        assertEquals("词语解释", type("语文", "q10", "成语「五颜六色」的意思是？"));
        assertEquals("计算", type("数学", "q11", "9 + 11 = ?"));
        assertEquals("应用题", type("数学", "q12", "二（1）班有男生 21 人，女生 23 人。全班一共有多少人？"));
        assertEquals("图形与公式", type("数学", "q13", "一个圆锥的底面积是 12 平方厘米，高是 9 厘米，体积是多少立方厘米？"));
        assertEquals("单位换算", type("数学", "q14", "3 米 = ? 厘米"));
        assertEquals("数与代数", type("数学", "q15", "x + 4.2 = 7.5，x = ?"));
        assertEquals("单词词义", type("英语", "q16", "「postman」这个单词的中文意思是？"));
        assertEquals("单词拼写", type("英语", "q17", "「眼睛」的正确拼写是哪一项？"));
        assertEquals("语法", type("英语", "q18", "「box」的复数形式是？"));
        assertEquals("句子翻译", type("英语", "q19", "句子「I like fish.」应该翻译成哪句中文？"));
        assertEquals("情景交际", type("英语", "q20", "别人帮了你，应该怎么说？"));
    }

    private static String type(String subject, String id, String q) {
        return QType.of(subject, id, q);
    }

    @Test
    public void everyBuiltInQuestionFallsIntoAKnownType() {
        Map<String, Integer> perSubject = new HashMap<String, Integer>();
        Map<String, Integer> other = new HashMap<String, Integer>();
        for (QuestionBank.Question q : QuestionBank.get().all()) {
            String t = QType.of(q);
            assertNotNull("题型不能为空", t);
            assertTrue("题型不能是空白：" + q.q, t.trim().length() > 0);
            Integer n = perSubject.get(q.subject);
            perSubject.put(q.subject, n == null ? 1 : n + 1);
            if (t.endsWith("其他")) {
                Integer m = other.get(q.subject);
                other.put(q.subject, m == null ? 1 : m + 1);
            }
        }
        assertEquals(3, perSubject.size());
        // 「其他」兜底桶要足够小，否则统计页的题型划分就失去意义
        for (String s : perSubject.keySet()) {
            int o = other.containsKey(s) ? other.get(s) : 0;
            double ratio = o * 1.0d / perSubject.get(s);
            assertTrue(s + " 未分类题太多：" + o + "/" + perSubject.get(s),
                    ratio < 0.06d);
        }
    }

    @Test
    public void recordsPerTypeAndSortsByWrongCount() throws Exception {
        StudyStats.record(RuntimeEnvironment.getApplication(), "语文", "拼音", false);
        StudyStats.record(RuntimeEnvironment.getApplication(), "语文", "拼音", true);
        StudyStats.record(RuntimeEnvironment.getApplication(), "语文", "拼音", false);
        StudyStats.record(RuntimeEnvironment.getApplication(), "数学", "计算", true);

        JSONObject snap = StudyStats.snapshot(RuntimeEnvironment.getApplication());
        assertEquals(4, snap.getInt("total"));
        assertEquals(2, snap.getInt("correct"));
        assertEquals(2, snap.getInt("wrong"));
        assertEquals(50.0d, snap.getDouble("rate"), 0.01d);

        JSONArray types = snap.getJSONArray("types");
        assertEquals(2, types.length());
        JSONObject first = types.getJSONObject(0);
        assertEquals("拼音", first.getString("type"));
        assertEquals("语文", first.getString("subject"));
        assertEquals(3, first.getInt("total"));
        assertEquals(2, first.getInt("wrong"));
        assertEquals(66.7d, first.getDouble("wrongRate"), 0.1d);
        assertEquals("计算", types.getJSONObject(1).getString("type"));
        assertEquals(0, types.getJSONObject(1).getInt("wrong"));
    }

    @Test
    public void keepsDailyBucketsAndResetsCleanly() throws Exception {
        StudyStats.record(RuntimeEnvironment.getApplication(), "英语", "单词拼写", false);
        JSONObject snap = StudyStats.snapshot(RuntimeEnvironment.getApplication());
        JSONArray days = snap.getJSONArray("days");
        assertEquals(1, days.length());
        assertEquals(1, days.getJSONObject(0).getInt("wrong"));
        assertTrue(snap.getString("since").length() >= 8);

        StudyStats.reset(RuntimeEnvironment.getApplication());
        JSONObject empty = StudyStats.snapshot(RuntimeEnvironment.getApplication());
        assertEquals(0, empty.getInt("total"));
        assertEquals(0, empty.getJSONArray("types").length());
        assertEquals("", empty.getString("since"));
    }
}
