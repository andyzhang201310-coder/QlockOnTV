package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;
import android.view.KeyEvent;

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
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowDisplay;

import android.widget.TextView;

/**
 * v1.8.1 锁屏页本体：文案按当前规则生成、返回键不退出、未锁定时自动退出。
 * 版式与截图对齐（图标 / 大标题 / 副标题 / 底部小字），这里只锁行为与文案。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LimitActivityTest {

    private Context ctx;
    private ActivityController<LimitActivity> controller;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
        ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE).edit().clear().apply();
        LimitActivity.running = false;
        // 屏幕醒着：否则锁屏页会按待机闸门直接退出
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        ShadowPowerManager spm = Shadows.shadowOf(pm);
        spm.setIsInteractive(true);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        ShadowDisplay sd = Shadows.shadowOf(dm.getDisplay(Display.DEFAULT_DISPLAY));
        sd.setState(Display.STATE_ON);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            try {
                controller.pause().stop().destroy();
            } catch (Throwable ignored) {
            }
        }
        LimitActivity.running = false;
    }

    private void limit(boolean enabled, int perDay, int used) throws Exception {
        JSONObject cfg = Rules.defaults();
        cfg.put("roundsLimitEnabled", enabled);
        cfg.put("roundsPerDay", perDay);
        Rules.save(ctx, cfg);
        java.util.Calendar now = java.util.Calendar.getInstance();
        String today = now.get(java.util.Calendar.YEAR) + "-" + (now.get(java.util.Calendar.MONTH) + 1)
                + "-" + now.get(java.util.Calendar.DAY_OF_MONTH);
        ctx.getSharedPreferences("studytv", Context.MODE_PRIVATE).edit()
                .putString("rules_round_date", today)
                .putInt("rules_round_count", used).apply();
    }

    private String text(int id) {
        return ((TextView) controller.get().findViewById(id)).getText().toString();
    }

    @Test
    public void 用满后显示截图同款三段文案() throws Exception {
        limit(true, 3, 3);
        controller = Robolectric.buildActivity(LimitActivity.class).setup();
        assertEquals("今天的次数已用完", text(R.id.tvLimitTitle));
        assertEquals("明天 00:00 恢复", text(R.id.tvLimitSubtitle));
        assertEquals("每日 3 轮 · 今天已弹 3 轮", text(R.id.tvLimitFootnote));
        assertTrue(LimitActivity.running);
        assertFalse(controller.get().isFinishing());
    }

    @Test
    public void 返回键不能退出锁屏页() throws Exception {
        limit(true, 3, 3);
        controller = Robolectric.buildActivity(LimitActivity.class).setup();
        LimitActivity a = controller.get();
        a.onKeyDown(KeyEvent.KEYCODE_BACK, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        a.onBackPressed();
        assertFalse(a.isFinishing());
    }

    @Test
    public void 没有锁定时进入即自动退出() throws Exception {
        limit(false, 3, 99);
        controller = Robolectric.buildActivity(LimitActivity.class).setup();
        assertTrue(controller.get().isFinishing());
    }

    @Test
    public void 家长放行后不再锁屏() throws Exception {
        limit(true, 3, 3);
        Rules.grantLimitPass(ctx);
        controller = Robolectric.buildActivity(LimitActivity.class).setup();
        assertTrue(controller.get().isFinishing());
    }

    @Test
    public void 待机时被拉起立刻退出且不标记运行() throws Exception {
        limit(true, 3, 3);
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        Shadows.shadowOf(pm).setIsInteractive(false);
        controller = Robolectric.buildActivity(LimitActivity.class).setup();
        assertTrue(controller.get().isFinishing());
        assertFalse(LimitActivity.running);
    }
}
