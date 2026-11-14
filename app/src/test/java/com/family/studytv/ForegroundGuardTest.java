package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
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
import org.robolectric.shadows.ShadowPowerManager;

/**
 * v1.10.2：连按遥控器主菜单（HOME）把答题页 / 锁屏页压到后台后，
 * 「实例还活着」和「电视上真的有弹窗」必须分开看，且必须能自己拉回来。
 *
 * <p>真机 logcat 证据：{@code Activity start request from 10157 stopped} —— 系统
 * app-switch 保护静默丢弃启动请求，Activity 收不到异常，原来的单次 relaunch 直接失效。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ForegroundGuardTest {

    private Context ctx;
    private ActivityController<QuizActivity> quiz;
    private ActivityController<LimitActivity> limit;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
        ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE).edit().clear().apply();
        resetFlags();
        QuestionBank.resetForTest();
        QuestionBank.get().ensureSeeded();
        Prefs.setQuizCount(5);
        Prefs.setSubjects("语文,数学,英语");
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        ShadowPowerManager spm = Shadows.shadowOf(pm);
        spm.setIsInteractive(true);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        ShadowDisplay sd = Shadows.shadowOf(dm.getDisplay(Display.DEFAULT_DISPLAY));
        sd.setState(Display.STATE_ON);
    }

    @After
    public void tearDown() {
        if (quiz != null) {
            try {
                quiz.pause().stop().destroy();
            } catch (Throwable ignored) {
            }
        }
        if (limit != null) {
            try {
                limit.pause().stop().destroy();
            } catch (Throwable ignored) {
            }
        }
        resetFlags();
        Prefs.setPending(false);
    }

    private void resetFlags() {
        QuizActivity.running = false;
        QuizActivity.foreground = false;
        QuizActivity.allowBringBack = false;
        LimitActivity.running = false;
        LimitActivity.foreground = false;
        LimitActivity.allowBringBack = false;
    }

    private void lockLimit() throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("roundsLimitEnabled", true);
        cfg.put("roundsPerDay", 3);
        Rules.save(ctx, cfg);
        java.util.Calendar now = java.util.Calendar.getInstance();
        String today = now.get(java.util.Calendar.YEAR) + "-" + (now.get(java.util.Calendar.MONTH) + 1)
                + "-" + now.get(java.util.Calendar.DAY_OF_MONTH);
        ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE).edit()
                .putString("rules_round_date", today)
                .putInt("rules_round_count", 3).apply();
    }

    @Test
    public void 按HOME后实例还在但不算前台显示() {
        quiz = Robolectric.buildActivity(QuizActivity.class).setup();
        assertTrue(QuizActivity.running);
        assertTrue(QuizActivity.foreground);
        assertFalse(QuizActivity.allowBringBack);

        quiz.pause();   // 遥控器主菜单：Activity 只是 stopped，不会 destroy

        assertTrue("Activity 实例仍活着", QuizActivity.running);
        assertFalse("但电视上已经没有弹窗了", QuizActivity.foreground);
        assertTrue("需要允许守护服务把自己拉回前台", QuizActivity.allowBringBack);
    }

    @Test
    public void 回到前台后停止拉回() {
        quiz = Robolectric.buildActivity(QuizActivity.class).setup();
        quiz.pause();
        assertTrue(QuizActivity.allowBringBack);
        quiz.resume();
        assertTrue(QuizActivity.foreground);
        assertFalse(QuizActivity.allowBringBack);
    }

    @Test
    public void 销毁后三个标记全部清零() {
        quiz = Robolectric.buildActivity(QuizActivity.class).setup();
        quiz.pause().stop().destroy();
        quiz = null;
        assertFalse(QuizActivity.running);
        assertFalse(QuizActivity.foreground);
        assertFalse(QuizActivity.allowBringBack);
    }

    @Test
    public void 锁屏页同样区分实例与前台() throws Exception {
        lockLimit();
        limit = Robolectric.buildActivity(LimitActivity.class).setup();
        assertTrue(LimitActivity.running);
        assertTrue(LimitActivity.foreground);
        assertFalse(LimitActivity.allowBringBack);

        limit.pause();
        assertTrue(LimitActivity.running);
        assertFalse(LimitActivity.foreground);
        assertTrue(LimitActivity.allowBringBack);

        limit.resume();
        assertTrue(LimitActivity.foreground);
        assertFalse(LimitActivity.allowBringBack);
    }

    @Test
    public void 拉回用Intent应把已有task提到前台() {
        Intent i = GuardService.frontIntent(ctx, false);
        assertEquals(QuizActivity.class.getName(), i.getComponent().getClassName());
        assertTrue("要能新建/复用独立 task", (i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("不能重复建实例", (i.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
        assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0);
        assertFalse("REORDER_TO_FRONT 对独立 task 无效，旧实现正是卡在这里",
                (i.getFlags() & Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0);

        Intent l = GuardService.frontIntent(ctx, true);
        assertEquals(LimitActivity.class.getName(), l.getComponent().getClassName());
    }

    @Test
    public void 系统静默拦下启动时会持续重试() throws Exception {
        // 让 ensureVisible 走「直接启动」分支，便于断言拉回请求
        java.lang.reflect.Field alive = GuardService.class.getDeclaredField("alive");
        alive.setAccessible(true);
        alive.set(null, null);

        quiz = Robolectric.buildActivity(QuizActivity.class).setup();
        quiz.pause();

        org.robolectric.util.Scheduler scheduler = Robolectric.getForegroundThreadScheduler();
        scheduler.advanceBy(1300);   // 1.2 秒后第一次拉回
        Intent first = Shadows.shadowOf(quiz.get()).getNextStartedActivity();
        assertNotNull("按 HOME 后必须发起拉回", first);
        assertEquals(QuizActivity.class.getName(), first.getComponent().getClassName());

        scheduler.advanceBy(2100);   // 系统丢弃了上一次，2 秒后应再试
        Intent second = Shadows.shadowOf(quiz.get()).getNextStartedActivity();
        assertNotNull("启动被系统静默拦截时不能只试一次就放弃", second);
        assertEquals(QuizActivity.class.getName(), second.getComponent().getClassName());
    }
}
