package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 卸载码校验 / 冷却 / 开关状态 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UninstallPrefsTest {

    /** Prefs.FILE 是 private，这里按已知文件名清场 */
    private static final String FILE = "studytv";

    @Before
    public void setUp() {
        App.get().getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .remove("uninstallHash")
                .remove("uninstallPinCustom")
                .remove("uninstallFail")
                .remove("uninstallLockUntil")
                .remove("uninstallGuard")
                .remove("blockFactoryReset")
                .remove("adminDisabledAt")
                .apply();
    }

    @Test
    public void defaultCodeIs246810() {
        assertFalse(Prefs.hasCustomUninstallPin());
        assertTrue(Prefs.verifyUninstallPin(UninstallCode.DEFAULT_CODE));
        assertFalse(Prefs.verifyUninstallPin("000000"));
    }

    @Test
    public void guardIsOnByDefault() {
        assertTrue(Prefs.uninstallGuard());
        Prefs.setUninstallGuard(false);
        assertFalse(Prefs.uninstallGuard());
    }

    @Test
    public void customCodeInvalidatesDefault() {
        Prefs.setUninstallPin("839201");
        assertTrue(Prefs.hasCustomUninstallPin());
        assertTrue(Prefs.verifyUninstallPin("839201"));
        assertFalse("改码后默认码必须失效", Prefs.verifyUninstallPin(UninstallCode.DEFAULT_CODE));
    }

    @Test
    public void hashNeverStoresPlainText() {
        Prefs.setUninstallPin("A1b2C3");
        String stored = Prefs.uninstallHash();
        assertEquals(64, stored.length());
        assertFalse(stored.contains("A1b2C3"));
    }

    @Test
    public void fiveWrongAttemptsTriggerLock() {
        for (int i = 0; i < 4; i++) {
            assertFalse(Prefs.verifyUninstallPin("999999"));
            assertEquals("前 4 次不该锁定", 0L, Prefs.uninstallLockRemainMs());
        }
        assertEquals(4, Prefs.uninstallFailCount());

        assertFalse(Prefs.verifyUninstallPin("999999"));
        assertEquals(5, Prefs.uninstallFailCount());
        assertTrue("第 5 次输错应触发冷却", Prefs.uninstallLockRemainMs() > 0);

        assertFalse("冷却期内正确码也必须拒绝", Prefs.verifyUninstallPin(UninstallCode.DEFAULT_CODE));
    }

    @Test
    public void resetClearsCounterAndLock() {
        for (int i = 0; i < 5; i++) {
            Prefs.verifyUninstallPin("999999");
        }
        assertTrue(Prefs.uninstallLockRemainMs() > 0);

        Prefs.resetUninstallFail();
        assertEquals(0, Prefs.uninstallFailCount());
        assertEquals(0L, Prefs.uninstallLockRemainMs());
        assertTrue(Prefs.verifyUninstallPin(UninstallCode.DEFAULT_CODE));
    }

    @Test
    public void successResetsFailCounter() {
        Prefs.verifyUninstallPin("888888");
        Prefs.verifyUninstallPin("888888");
        assertEquals(2, Prefs.uninstallFailCount());
        assertTrue(Prefs.verifyUninstallPin(UninstallCode.DEFAULT_CODE));
        assertEquals("成功一次就该清零", 0, Prefs.uninstallFailCount());
    }

    @Test
    public void adminDisabledTimeIsRecorded() {
        assertEquals(0L, Prefs.adminDisabledAt());
        Prefs.setAdminDisabledAt(1700000000000L);
        assertEquals(1700000000000L, Prefs.adminDisabledAt());
    }
}
