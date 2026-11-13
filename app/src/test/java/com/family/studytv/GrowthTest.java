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

/**
 * 成长记录（错题集）：错一次记一次、答对移出并计入已掌握、再错累加错误次数。
 * 与「错题冷却」共用同一份错题集，这里只验证展示用的计数与时间字段。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GrowthTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
        ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE).edit()
                .remove("rules_json").remove("rules_version")
                .remove("rules_wrong_total").remove("rules_wrong_date")
                .remove("rules_wrong_ids").remove("rules_wrong_map").remove("rules_round_seq")
                .remove("rules_grow_map").remove("rules_grow_total").remove("rules_grow_mastered")
                .apply();
    }

    private JSONObject item(String id) throws Exception {
        JSONArray items = Rules.growth(ctx).optJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                return o;
            }
        }
        return null;
    }

    @Test
    public void firstWrongCreatesEntryWithOneCount() throws Exception {
        Rules.noteWrong(ctx, "q1");
        JSONObject g = Rules.growth(ctx);
        assertEquals(1, g.optInt("active"));
        assertEquals(1, g.optInt("wrongTotal"));
        assertEquals(0, g.optInt("mastered"));
        JSONObject it = item("q1");
        assertEquals(1, it.optInt("n"));
        assertTrue(it.optLong("first") > 0L);
        assertEquals(it.optLong("first"), it.optLong("last"));
    }

    @Test
    public void secondWrongAccumulatesCountButKeepsFirstTime() throws Exception {
        Rules.noteWrong(ctx, "q1");
        long first = item("q1").optLong("first");
        Thread.sleep(5L);
        Rules.noteWrong(ctx, "q1");
        JSONObject g = Rules.growth(ctx);
        assertEquals(1, g.optInt("active"));      // 同一道题不会变成两条
        assertEquals(2, g.optInt("wrongTotal"));
        JSONObject it = item("q1");
        assertEquals(2, it.optInt("n"));
        assertEquals(first, it.optLong("first"));
        assertTrue(it.optLong("last") > first);
    }

    @Test
    public void correctAnswerRemovesEntryAndCountsMastered() throws Exception {
        Rules.noteWrong(ctx, "q1");
        Rules.noteWrong(ctx, "q2");
        Rules.clearWrong(ctx, "q1");
        JSONObject g = Rules.growth(ctx);
        assertEquals(1, g.optInt("active"));
        assertEquals(1, g.optInt("mastered"));
        assertEquals(null, item("q1"));
        assertTrue(item("q2") != null);
    }

    @Test
    public void correctAnswerOnCleanQuestionDoesNotCountMastered() {
        Rules.clearWrong(ctx, "never-wrong");
        JSONObject g = Rules.growth(ctx);
        assertEquals(0, g.optInt("active"));
        assertEquals(0, g.optInt("mastered"));
    }

    @Test
    public void parentRemovalDoesNotCountAsMastered() throws Exception {
        Rules.noteWrong(ctx, "q1");
        Rules.dropWrong(ctx, "q1");
        JSONObject g = Rules.growth(ctx);
        assertEquals(0, g.optInt("active"));
        assertEquals(0, g.optInt("mastered"));
        assertEquals(1, g.optInt("wrongTotal"));   // 累计答错是历史，不受移除影响
    }

    @Test
    public void clearPoolEmptiesListButKeepsGrowthTotals() throws Exception {
        Rules.noteWrong(ctx, "q1");
        Rules.noteWrong(ctx, "q2");
        Rules.clearWrong(ctx, "q2");               // 答对 q2 → 已掌握 = 1
        Rules.noteWrong(ctx, "q3");
        Rules.clearWrongPool(ctx);
        JSONObject g = Rules.growth(ctx);
        assertEquals(0, g.optInt("active"));
        assertEquals(3, g.optInt("wrongTotal"));
        assertEquals(1, g.optInt("mastered"));
        assertEquals(0, Rules.wrongIds(ctx).size());
    }

    @Test
    public void newestWrongComesFirst() throws Exception {
        Rules.noteWrong(ctx, "old");
        Thread.sleep(5L);
        Rules.noteWrong(ctx, "new");
        JSONArray items = Rules.growth(ctx).optJSONArray("items");
        assertEquals("new", items.optJSONObject(0).optString("id"));
        assertEquals("old", items.optJSONObject(1).optString("id"));
    }

    @Test
    public void entryCarriesCooldownRoundForBackgroundDisplay() throws Exception {
        Rules.nextRoundSeq(ctx);
        Rules.noteWrong(ctx, "q1");
        JSONObject it = item("q1");
        assertEquals(Rules.roundSeq(ctx), it.optInt("round"));
        assertFalse(Rules.wrongDue(ctx, "q1", 3));   // 刚答错，还在冷却期
    }
}
