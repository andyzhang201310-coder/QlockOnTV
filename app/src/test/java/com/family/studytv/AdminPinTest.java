package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 答题页「管理」入口的密码校验：默认码、改码后旧码失效、连错冷却。
 * 这个入口现在是电视端唯一能看到二维码的路径，所以必须防着小孩乱试。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AdminPinTest {

    @Before
    public void setUp() {
        Prefs.setPin("1234");
    }

    @Test
    public void defaultPinWorks() {
        assertTrue(Prefs.verifyPin("1234"));
        assertEquals("成功一次就清零", 0, Prefs.pinFailCount());
        assertEquals(0L, Prefs.pinLockRemainMs());
    }

    @Test
    public void changedPinRejectsOldOne() {
        Prefs.setPin("864209");
        assertFalse(Prefs.verifyPin("1234"));
        assertTrue(Prefs.verifyPin("864209"));
    }

    @Test
    public void fourWrongAttemptsDoNotLock() {
        for (int i = 0; i < 4; i++) {
            assertFalse(Prefs.verifyPin("0000"));
        }
        assertEquals(4, Prefs.pinFailCount());
        assertEquals("4 次还不该锁", 0L, Prefs.pinLockRemainMs());
        assertTrue("没锁之前正确密码仍然能进", Prefs.verifyPin("1234"));
    }

    @Test
    public void fiveWrongAttemptsLockOutEvenCorrectPin() {
        for (int i = 0; i < 5; i++) {
            Prefs.verifyPin("0000");
        }
        assertTrue("第 5 次应触发冷却", Prefs.pinLockRemainMs() > 0);
        assertFalse("冷却期内正确密码也不放行", Prefs.verifyPin("1234"));
        assertTrue("冷却期应还在", Prefs.pinLockRemainMs() > 0);
    }

    @Test
    public void changingPinClearsLock() {
        for (int i = 0; i < 5; i++) {
            Prefs.verifyPin("0000");
        }
        assertTrue(Prefs.pinLockRemainMs() > 0);

        Prefs.setPin("5678");
        assertEquals("换密码应清掉冷却，避免家长自己被挡在外面", 0L, Prefs.pinLockRemainMs());
        assertTrue(Prefs.verifyPin("5678"));
    }

    @Test
    public void blankInputNeverPasses() {
        assertFalse(Prefs.verifyPin(null));
        assertFalse(Prefs.verifyPin(""));
        assertTrue(Prefs.pinFailCount() > 0);
    }
}
