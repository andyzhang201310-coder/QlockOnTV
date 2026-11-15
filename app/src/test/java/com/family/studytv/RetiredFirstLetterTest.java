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

import java.util.regex.Pattern;

/** v1.10.8 下架「选单词首字母」题：内置题库与家长自定义题库升级都要清干净。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RetiredFirstLetterTest {

    private static final Pattern RETIRED = Pattern.compile(
            "第一个字母|首字母|开头字母|以哪个字母开头|以什么字母开头");

    @Before
    public void setUp() {
        QuestionBank.resetForTest();
        Prefs.setBankCustom(false);
        Prefs.setGrades("1,2,3,4,5,6");
        Prefs.setSubjects("语文,数学,英语,小学必备词汇");
        QuestionBank.get().ensureSeeded();
    }

    @Test
    public void builtinBankHasNoFirstLetterQuestionsButKeepsRealLetterKnowledge() {
        QuestionBank bank = QuestionBank.get();
        assertEquals("下架 414 题后总量", 25164, bank.count());
        int letterKnowledge = 0;
        for (QuestionBank.Question q : bank.all()) {
            assertFalse("内置题库不该再出现首字母题: " + q.q,
                    RETIRED.matcher(q.q).find() && q.id != null
                            && q.id.matches("g[1-6]-english-\\d+"));
            if (q.q.contains("字母表") || q.q.contains("英文字母一共有")
                    || q.q.contains("大写字母")) {
                letterKnowledge++;
            }
        }
        assertTrue("字母表 / 大小写这类正常知识点必须保留", letterKnowledge >= 3);
    }

    @Test
    public void onlyOfficialFirstLetterQuestionsAreRetired() throws Exception {
        assertTrue("官方首字母题", QuestionBank.isRetiredFirstLetter(new JSONObject(
                item("g1-english-925", "单词「bye」的第一个字母是？", "b", "q", "f", "u"))));
        assertTrue("官方开头字母题", QuestionBank.isRetiredFirstLetter(new JSONObject(
                item("g6-english-999", "「zoo」以哪个字母开头？", "z", "a", "b", "c"))));
        assertFalse("家长自己加的同款题不碰", QuestionBank.isRetiredFirstLetter(new JSONObject(
                item("mine-1", "单词「cat」的首字母是？", "c", "d", "e", "f"))));
        assertFalse("字母表顺序不是首字母题", QuestionBank.isRetiredFirstLetter(new JSONObject(
                item("g1-english-04", "字母 A 后面是哪个字母？", "B", "C", "D", "A"))));
        assertFalse("题干命中但选项是词组的题不碰", QuestionBank.isRetiredFirstLetter(
                new JSONObject(item("g1-english-926", "单词「apple」的首字母是？",
                        "a 字母", "b 字母", "c 字母", "d 字母"))));
        assertFalse("数学字母公式题不碰", QuestionBank.isRetiredFirstLetter(new JSONObject(
                item("ext-math-0009", "长方体表面积的字母公式是？", "S", "x", "y", "z"))));
    }

    @Test
    public void customBankUpgradeDropsRetiredQuestionsAndKeepsParentsOwn() throws Exception {
        String first1 = item("g1-english-925", "单词「bye」的第一个字母是？", "b", "q", "f", "u");
        String first2 = item("g6-english-999", "「zoo」以哪个字母开头？", "z", "a", "b", "c");
        String parent = item("mine-1", "单词「cat」的首字母是？", "c", "d", "e", "f");
        String letterTable = item("g1-english-04", "字母 A 后面是哪个字母？", "B", "C", "D", "A");
        String math = item("ext-math-0009", "长方体表面积的字母公式是？", "S", "x", "y", "z");
        String fresh = item("vocab-0001", "【单词词义】「apple」的中文意思是？",
                "苹果", "香蕉", "橘子", "梨");

        String cur = doc(9, first1, first2, parent, letterTable, math);
        String asset = doc(10, letterTable, fresh);
        QuestionBank.Migration m = QuestionBank.migrateMathText(cur, asset);
        assertNotNull(m.json);
        assertFalse("有自定义题时不能整库覆盖", m.pristine);

        JSONObject out = new JSONObject(m.json);
        assertEquals("版本升到 10，避免每次启动重扫", 10, out.optInt("version"));
        JSONArray qs = out.getJSONArray("questions");
        assertEquals("5 题 − 2 道首字母题 + 1 道新增题", 4, qs.length());
        boolean hasParent = false, hasLetterTable = false, hasMath = false, hasFresh = false;
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.getJSONObject(i);
            assertFalse("升级后不该残留首字母题",
                    QuestionBank.isRetiredFirstLetter(q));
            String id = q.optString("id", "");
            hasParent |= "mine-1".equals(id);
            hasLetterTable |= "g1-english-04".equals(id);
            hasMath |= "ext-math-0009".equals(id);
            hasFresh |= "vocab-0001".equals(id);
        }
        assertTrue("家长自己加的题保留", hasParent);
        assertTrue("字母表题保留", hasLetterTable);
        assertTrue("数学字母公式题保留", hasMath);
        assertTrue("内置新增题仍要能补进来", hasFresh);
    }

    @Test
    public void migrationIsIdempotentAfterRetirement() throws Exception {
        String letterTable = item("g1-english-04", "字母 A 后面是哪个字母？", "B", "C", "D", "A");
        String cur = doc(10, letterTable);
        QuestionBank.Migration m = QuestionBank.migrateMathText(cur, cur);
        assertTrue("已是新版时不该再改动", m.pristine);
        assertEquals(0, m.changed);
    }

    private static String item(String id, String q, String a, String b, String c, String d) {
        return "{\"id\":\"" + id + "\",\"subject\":\"英语\",\"grade\":1,\"q\":\"" + q
                + "\",\"options\":[\"" + a + "\",\"" + b + "\",\"" + c + "\",\"" + d
                + "\"],\"answer\":0,\"difficulty\":1}";
    }

    private static String doc(int version, String... items) {
        StringBuilder sb = new StringBuilder("{\"version\":").append(version)
                .append(",\"count\":").append(items.length)
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
