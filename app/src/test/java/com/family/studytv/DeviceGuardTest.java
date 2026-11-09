package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDevicePolicyManager;

/**
 * 设备管理员激活链路：电视上没有系统设置入口时也必须能走通。
 *
 * <p>重点回归的是「不再依赖设置里的设备管理员页面」这件事：
 * 静默激活优先，其次系统页，最后给 adb 命令，任何一步都不能只丢一句
 * 「设置里没有入口」就结束。
 *
 * <p>注意：Robolectric 的 ShadowDevicePolicyManager 没有实现带 refreshing 参数的
 * setActiveAdmin，静默激活这条分支在单测里必然失败并落到系统页分支，
 * 真机行为由 MuMu / 电视实测覆盖。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DeviceGuardTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = App.get();
        Prefs.setUninstallGuard(true);
    }

    @After
    public void tearDown() {
        Prefs.setUninstallGuard(true);
    }

    private ShadowDevicePolicyManager sdpm() {
        return Shadows.shadowOf((DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE));
    }

    @Test
    public void resultCodesAreDistinct() {
        assertNotEquals(DeviceGuard.ACTIVATE_OK, DeviceGuard.ACTIVATE_UI);
        assertNotEquals(DeviceGuard.ACTIVATE_UI, DeviceGuard.ACTIVATE_FAILED);
        assertNotEquals(DeviceGuard.ACTIVATE_OK, DeviceGuard.ACTIVATE_FAILED);
    }

    @Test
    public void basicCmdUsesSetActiveAdmin() {
        String cmd = DeviceGuard.adbActiveAdminCommand(ctx);
        assertTrue(cmd.startsWith("adb shell dpm set-active-admin "));
        assertTrue(cmd.contains("com.family.studytv/"));
        assertTrue(cmd.contains("StudyDeviceAdmin"));
        // 不能误写成 set-device-owner：那条要求电视上没有登录账号
        assertFalse(cmd.contains("set-device-owner"));
    }

    @Test
    public void ownerCmdUsesSetDeviceOwner() {
        String cmd = DeviceGuard.adbCommand(ctx);
        assertTrue(cmd.startsWith("adb shell dpm set-device-owner "));
        assertTrue(cmd.contains("StudyDeviceAdmin"));
    }

    @Test
    public void helpShowsBothCommands() {
        String help = DeviceGuard.activateHelp(ctx);
        assertTrue(help.contains(DeviceGuard.adbActiveAdminCommand(ctx)));
        assertTrue(help.contains(DeviceGuard.adbCommand(ctx)));
    }

    @Test
    public void guideNoLongerSaysOnlyThisPage() {
        String guide = DeviceGuard.ownerGuide(ctx);
        assertTrue(guide.contains("set-active-admin"));
        assertTrue(guide.contains("set-device-owner"));
        assertFalse(guide.contains("只能由本页触发"));
        assertFalse(guide.contains("别去设置里找"));
    }

    @Test
    public void activateShortCircuitsWhenActive() {
        sdpm().setActiveAdmin(DeviceGuard.admin(ctx));
        assertTrue(DeviceGuard.isAdminActive(ctx));
        assertEquals(DeviceGuard.ACTIVATE_OK, DeviceGuard.activate(ctx));
    }

    @Test
    public void activateReturnsKnownCodeWhenInactive() {
        assertFalse(DeviceGuard.isAdminActive(ctx));
        int r = DeviceGuard.activate(ctx);
        assertTrue(r == DeviceGuard.ACTIVATE_OK
                || r == DeviceGuard.ACTIVATE_UI
                || r == DeviceGuard.ACTIVATE_FAILED);
        // 静默激活成功的话，系统状态必须真的变了
        if (r == DeviceGuard.ACTIVATE_OK) {
            assertTrue(DeviceGuard.isAdminActive(ctx));
        }
    }

    @Test
    public void silentActivateNeverThrows() {
        // 返回值取决于 ROM，但绝不能把异常抛给调用方
        DeviceGuard.silentActivate(ctx);
    }

    @Test
    public void statusLineExplainsWhenOff() {
        Prefs.setUninstallGuard(false);
        assertTrue(DeviceGuard.statusLine(ctx).contains("可直接卸载"));
    }

    @Test
    public void componentNameIsFixed() {
        ComponentName cn = DeviceGuard.admin(ctx);
        assertEquals("com.family.studytv", cn.getPackageName());
        assertEquals("com.family.studytv.StudyDeviceAdmin", cn.getClassName());
    }
}
