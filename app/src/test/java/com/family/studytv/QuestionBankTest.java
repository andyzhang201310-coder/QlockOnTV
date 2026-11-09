package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QuestionBankTest {

    @Before
    public void setUp() {
        // 每个用例都从内置题库重新开始，避免用例之间互相污染
        QuestionBank.resetForTest();
        Prefs.setBankCustom(false);
        Prefs.setGrades("1,2,3,4,5,6");
        Prefs.setSubjects("语文,数学,英语");
        QuestionBank.get().ensureSeeded();
    }

    @Test
    public void seedBankLoadsAllThreeSubjects() {
        QuestionBank bank = QuestionBank.get();
        bank.ensureSeeded();
        assertEquals("内置题库为 1-6 年级 × 语数英各 1000 题", 18000, bank.count());
        assertEquals(Integer.valueOf(6000), bank.countBySubject().get("语文"));
        assertEquals(Integer.valueOf(6000), bank.countBySubject().get("数学"));
        assertEquals(Integer.valueOf(6000), bank.countBySubject().get("英语"));
        assertEquals("每个年级各 3000 题", Integer.valueOf(3000), bank.countByGrade().get(3));

        // 每个年级、每个学科都必须是 1000 题
        int[][] perGradeSubject = new int[7][3];
        for (QuestionBank.Question q : bank.all()) {
            int idx = "语文".equals(q.subject) ? 0 : ("数学".equals(q.subject) ? 1 : 2);
            perGradeSubject[q.grade][idx]++;
        }
        for (int g = 1; g <= 6; g++) {
            for (int s = 0; s < 3; s++) {
                assertEquals("年级 " + g + " 学科序号 " + s + " 题量应为 1000",
                        1000, perGradeSubject[g][s]);
            }
        }
    }

    @Test
    public void pickReturnsRequestedCountAndMixedSubjects() {
        QuestionBank bank = QuestionBank.get();
        List<QuestionBank.Question> picked = bank.pick(5);
        assertEquals(5, picked.size());
        Set<String> subjects = new HashSet<String>();
        for (QuestionBank.Question q : picked) {
            subjects.add(q.subject);
        }
        assertTrue("5 道题应覆盖多个学科", subjects.size() >= 2);
    }

    @Test
    public void parseSupportsTextAnswer() throws Exception {
        String json = "{\"questions\":[{\"subject\":\"语文\",\"q\":\"测试\",\"options\":[\"A选项\",\"B选项\"],\"answer\":\"B选项\"}]}";
        List<QuestionBank.Question> list = QuestionBank.parse(json);
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).answer);
    }

    @Test
    public void parseRejectsBadAnswer() {
        String json = "{\"questions\":[{\"q\":\"测试\",\"options\":[\"A\",\"B\"],\"answer\":9}]}";
        try {
            QuestionBank.parse(json);
            fail("越界 answer 应当被拒绝");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("answer"));
        }
    }

    @Test
    public void parseRejectsBrokenJson() {
        try {
            QuestionBank.parse("{not json}");
            fail("非法 JSON 应当被拒绝");
        } catch (Exception e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void saveThenRestoreBackup() throws Exception {
        QuestionBank bank = QuestionBank.get();
        bank.ensureSeeded();
        int before = bank.count();
        String json = "{\"questions\":[{\"id\":\"t1\",\"subject\":\"数学\",\"q\":\"1+1=?\",\"options\":[\"2\",\"3\"],\"answer\":0}]}";
        bank.save(json);
        assertEquals(1, bank.count());
        assertTrue(bank.restoreBackup());
        assertEquals(before, bank.count());
    }

    @Test
    public void pickAvoidsExcludedIds() {
        QuestionBank bank = QuestionBank.get();
        List<QuestionBank.Question> first = bank.pick(5);
        Set<String> used = new HashSet<String>();
        for (QuestionBank.Question q : first) {
            used.add(q.id);
        }

        // 抽 20 次都不应该出现刚答错那一组里的题
        for (int round = 0; round < 20; round++) {
            List<QuestionBank.Question> next = bank.pick(5, used);
            assertEquals(5, next.size());
            for (QuestionBank.Question q : next) {
                assertTrue("换新题时不能重复上一组的题：" + q.id, !used.contains(q.id));
            }
        }
    }

    @Test
    public void pickOnlyUsesSelectedGrade() {
        Prefs.setGrades("3");
        for (int round = 0; round < 10; round++) {
            List<QuestionBank.Question> picked = QuestionBank.get().pick(5);
            assertEquals(5, picked.size());
            for (QuestionBank.Question q : picked) {
                assertEquals("只选 3 年级时不能出别的年级：" + q.q, 3, q.grade);
            }
        }
    }

    @Test
    public void pickSupportsMultipleGrades() {
        Prefs.setGrades("1,2,3");
        Set<Integer> grades = new HashSet<Integer>();
        for (int round = 0; round < 20; round++) {
            List<QuestionBank.Question> picked = QuestionBank.get().pick(5);
            assertEquals(5, picked.size());
            for (QuestionBank.Question q : picked) {
                assertTrue("多选 1-3 年级时越界：" + q.grade + " " + q.q,
                        q.grade >= 1 && q.grade <= 3);
                grades.add(q.grade);
            }
        }
        assertTrue("多选时几个年级都应能抽到：" + grades, grades.size() >= 2);
    }

    @Test
    public void allGradesCoversEveryGrade() {
        Prefs.setGrades("1,2,3,4,5,6");
        Set<Integer> grades = new HashSet<Integer>();
        for (int round = 0; round < 80; round++) {
            for (QuestionBank.Question q : QuestionBank.get().pick(5)) {
                grades.add(q.grade);
            }
        }
        assertEquals("全选时应覆盖 1-6 年级：" + grades, 6, grades.size());
    }

    @Test
    public void gradeFallsBackWhenSelectedGradeHasNoQuestion() throws Exception {
        QuestionBank bank = QuestionBank.get();
        Prefs.setGrades("6");
        bank.save("{\"questions\":["
                + "{\"id\":\"a\",\"subject\":\"数学\",\"grade\":1,\"q\":\"1+1=?\",\"options\":[\"2\",\"3\"],\"answer\":0},"
                + "{\"id\":\"b\",\"subject\":\"数学\",\"grade\":1,\"q\":\"2+2=?\",\"options\":[\"4\",\"5\"],\"answer\":0}]}");
        try {
            List<QuestionBank.Question> picked = bank.pick(2);
            assertEquals("选中年级没题时必须回退，不能出不了题", 2, picked.size());
        } finally {
            bank.restoreBackup();
        }
    }

    @Test
    public void parseGradeAcceptsChineseNumberAndMissing() throws Exception {
        String json = "{\"questions\":["
                + "{\"id\":\"a\",\"subject\":\"语文\",\"grade\":\"三年级\",\"q\":\"甲\",\"options\":[\"A\",\"B\"],\"answer\":0},"
                + "{\"id\":\"b\",\"subject\":\"语文\",\"grade\":5,\"q\":\"乙\",\"options\":[\"A\",\"B\"],\"answer\":0},"
                + "{\"id\":\"c\",\"subject\":\"语文\",\"q\":\"丙\",\"options\":[\"A\",\"B\"],\"answer\":0}]}";
        List<QuestionBank.Question> list = QuestionBank.parse(json);
        assertEquals(3, list.get(0).grade);
        assertEquals(5, list.get(1).grade);
        assertEquals("不写 grade 视为未标注", 0, list.get(2).grade);
    }

    @Test
    public void oldBankWithoutGradeStillPlayable() throws Exception {
        QuestionBank bank = QuestionBank.get();
        Prefs.setGrades("4");
        bank.save("{\"questions\":["
                + "{\"id\":\"a\",\"subject\":\"语文\",\"q\":\"老题库一\",\"options\":[\"A\",\"B\"],\"answer\":0},"
                + "{\"id\":\"b\",\"subject\":\"数学\",\"q\":\"老题库二\",\"options\":[\"A\",\"B\"],\"answer\":0}]}");
        try {
            List<QuestionBank.Question> picked = bank.pick(2);
            assertEquals("老题库没有年级字段时不能被筛空", 2, picked.size());
        } finally {
            bank.restoreBackup();
        }
    }

    @Test
    public void pickIgnoresExclusionWhenNotEnoughQuestionsLeft() throws Exception {
        QuestionBank bank = QuestionBank.get();
        bank.save("{\"questions\":["
                + "{\"id\":\"a\",\"subject\":\"数学\",\"q\":\"1+1=?\",\"options\":[\"2\",\"3\"],\"answer\":0},"
                + "{\"id\":\"b\",\"subject\":\"数学\",\"q\":\"2+2=?\",\"options\":[\"4\",\"5\"],\"answer\":0},"
                + "{\"id\":\"c\",\"subject\":\"数学\",\"q\":\"3+3=?\",\"options\":[\"6\",\"7\"],\"answer\":0}]}");
        Set<String> used = new HashSet<String>();
        used.add("a");
        used.add("b");
        used.add("c");

        try {
            List<QuestionBank.Question> picked = bank.pick(3, used);
            assertEquals("排除后不够题时必须放宽，不能返回空题库", 3, picked.size());
        } finally {
            // save() 会自动备份原题库，这里还原，避免影响同一 JVM 里的后续用例
            QuestionBank.get().restoreBackup();
        }
    }
}
