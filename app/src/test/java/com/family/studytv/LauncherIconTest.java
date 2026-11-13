package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 桌面图标隐藏：只禁别名、可恢复、更新后能自动重新套用。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LauncherIconTest {

    private Context c;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        Prefs.setLauncherHidden(false);
        c.getPackageManager().setComponentEnabledSetting(LauncherIcon.alias(c),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
    }

    @Test
    public void disabledStatesCountAsHidden() {
        assertTrue(LauncherIcon.isDisabledState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED));
        assertTrue(LauncherIcon.isDisabledState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertTrue(LauncherIcon.isDisabledState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED));
        assertFalse(LauncherIcon.isDisabledState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT));
        assertFalse(LauncherIcon.isDisabledState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED));
    }

    @Test
    public void aliasIsLauncherEntryNotMainActivity() {
        assertEquals("com.family.studytv.LauncherAlias", LauncherIcon.alias(c).getClassName());
        assertFalse("MainActivity 绝不能被禁用，否则后台服务与家长入口一起失效",
                LauncherIcon.alias(c).getClassName().endsWith(".MainActivity"));
    }

    @Test
    public void hideAndRestore() {
        assertFalse(LauncherIcon.isHidden(c));
        assertTrue(LauncherIcon.setHidden(c, true));
        assertTrue(LauncherIcon.isHidden(c));
        assertTrue(Prefs.launcherHidden());
        assertTrue(LauncherIcon.setHidden(c, false));
        assertFalse(LauncherIcon.isHidden(c));
        assertFalse(Prefs.launcherHidden());
    }

    @Test
    public void enforceReappliesHiddenStateAfterUpdate() {
        Prefs.setLauncherHidden(true);
        // 模拟 install -r 之后系统把组件状态重置回默认
        c.getPackageManager().setComponentEnabledSetting(LauncherIcon.alias(c),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
        assertFalse(LauncherIcon.isHidden(c));
        LauncherIcon.enforce(c);
        assertTrue(LauncherIcon.isHidden(c));
    }

    @Test
    public void enforceDoesNotHideWhenParentAskedToShow() {
        Prefs.setLauncherHidden(false);
        LauncherIcon.enforce(c);
        assertFalse(LauncherIcon.isHidden(c));
    }

    @Test
    public void adbOpenCommandStartsMainActivity() {
        assertTrue(LauncherIcon.adbOpenCommand().contains(".MainActivity"));
    }
}
