package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplay;

import java.util.Calendar;

/**
 * v1.10.3：每日轮数上限用满后必须真的不再弹题。
 *
 * <p>真机现象：后台写「今日已弹 23 / 16 轮 · 已用完 · 今日已放行」，电视上却还在弹题。
 * 根因是家长「立即解锁」把 {@code limitLocked()} 变成 false，而所有弹题入口都拿它当
 * 额度闸门；放行语义其实是「只解除整屏锁」，不该顺带恢复额度。同时残留答题页靠
 * pending 被 v1.10.2 的自愈逻辑每 2 秒拉回前台，看起来就像还在继续弹。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DailyLimitGuardTest {

    private Context ctx;

    @Before
    public void setUp() throws Exception {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
        sp().edit().clear().apply();
        Prefs.setFirstRunDone(true);
        Prefs.setEnabled(true);
        Prefs.setAutoQuiz(true);
        QuestionBank.resetForTest();
        QuestionBank.get().ensureSeeded();
        Prefs.setQuizCount(5);
        Prefs.setSubjects("语文,数学,英语");
        screenOn();
        resetFlags();
    }

    @After
    public void tearDown() throws Exception {
        resetFlags();
        Prefs.setPending(false);
        // 「自由练习」用例会把 playMode / 上限开关落盘，必须还原：否则并行执行时
        // 会污染 QuizActivityTest（practice 模式答对 5 题不关弹窗）。
        Rules.save(ctx, Rules.defaults());
        sp().edit().remove("rules_round_date").remove("rules_round_count")
                .remove("rules_limit_pass_date").apply();
    }

    private SharedPreferences sp() {
        return ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE);
    }

    private void screenOn() {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        Shadows.shadowOf(pm).setIsInteractive(true);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        ShadowDisplay sd = Shadows.shadowOf(dm.getDisplay(Display.DEFAULT_DISPLAY));
        sd.setState(Display.STATE_ON);
    }

    private void resetFlags() {
        QuizActivity.running = false;
        QuizActivity.foreground = false;
        QuizActivity.allowBringBack = false;
        LimitActivity.running = false;
        LimitActivity.foreground = false;
        LimitActivity.allowBringBack = false;
    }

    private void rules(boolean enabled, int perDay) throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("roundsLimitEnabled", enabled);
        cfg.put("roundsPerDay", perDay);
        Rules.save(ctx, cfg);
    }

    private void rounds(int n) {
        sp().edit().putString("rules_round_date", dayKey(0))
                .putInt("rules_round_count", n).apply();
    }

    private static String dayKey(int offsetDays) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, offsetDays);
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1)
                + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    private void destroy(ActivityController<?> c) {
        if (c == null) {
            return;
        }
        try {
            c.pause().stop().destroy();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 额度语义

    @Test
    public void 家长放行不解除额度闸门() throws Exception {
        rules(true, 3);
        rounds(3);
        assertTrue(Rules.quizExhausted(ctx));
        assertTrue(Rules.limitLocked(ctx));

        Rules.grantLimitPass(ctx);
        assertFalse("放行只关掉整屏锁", Rules.limitLocked(ctx));
        assertTrue("额度仍然用满：旧版正是这里被误判成 false，才继续弹了 7 轮",
                Rules.quizExhausted(ctx));
    }

    @Test
    public void 没开上限或没用满都不算额度耗尽() throws Exception {
        rules(false, 3);
        rounds(99);
        assertFalse(Rules.quizExhausted(ctx));

        rules(true, 5);
        rounds(4);
        assertFalse(Rules.quizExhausted(ctx));
    }

    @Test
    public void 跨天与调高上限都能恢复额度() throws Exception {
        rules(true, 3);
        rounds(3);
        Rules.grantLimitPass(ctx);
        assertTrue(Rules.quizExhausted(ctx));

        sp().edit().putString("rules_round_date", dayKey(-1))
                .putInt("rules_round_count", 99).apply();
        assertEquals(0, Rules.roundsToday(ctx));
        assertFalse("跨天自动清零", Rules.quizExhausted(ctx));

        rounds(3);
        rules(true, 8);
        assertFalse("调高上限后额度恢复", Rules.quizExhausted(ctx));
    }

    @Test
    public void 上限没启用时家长解锁不写今日已放行() throws Exception {
        rules(false, 16);
        rounds(23);
        Prefs.setPending(true);

        GuardService.applyUnlock(ctx);

        assertFalse("随手点一次解锁不该让状态卡显示「今日已放行」",
                Rules.limitPassedToday(ctx));
        assertFalse(Prefs.isPending());
        assertFalse("没启用上限就不存在额度用满", Rules.quizExhausted(ctx));
    }

    @Test
    public void 用满后家长解锁解除锁屏但不恢复额度() throws Exception {
        rules(true, 16);
        rounds(16);

        GuardService.applyUnlock(ctx);

        assertTrue("用满后解锁必须解除锁屏", Rules.limitPassedToday(ctx));
        assertFalse(Rules.limitLocked(ctx));
        assertTrue("放行不恢复额度", Rules.quizExhausted(ctx));
    }

    // ---------------------------------------------------------------- 弹题入口

    @Test
    public void 额度用满时showQuiz不再出题也不计轮数() throws Exception {
        rules(true, 3);
        rounds(3);
        Rules.grantLimitPass(ctx);
        Prefs.setPending(true);

        GuardService svc = Robolectric.setupService(GuardService.class);
        java.lang.reflect.Method showQuiz = GuardService.class.getDeclaredMethod("showQuiz");
        showQuiz.setAccessible(true);
        showQuiz.invoke(svc);

        assertEquals("放行后用满不能再加一轮", 3, Rules.roundsToday(ctx));
        assertFalse("必须清掉 pending，否则 ensureVisible 会把残留答题页拉回来",
                Prefs.isPending());
        assertFalse("不该再拉起答题页", QuizActivity.running);
    }

    @Test
    public void 额度用满时未放行改弹锁屏页() throws Exception {
        rules(true, 3);
        rounds(3);
        Prefs.setPending(true);

        GuardService svc = Robolectric.setupService(GuardService.class);
        java.lang.reflect.Method showQuiz = GuardService.class.getDeclaredMethod("showQuiz");
        showQuiz.setAccessible(true);
        showQuiz.invoke(svc);

        assertEquals(3, Rules.roundsToday(ctx));
        Intent i = Shadows.shadowOf(svc).getNextStartedActivity();
        assertNotNull("用满且未放行时应该改弹锁屏页", i);
        assertEquals(LimitActivity.class.getName(), i.getComponent().getClassName());
    }

    @Test
    public void 延迟送达的答题页启动请求直接作废() throws Exception {
        rules(true, 3);
        rounds(3);
        Rules.grantLimitPass(ctx);
        Prefs.setPending(true);

        ActivityController<QuizActivity> c = Robolectric.buildActivity(QuizActivity.class).create();
        assertTrue("用满后不该再创建答题页", c.get().isFinishing());
        assertFalse("onCreate 不能把 pending 又写成 true", Prefs.isPending());
        assertFalse(QuizActivity.running);
        destroy(c);
    }

    @Test
    public void 自由练习不受每日上限约束() throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("roundsLimitEnabled", true);
        cfg.put("roundsPerDay", 3);
        cfg.put("playMode", "practice");
        Rules.save(ctx, cfg);
        rounds(3);

        assertTrue(Rules.quizExhausted(ctx));
        ActivityController<QuizActivity> c = Robolectric.buildActivity(QuizActivity.class).create();
        assertFalse("家长带练的自由练习不该被每日上限挡住", c.get().isFinishing());
        assertTrue(QuizActivity.running);
        destroy(c);
    }

    @Test
    public void 桌面图标用满且已放行时不再跳答题页() throws Exception {
        rules(true, 3);
        rounds(3);
        Rules.grantLimitPass(ctx);

        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create();
        assertTrue(c.get().isFinishing());
        Intent next = Shadows.shadowOf(c.get()).getNextStartedActivity();
        assertNull("放行不等于又能答题", next);
        destroy(c);
    }

    @Test
    public void 桌面图标用满且未放行时跳锁屏页() throws Exception {
        rules(true, 3);
        rounds(3);

        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create();
        Intent next = Shadows.shadowOf(c.get()).getNextStartedActivity();
        assertNotNull("用满后点图标应该撞上锁屏页", next);
        assertEquals(LimitActivity.class.getName(), next.getComponent().getClassName());
        destroy(c);
    }
}
