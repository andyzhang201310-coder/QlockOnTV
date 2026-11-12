package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 纯 JVM 用例：卸载界面判定，决定无障碍服务该不该把界面顶掉 */
public class UninstallShieldTest {

    private static final String SELF = "com.family.studytv";
    private static final String LABEL = "题锁";
    private static final long NOW = 1_700_000_000_000L;

    private boolean block(String pkg, String cls, String text, boolean guard, long allow) {
        return UninstallShield.shouldBlock(pkg, cls, text, SELF, LABEL, guard, allow, NOW);
    }

    @Test
    public void blocksInstallerUninstallScreenEvenWithoutText() {
        // 电视上读不到节点文本是常态，这种时候必须保守拦截
        assertTrue(block("com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity", null, true, 0L));
        assertTrue(block("com.android.packageinstaller",
                "com.android.packageinstaller.UninstallAppProgress", "", true, 0L));
    }

    @Test
    public void ignoresOtherPackages() {
        assertFalse(block("com.xiaomi.market", "com.xiaomi.market.UninstallActivity", LABEL, true, 0L));
        assertFalse(block("com.family.studytv", "com.family.studytv.QuizActivity", "卸载", true, 0L));
    }

    @Test
    public void ignoresNormalSettingsPages() {
        assertFalse(block("com.android.tv.settings", "com.android.tv.settings.MainSettings",
                "网络\n显示\n关于", true, 0L));
    }

    @Test
    public void blocksSettingsUninstallDialogOnlyForSelf() {
        assertTrue(block("com.android.tv.settings", "android.app.AlertDialog",
                "要卸载此应用吗？\n题锁\n取消 确定", true, 0L));
        assertFalse(block("com.android.tv.settings", "android.app.AlertDialog",
                "要卸载此应用吗？\n极光影视\n取消 确定", true, 0L));
    }

    @Test
    public void doesNotBlockAppInfoPageOrInstallScreen() {
        // 应用信息页有「卸载」按钮，但那是入口不是确认；顶掉它会让家长连信息页都进不去
        assertFalse(block("com.android.tv.settings",
                "com.android.settings.applications.InstalledAppDetails",
                "题锁\n版本 1.7.29\n强制停止\n卸载", true, 0L));
        // 安装界面（PackageInstallerActivity）不能被当成卸载拦掉，否则没法更新
        assertFalse(block("com.android.packageinstaller",
                "com.android.packageinstaller.PackageInstallerActivity",
                "安装\n题锁\n取消 安装", true, 0L));
    }

    @Test
    public void respectsGuardSwitchAndAllowWindow() {
        String pkg = "com.android.packageinstaller";
        String cls = "com.android.packageinstaller.UninstallerActivity";
        assertFalse("防卸载关掉时不拦", block(pkg, cls, LABEL, false, 0L));
        assertFalse("放行窗口内不拦", block(pkg, cls, LABEL, true, NOW + 1000L));
        assertTrue("放行窗口过期后继续拦", block(pkg, cls, LABEL, true, NOW - 1L));
    }

    @Test
    public void packageNameHintsCoverVendors() {
        assertTrue(UninstallShield.isSystemInstallerUi("com.google.android.packageinstaller"));
        assertTrue(UninstallShield.isSystemInstallerUi("com.android.permissioncontroller"));
        assertTrue(UninstallShield.isSystemInstallerUi("com.android.settings"));
        assertFalse(UninstallShield.isSystemInstallerUi(null));
        assertFalse(UninstallShield.isSystemInstallerUi("com.tencent.video"));
    }

    @Test
    public void allowWindowIsTenMinutes() {
        assertEquals(10L * 60_000L, UninstallShield.ALLOW_WINDOW_MS);
        assertEquals(NOW + UninstallShield.ALLOW_WINDOW_MS, UninstallShield.allowUntilFrom(NOW));
    }
}
