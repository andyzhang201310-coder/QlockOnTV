package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPowerManager;

/**
 * v1.7.33 待机闸门：电视待机 / 休眠时不能弹题，否则答题页的唤醒标志会把睡着的电视点亮。
 *
 * <p>这组测试锁三件事：两层判定（PowerManager + 显示屏状态）都要生效、
 * 取不到状态时不能误判成待机（否则孩子可以靠异常逃避）、答题页被拉起来时要立刻退出。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DeviceAwakeTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = App.get();
        screen(true);
        display(Display.STATE_ON);
    }

    private void screen(boolean interactive) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        ShadowPowerManager spm = Shadows.shadowOf(pm);
        spm.setIsInteractive(interactive);
    }

    private void display(int state) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Shadows.shadowOf(d).setState(state);
    }

    @Test
    public void interactiveScreenIsAwake() {
        assertTrue(DeviceAwake.isAwake(ctx));
        assertEquals("使用中", DeviceAwake.statusName(ctx));
    }

    @Test
    public void screenOffMeansAsleep() {
        screen(false);
        assertFalse(DeviceAwake.isAwake(ctx));
        assertEquals("待机", DeviceAwake.statusName(ctx));
    }

    /** 有些电视只关背光、PowerManager 仍报 interactive，靠显示屏状态兜住 */
    @Test
    public void displayOffMeansAsleepEvenIfInteractive() {
        screen(true);
        display(Display.STATE_OFF);
        assertFalse(DeviceAwake.isAwake(ctx));
    }

    @Test
    public void displayDozeSuspendMeansAsleep() {
        screen(true);
        display(Display.STATE_DOZE_SUSPEND);
        assertFalse(DeviceAwake.isAwake(ctx));
        assertEquals("深度待机", DeviceAwake.statusName(ctx));
    }

    /** 屏保 / 环境模式：屏幕亮着就不算待机，正常弹题 */
    @Test
    public void dozeWithoutSuspendStillCountsAsAwake() {
        screen(true);
        display(Display.STATE_DOZE);
        assertTrue(DeviceAwake.isAwake(ctx));
    }

    @Test
    public void asleepDeferTimestampPersists() {
        assertEquals(0L, Prefs.asleepDeferAt());
        Prefs.setAsleepDeferAt(1700000000000L);
        assertEquals(1700000000000L, Prefs.asleepDeferAt());
    }

    /** 兜底：系统把排队的答题页 create 起来时，待机状态下必须立刻退出，不能停在屏上 */
    @Test
    public void quizActivityBailsOutWhenAsleep() {
        screen(false);
        QuizActivity a = Robolectric.buildActivity(QuizActivity.class).create().get();
        assertTrue(a.isFinishing());
        assertFalse(QuizActivity.running);
    }

    @Test
    public void quizActivityKeepsRunningWhenAwake() {
        QuizActivity a = Robolectric.buildActivity(QuizActivity.class).create().get();
        assertFalse(a.isFinishing());
    }
}
