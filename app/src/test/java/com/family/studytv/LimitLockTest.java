package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Calendar;

/**
 * v1.8.1「今天的次数已用完」锁屏判定。
 *
 * <p>锁屏 = 开了每日轮数上限 + 今天已用满 + 家长今天没放行。三件事各自都能单独关掉这一页：
 * 关上限、跨天清零、家长放行；任何一条被误判都会让孩子白白被锁在电视外面。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LimitLockTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
        sp().edit().clear().apply();
    }

    private SharedPreferences sp() {
        return ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE);
    }

    /** 写入一份只关心轮数上限的规则 */
    private void rules(boolean limitEnabled, int perDay) throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("roundsLimitEnabled", limitEnabled);
        cfg.put("roundsPerDay", perDay);
        Rules.save(ctx, cfg);
    }

    /** 直接写「今天弹了几轮」，避免依赖真实时间推进 */
    private void rounds(int n) {
        sp().edit().putString("rules_round_date", dayKey(0)).putInt("rules_round_count", n).apply();
    }

    private static String dayKey(int offsetDays) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, offsetDays);
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    @Test
    public void 没开上限时用满也不锁() throws Exception {
        rules(false, 3);
        rounds(99);
        assertFalse(Rules.limitLocked(ctx));
        assertTrue(Rules.roundsLeft(ctx, Rules.get(ctx)));
    }

    @Test
    public void 开了上限但没用满不锁() throws Exception {
        rules(true, 5);
        rounds(4);
        assertFalse(Rules.limitLocked(ctx));
        assertFalse(Rules.limitReached(ctx, Rules.get(ctx)));
    }

    @Test
    public void 用满即锁且文案正确() throws Exception {
        rules(true, 5);
        rounds(5);
        assertTrue(Rules.limitReached(ctx, Rules.get(ctx)));
        assertTrue(Rules.limitLocked(ctx));
        assertEquals("每日 5 轮 · 今天已弹 5 轮", Rules.limitFootnote(ctx, Rules.get(ctx)));
        assertEquals("明天 00:00 恢复", Rules.limitResetText());
    }

    @Test
    public void 家长放行后今天不再锁() throws Exception {
        rules(true, 5);
        rounds(5);
        assertFalse(Rules.limitPassedToday(ctx));
        Rules.grantLimitPass(ctx);
        assertTrue(Rules.limitPassedToday(ctx));
        assertFalse(Rules.limitLocked(ctx));
        // 放行只是不解锁屏，不会把「已弹 5 轮」清零
        assertEquals(5, Rules.roundsToday(ctx));
    }

    @Test
    public void 跨天自动恢复() throws Exception {
        rules(true, 5);
        sp().edit().putString("rules_round_date", dayKey(-1)).putInt("rules_round_count", 99).apply();
        assertEquals(0, Rules.roundsToday(ctx));
        assertFalse(Rules.limitLocked(ctx));
    }

    @Test
    public void 家长放行只在当天有效() throws Exception {
        rules(true, 5);
        rounds(5);
        Rules.grantLimitPass(ctx);
        assertFalse(Rules.limitLocked(ctx));
        // 把放行日期改成昨天：等价于跨天，锁屏重新生效
        sp().edit().putString("rules_limit_pass_date", dayKey(-1)).apply();
        assertTrue(Rules.limitLocked(ctx));
    }

    @Test
    public void 调高上限后清放行标记可重新判定() throws Exception {
        rules(true, 5);
        rounds(5);
        Rules.grantLimitPass(ctx);
        assertFalse(Rules.limitLocked(ctx));
        Rules.clearLimitPass(ctx);
        assertTrue(Rules.limitLocked(ctx));
        // 上限从 5 调到 8：已弹 5 轮没到上限，自然解除
        rules(true, 8);
        assertFalse(Rules.limitLocked(ctx));
    }

    @Test
    public void 暂停拦截期间不锁屏() throws Exception {
        rules(true, 5);
        rounds(5);
        assertTrue(Rules.limitLocked(ctx));
        Prefs.setEnabled(false);
        assertFalse(Rules.limitLocked(ctx));
        Prefs.setEnabled(true);
        Prefs.setAutoQuiz(false);
        assertFalse(Rules.limitLocked(ctx));
        Prefs.setAutoQuiz(true);
        assertTrue(Rules.limitLocked(ctx));
    }

    @Test
    public void 轮数越界会被钳制() throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("roundsPerDay", 999);
        assertEquals(100, Rules.roundsPerDay(cfg));
        cfg.put("roundsPerDay", 0);
        assertEquals(1, Rules.roundsPerDay(cfg));
    }
}
