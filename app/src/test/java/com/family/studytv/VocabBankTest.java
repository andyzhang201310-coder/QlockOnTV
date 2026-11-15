package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 《小学必备词汇》分类题库：词条覆盖、单独勾选、升级合并、AI 讲题身份。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VocabBankTest {

    private static final String SUBJECT = "小学必备词汇";

    @Before
    public void setUp() {
        QuestionBank.resetForTest();
        Prefs.setBankCustom(false);
        Prefs.setGrades("1,2,3,4,5,6");
        Prefs.setSubjects("语文,数学,英语");
        QuestionBank.get().ensureSeeded();
    }

    @Test
    public void allIndexWordsBecomeValidChoiceQuestions() {
        QuestionBank bank = QuestionBank.get();
        int total = 0;
        Set<String> ids = new HashSet<String>();
        Set<String> questions = new HashSet<String>();
        Set<String> correct = new HashSet<String>();
        for (QuestionBank.Question q : bank.all()) {
            if (!SUBJECT.equals(q.subject)) {
                continue;
            }
            total++;
            assertTrue("词汇题 id 必须以 vocab- 开头: " + q.id, q.id.startsWith("vocab-"));
            assertTrue("id 不能重复: " + q.id, ids.add(q.id));
            assertTrue("题干不能重复: " + q.q, questions.add(q.q));
            assertEquals("词汇题不分年级", 0, q.grade);
            assertEquals("必须是四选一", 4, q.options.size());
            assertTrue("答案下标合法", q.answer >= 0 && q.answer < 4);
            assertTrue("题干要带题型标签，统计才能归类", q.q.startsWith("【单词词义】"));
            assertTrue("题干要点名单词", q.q.contains("「") && q.q.contains("」"));
            assertEquals("题型应识别为单词词义", "单词词义", QType.of(q));
            Set<String> opts = new HashSet<String>(q.options);
            assertEquals("四个选项不能重复", 4, opts.size());
            correct.add(q.options.get(q.answer));
        }
        assertEquals("索引里的 874 个词条一词一题", 874, total);
        assertTrue("释义池要有足够多的不同中文，不能全题一面", correct.size() > 700);
        assertEquals(Integer.valueOf(874), bank.countBySubject().get(SUBJECT));
    }

    @Test
    public void vocabCanBeSelectedAloneInQuizScope() {
        Prefs.setSubjects(SUBJECT);
        List<QuestionBank.Question> picked = QuestionBank.get().pick(12);
        assertEquals(12, picked.size());
        for (QuestionBank.Question q : picked) {
            assertEquals("只勾「小学必备词汇」时就只能出该分类的题", SUBJECT, q.subject);
        }
    }

    @Test
    public void gradeFilterStillLetsGenericVocabQuestionsThrough() {
        Prefs.setSubjects(SUBJECT);
        Prefs.setGrades("1");
        List<QuestionBank.Question> picked = QuestionBank.get().pick(6);
        assertEquals(6, picked.size());
        for (QuestionBank.Question q : picked) {
            assertEquals(SUBJECT, q.subject);
        }
    }

    @Test
    public void customBankUpgradeAppendsNewVocabQuestions() throws Exception {
        String cur = doc(8,
                item("m1", "数学", "家长改过的题"),
                item("mine-1", "英语", "家长自己加的题"));
        String asset = doc(9,
                item("m1", "数学", "官方原题"),
                item("vocab-0001", SUBJECT, "【单词词义】「apple」的中文意思是？"));
        QuestionBank.Migration m = QuestionBank.migrateMathText(cur, asset);
        assertNotNull(m.json);
        assertFalse("有自定义内容时不能整库覆盖", m.pristine);
        JSONObject out = new JSONObject(m.json);
        assertEquals("版本要一起升，避免每次启动重扫", 9, out.optInt("version"));
        JSONArray qs = out.getJSONArray("questions");
        assertEquals("原有 2 题 + 新增 1 题", 3, qs.length());
        assertEquals("家长改过的题原样保留", "家长改过的题", qs.getJSONObject(0).getString("q"));
        assertEquals("家长自己加的题原样保留", "家长自己加的题",
                qs.getJSONObject(1).getString("q"));
        assertEquals("内置新增的分类题要补进来", "vocab-0001",
                qs.getJSONObject(2).getString("id"));
        assertEquals(SUBJECT, qs.getJSONObject(2).getString("subject"));
    }

    @Test
    public void upgradeDoesNotDuplicateExistingQuestions() throws Exception {
        String cur = doc(9,
                item("m1", "数学", "家长改过的题"),
                item("vocab-0001", SUBJECT, "【单词词义】「apple」的中文意思是？"));
        String asset = doc(9,
                item("m1", "数学", "官方原题"),
                item("vocab-0001", SUBJECT, "【单词词义】「apple」的中文意思是？"));
        QuestionBank.Migration m = QuestionBank.migrateMathText(cur, asset);
        JSONObject out = new JSONObject(m.json);
        assertEquals("已有 id 不能被追加第二次", 2, out.getJSONArray("questions").length());
    }

    @Test
    public void aiExplainsVocabAsAnEnglishTeacher() {
        String p = AiExplain.buildPrompt(SUBJECT, 0,
                "【单词词义】「apple」的中文意思是？",
                Arrays.asList("苹果", "香蕉", "橘子", "梨"), 0, 1);
        assertTrue("要按英语科任老师的路径讲", p.contains("英语老师"));
        assertTrue("英语课要先认单词", p.contains("先带学生认单词"));
        assertFalse("不能把分类名当学科名", p.contains("小学必备词汇老师"));
        assertTrue("错因要盯着孩子选的干扰项", p.contains("「香蕉」"));
    }

    private static String item(String id, String subject, String q) {
        return "{\"id\":\"" + id + "\",\"subject\":\"" + subject
                + "\",\"grade\":0,\"q\":\"" + q
                + "\",\"options\":[\"A\",\"B\",\"C\",\"D\"],\"answer\":0,\"difficulty\":1}";
    }

    private static String doc(int version, String... items) {
        StringBuilder sb = new StringBuilder("{\"version\":").append(version)
                .append(",\"questions\":[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(items[i]);
        }
        return sb.append("]}").toString();
    }
}
