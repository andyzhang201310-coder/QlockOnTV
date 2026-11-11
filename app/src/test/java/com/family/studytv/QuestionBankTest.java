package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
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
        assertEquals("内置题库 = 原有 18000 题 + v5 追加 + v6 复合诗词题", 19739, bank.count());
        assertEquals(Integer.valueOf(6867), bank.countBySubject().get("语文"));
        assertEquals(Integer.valueOf(6576), bank.countBySubject().get("数学"));
        assertEquals(Integer.valueOf(6296), bank.countBySubject().get("英语"));
        assertTrue("每个年级至少还有原有的 3000 题", bank.countByGrade().get(3) >= 3000);

        // 原有题库必须逐题保留：每个年级、每个学科恰好 1000 题（id 不以 ext- 开头）
        int[][] perGradeSubject = new int[7][3];
        int extra = 0;
        for (QuestionBank.Question q : bank.all()) {
            if (q.id != null && q.id.startsWith("ext-")) {
                extra++;
                continue;
            }
            if (q.id != null && q.id.startsWith("poem-")) {
                continue;   // v6 复合诗词题单独统计
            }
            int idx = "语文".equals(q.subject) ? 0 : ("数学".equals(q.subject) ? 1 : 2);
            perGradeSubject[q.grade][idx]++;
        }
        assertEquals("v5 追加题里未被复合题替换的部分", 990, extra);
        for (int g = 1; g <= 6; g++) {
            for (int s = 0; s < 3; s++) {
                assertEquals("原有题库 年级 " + g + " 学科序号 " + s + " 应仍有 1000 题",
                        1000, perGradeSubject[g][s]);
            }
        }
    }

    @Test
    public void extendedQuestionsKeepFormatAndCoverNewTopics() {
        QuestionBank bank = QuestionBank.get();
        boolean sequence = false, plural = false, formula = false;
        int extra = 0;
        for (QuestionBank.Question q : bank.all()) {
            if (q.id == null || !q.id.startsWith("ext-")) {
                continue;
            }
            extra++;
            assertEquals("新题正确项下标统一为 0（出题时再打乱）", 0, q.answer);
            assertEquals("新题必须有 4 个选项", 4, q.options.size());
            assertEquals("新题选项不能重复", 4, new HashSet<String>(q.options).size());
            assertTrue("新题年级需在 1-6 之间", q.grade >= 1 && q.grade <= 6);
            assertTrue("新题学科需为语数英", "语文".equals(q.subject)
                    || "数学".equals(q.subject) || "英语".equals(q.subject));
            if (q.q.contains("等差数列")) {
                sequence = true;
            }
            if (q.q.contains("的复数形式")) {
                plural = true;
            }
            if (q.q.contains("公式") || q.q.contains("怎么求")) {
                formula = true;
            }
        }
        assertEquals(990, extra);
        assertTrue("应包含等差数列题", sequence);
        assertTrue("应包含英语名词复数题", plural);
        assertTrue("应包含数学公式题", formula);
    }

    /**
     * v6：语文诗词题不再只问「作者是谁」「哪个朝代」「出自哪首诗」，
     * 改成「诗名 · 作者 · 朝代」这类复合答案，并且问到朝代时必然同时带作者。
     */
    @Test
    public void chinesePoemQuestionsCombineDynastyAndAuthor() {
        QuestionBank bank = QuestionBank.get();
        int composite = 0;
        int withDynasty = 0;
        for (QuestionBank.Question q : bank.all()) {
            if (!"语文".equals(q.subject)) {
                continue;
            }
            boolean allCombo = true;
            for (String o : q.options) {
                if (o.indexOf('·') < 0) {
                    allCombo = false;
                    break;
                }
            }
            if (q.id != null && q.id.startsWith("poem-")) {
                composite++;
                // 反查题（作者 + 朝代 → 名句）的选项是诗句本身，不要求带 ·
                if (!q.q.endsWith("名句是哪一句？")) {
                    assertTrue("复合题每个选项都要同时带作者与朝代：[" + q.q + "]", allCombo);
                }
                assertEquals("复合题固定 4 个选项", 4, q.options.size());
                assertEquals("复合题选项不能重复", 4, new HashSet<String>(q.options).size());
            }
            // 还在问朝代的语文题，选项里必须带作者（不能只给一个朝代名）
            if (q.q.contains("朝代") && q.options.size() == 4 && allCombo) {
                withDynasty++;
            }
        }
        assertTrue("复合诗词题应覆盖整套统编古诗文", composite >= 600);
        assertTrue("问到朝代的题应同时带作者", withDynasty >= composite * 3 / 4);
        // 老的「只问一个要素」的题干不应再大量存在
        int solo = 0;
        for (QuestionBank.Question q : bank.all()) {
            if ("语文".equals(q.subject) && (q.q.startsWith("《") || q.q.startsWith("「"))) {
                if (q.q.endsWith("的作者是谁？") || q.q.endsWith("出自哪首诗？")) {
                    solo++;
                }
            }
        }
        assertTrue("单要素诗词题应基本清理干净，实际还剩 " + solo, solo < 10);
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

    // ---------------------------------------------------------------- v1.7.20 单题增删改

    @Test
    public void saveOneAddsEditsAndDeletesOneQuestion() throws Exception {
        QuestionBank bank = QuestionBank.get();
        String original = bank.exportJson();
        try {
            int before = bank.count();
            QuestionBank.Question added = bank.saveOne("", "语文", 3, "测试题干：下一句是什么？",
                    Arrays.asList("对", "错"), 0, -1);
            assertEquals("题库 +1", before + 1, bank.count());
            assertEquals("新增题自动带年级标签", "语文 · 3年级", added.label());
            assertEquals("语文 · 3年级", bank.byId(added.id).label());

            bank.saveOne(added.id, "数学", 4, "改过的题干", Arrays.asList("A", "B", "C"), 1, -1);
            QuestionBank.Question edited = bank.byId(added.id);
            assertNotNull(edited);
            assertEquals("数学 · 4年级", edited.label());
            assertEquals("改过的题干", edited.q);
            assertEquals(3, edited.options.size());
            assertEquals(1, edited.answer);
            assertEquals("编辑不应改变题量", before + 1, bank.count());

            assertTrue(bank.deleteOne(added.id, -1));
            assertEquals("删除后题量复原", before, bank.count());
            assertNull(bank.byId(added.id));
        } finally {
            bank.save(original);
        }
    }

    @Test
    public void saveOneRejectsBadInput() throws Exception {
        QuestionBank bank = QuestionBank.get();
        String original = bank.exportJson();
        try {
            try {
                bank.saveOne("", "语文", 3, "题干", Arrays.asList("只有一个"), 0, -1);
                fail("少于 2 个选项必须拒绝");
            } catch (Exception expected) {
            }
            try {
                bank.saveOne("", "语文", 3, "题干", Arrays.asList("A", "B"), 5, -1);
                fail("答案下标越界必须拒绝");
            } catch (Exception expected) {
            }
            try {
                bank.saveOne("", "", 3, "题干", Arrays.asList("A", "B"), 0, -1);
                fail("空科目必须拒绝");
            } catch (Exception expected) {
            }
        } finally {
            bank.save(original);
        }
    }

    @Test
    public void queryFiltersBySubjectGradeAndKeyword() throws Exception {
        QuestionBank bank = QuestionBank.get();
        List<QuestionBank.Question> page = bank.query("语文", 3, "", 0, 5);
        assertEquals(5, page.size());
        for (QuestionBank.Question q : page) {
            assertEquals("语文", q.subject);
            assertEquals(3, q.grade);
        }
        int total = bank.queryCount("语文", 3, "");
        assertTrue("三年级语文至少 1000 题", total >= 1000);
        assertTrue("分页不能超过总数", bank.query("语文", 3, "", total + 10, 5).isEmpty());
        // 关键词
        List<QuestionBank.Question> hit = bank.query("", -1, "拼音", 0, 3);
        assertTrue("关键词应能搜到拼音题", hit.size() > 0);
        assertTrue("关键词过滤后题量应少于全库", bank.queryCount("", -1, "拼音") < bank.count());
    }

    // ---------------------------------------------------------------- v6 难度星级

    @Test
    public void difficultyIsParsedFilteredAndDefaulted() throws Exception {
        QuestionBank bank = QuestionBank.get();
        String original = bank.exportJson();
        try {
            bank.save("{\"questions\":["
                    + "{\"id\":\"s1\",\"subject\":\"语文\",\"grade\":1,\"q\":\"易\","
                    + "\"options\":[\"a\",\"b\"],\"answer\":0,\"difficulty\":1},"
                    + "{\"id\":\"s5\",\"subject\":\"语文\",\"grade\":6,\"q\":\"难\","
                    + "\"options\":[\"a\",\"b\"],\"answer\":0,\"difficulty\":5},"
                    + "{\"id\":\"sd\",\"subject\":\"数学\",\"grade\":3,\"q\":\"默认\","
                    + "\"options\":[\"a\",\"b\"],\"answer\":0},"
                    + "{\"id\":\"ss\",\"subject\":\"英语\",\"grade\":4,\"q\":\"星号\","
                    + "\"options\":[\"a\",\"b\"],\"answer\":0,\"stars\":\"★★★★\"}]}");

            assertEquals(1, bank.byId("s1").difficulty);
            assertEquals(5, bank.byId("s5").difficulty);
            assertEquals("没写难度按年级推：3 年级 2 星", 2, bank.byId("sd").difficulty);
            assertEquals("★★☆☆☆", bank.byId("sd").stars());
            assertEquals("支持 stars:\"★★★★\" 写法", 4, bank.byId("ss").difficulty);

            assertEquals("1 星只有语文那一道", 1, bank.queryCount("", -1, 1, ""));
            assertEquals(1, bank.query("语文", -1, 5, "", 0, 10).size());
            assertEquals("按星筛选要和关键词叠加", 1, bank.queryCount("", -1, 2, "默认"));
            assertEquals("不限星级时全部命中", 4, bank.queryCount("", -1, -1, ""));
        } finally {
            bank.save(original);
        }
    }

    @Test
    public void saveOneKeepsDifficultyStars() throws Exception {
        QuestionBank bank = QuestionBank.get();
        String original = bank.exportJson();
        try {
            QuestionBank.Question hard = bank.saveOne("", "语文", 4, "五星题",
                    Arrays.asList("对", "错"), 0, 5, -1);
            assertEquals(5, hard.difficulty);
            assertEquals("★★★★★", hard.stars());
            assertEquals("落盘后星级仍在", 5, bank.byId(hard.id).difficulty);

            QuestionBank.Question auto = bank.saveOne("", "数学", 5, "按年级默认",
                    Arrays.asList("对", "错"), 0, 0, -1);
            assertEquals("传 0 表示按年级推：5 年级 4 星", 4, auto.difficulty);

            // 改题时可以顺手改星
            QuestionBank.Question edited = bank.saveOne(hard.id, "语文", 4, "改成两星",
                    Arrays.asList("对", "错"), 1, 2, -1);
            assertEquals("★★☆☆☆", edited.stars());
            assertEquals(2, bank.byId(hard.id).difficulty);
        } finally {
            bank.save(original);
        }
    }

    @Test
    public void builtinBankCarriesDifficultyForEveryQuestion() throws Exception {
        QuestionBank bank = QuestionBank.get();
        for (QuestionBank.Question q : bank.query("", -1, "", 0, 50)) {
            assertTrue("内置题应有 1–5 星难度", q.difficulty >= 1 && q.difficulty <= 5);
            assertEquals("星号由难度生成", 5, q.stars().length());
        }
        java.util.Map<Integer, Integer> dist = bank.countByDifficulty();
        assertEquals("五档都要有统计键", 5, dist.size());
        int sum = 0;
        for (int v : dist.values()) {
            sum += v;
        }
        assertEquals("各档题量之和等于全库", bank.count(), sum);
    }

    @Test
    public void deleteOneRefusesToEmptyTheBank() throws Exception {        QuestionBank bank = QuestionBank.get();
        String original = bank.exportJson();
        try {
            bank.save("{\"questions\":[{\"id\":\"only\",\"subject\":\"数学\",\"q\":\"1+1=?\","
                    + "\"options\":[\"2\",\"3\"],\"answer\":0}]}");
            try {
                bank.deleteOne("only", -1);
                fail("最后一道题不能删");
            } catch (Exception expected) {
            }
            assertEquals(1, bank.count());
        } finally {
            bank.save(original);
        }
    }

    @Test
    public void backupNowAndRestoreFileRoundTrip() throws Exception {
        QuestionBank bank = QuestionBank.get();
        String original = bank.exportJson();
        try {
            int before = bank.count();
            org.json.JSONObject bak = bank.backupNow();
            String name = bak.optString("name", "");
            assertTrue("备份文件名形如 bank-*.json", name.startsWith("bank-") && name.endsWith(".json"));
            assertTrue("备份应该出现在列表里", bank.backups().length() >= 1);

            bank.deleteOne("g1-chinese-01", -1);
            assertEquals(before - 1, bank.count());

            bank.restoreBackupFile(name);
            assertEquals("恢复备份后题量复原", before, bank.count());
            assertNotNull(bank.byId("g1-chinese-01"));

            assertTrue(bank.deleteBackupFile(name));
        } finally {
            bank.save(original);
        }
    }
}
