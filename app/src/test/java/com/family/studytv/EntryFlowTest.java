package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.widget.Button;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 入口行为：
 * · 装好后第一次打开 → 显示一次二维码说明页，之后不再出现；
 * · 平时打开应用 → 直接进答题页；
 * · 只有家长在答题页按「管理」输对密码，才会带 EXTRA_INFO 打开二维码页。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EntryFlowTest {

    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        QuizActivity.running = false;
        QuestionBank.resetForTest();
        QuestionBank.get().ensureSeeded();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            try {
                controller.pause().stop().destroy();
            } catch (Throwable ignored) {
            }
        }
        QuizActivity.running = false;
    }

    @Test
    public void launcherGoesStraightToQuiz() {
        Prefs.setFirstRunDone(true);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = controller.get();

        assertTrue("打开应用后不应停在信息页", a.isFinishing());

        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull("应跳转到答题页", next);
        assertEquals(QuizActivity.class.getName(), next.getComponent().getClassName());
        assertNull("信息页只能在家长验证密码后打开，不能用 extra 之外的方式进入",
                a.findViewById(R.id.ivQr));
    }

    @Test
    public void firstLaunchShowsQrPageExactlyOnce() {
        Prefs.setFirstRunDone(false);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = controller.get();

        assertFalse("首次安装应先停在二维码说明页", a.isFinishing());
        assertTrue("首次展示后要记账，避免每次都弹", Prefs.firstRunDone());

        TextView url = (TextView) a.findViewById(R.id.tvUrl);
        assertNotNull(url);
        assertTrue("首次页应给出手机访问地址", url.getText().toString().startsWith("http://"));

        Button close = (Button) a.findViewById(R.id.btnClose);
        assertEquals("首次页的关闭按钮改成「开始答题」", "开始答题", close.getText().toString());

        close.performClick();
        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull("按「开始答题」进入答题页", next);
        assertEquals(QuizActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(a.isFinishing());

        // 第二次打开：不再显示二维码，直接答题
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity b = second.get();
        assertTrue("第二次打开不应停在信息页", b.isFinishing());
        Intent again = shadowOf(b).getNextStartedActivity();
        assertNotNull(again);
        assertEquals(QuizActivity.class.getName(), again.getComponent().getClassName());
        try {
            second.pause().stop().destroy();
        } catch (Throwable ignored) {
        }
    }

    @Test
    public void infoPageOnlyAppearsWithExtra() {
        Prefs.setFirstRunDone(true);
        Intent i = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_INFO, true);
        controller = Robolectric.buildActivity(MainActivity.class, i).setup();
        MainActivity a = controller.get();

        assertFalse("带 EXTRA_INFO 时应停留在信息页", a.isFinishing());
        TextView status = (TextView) a.findViewById(R.id.tvStatus);
        assertNotNull(status);
        String text = status.getText().toString();
        assertTrue("信息页应显示弹题频率", text.contains("弹题频率"));
        assertTrue("信息页应显示题库数量", text.contains("题库存量"));

        TextView url = (TextView) a.findViewById(R.id.tvUrl);
        assertNotNull(url);
        assertTrue("信息页应给出手机访问地址", url.getText().toString().startsWith("http://"));

        Button close = (Button) a.findViewById(R.id.btnClose);
        assertEquals("家长主动打开的信息页，关闭按钮就是「关闭」", "关闭", close.getText().toString());
    }

    @Test
    public void infoPageHasNoSettingControls() {
        Prefs.setFirstRunDone(true);
        Intent i = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
                .putExtra(MainActivity.EXTRA_INFO, true);
        controller = Robolectric.buildActivity(MainActivity.class, i).setup();
        MainActivity a = controller.get();

        // 电视端不再提供参数修改入口，只剩 防卸载设置 / 刷新二维码 / 关闭
        assertNotNull(a.findViewById(R.id.btnSecurity));
        assertNotNull(a.findViewById(R.id.btnRefresh));
        assertNotNull(a.findViewById(R.id.btnClose));
    }

    @Test
    public void securityPageRenders() {
        ActivityController<SecurityActivity> c =
                Robolectric.buildActivity(SecurityActivity.class).setup();
        SecurityActivity s = c.get();

        TextView guard = (TextView) s.findViewById(R.id.tvGuard);
        assertNotNull(guard);
        assertTrue("应显示保护状态", guard.getText().toString().contains("保护状态"));
        assertTrue("应提示卸载码状态", guard.getText().toString().contains("卸载码"));

        try {
            c.pause().stop().destroy();
        } catch (Throwable ignored) {
        }
    }
}
