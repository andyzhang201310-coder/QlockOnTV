package com.family.studytv;

import java.util.Locale;

/**
 * 卸载界面的判定逻辑（纯函数，不依赖 Android，便于单元测试）。
 *
 * <p>背景：系统卸载由 {@code com.android.packageinstaller} 或「设置」应用完成，
 * 本应用没有任何机会插入自己的输入框。设备管理员 / 设备所有者在部分机型
 * （例如 Sony BRAVIA 的 Android 9，DPM 是空壳）完全不可用，唯一还能拦住的路是
 * 无障碍服务：一旦出现「卸载本应用」的窗口，立刻执行返回，把界面顶掉。
 *
 * <p>判定要克制：只拦「确实在卸载本应用」的窗口，不能把普通设置页、别的应用卸载页
 * 一起顶掉，否则会明显干扰正常使用。
 */
public final class UninstallShield {

    /** 卸载码校验通过后，放行多久（毫秒）——够家长走完系统卸载流程 */
    public static final long ALLOW_WINDOW_MS = 10L * 60_000L;

    /** 系统安装器 / 设置类应用的包名特征（各厂商叫法不同，用后缀匹配） */
    private static final String[] SYSTEM_UI_HINTS = {
            "packageinstaller",
            "permissioncontroller",
            "settings",
    };

    private UninstallShield() {
    }

    /** 事件来自系统安装器或设置页 */
    public static boolean isSystemInstallerUi(String pkg) {
        if (pkg == null) {
            return false;
        }
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String hint : SYSTEM_UI_HINTS) {
            if (p.endsWith(hint) || p.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 窗口类名看起来是卸载页（UninstallerActivity / UninstallAppProgress 等） */
    public static boolean looksLikeUninstallScreen(String className) {
        if (className == null) {
            return false;
        }
        String c = className.toLowerCase(Locale.ROOT);
        return c.contains("uninstall");
    }

    /** 类名是对话框（设置页里的卸载确认框类名就是 AlertDialog） */
    public static boolean isDialog(String className) {
        if (className == null) {
            return false;
        }
        String c = className.toLowerCase(Locale.ROOT);
        return c.contains("dialog") || c.contains("alert");
    }

    /**
     * 文本是不是「卸载确认」的口气。
     *
     * <p>不能只匹配「卸载」两个字：应用信息页上也有「卸载」按钮，那里被顶掉会让
     * 家长连应用信息都进不去。只认确认框里的问句。
     */
    public static boolean looksLikeUninstallConfirmText(String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("要卸载此应用吗")
                || t.contains("确定要卸载")
                || t.contains("卸载此应用")
                || t.contains("要卸载吗")
                || t.contains("do you want to uninstall")
                || t.contains("uninstall this app")
                || t.contains("uninstall app");
    }

    /**
     * 窗口文本里是否出现了本应用（用来区分「卸载别的应用」）。
     *
     * @param text 窗口节点文本拼接结果，读不到时传 null
     */
    public static boolean mentionsSelf(String text, String selfPkg, String selfLabel) {
        if (text == null || text.length() == 0) {
            return false;
        }
        if (selfPkg != null && selfPkg.length() > 0 && text.contains(selfPkg)) {
            return true;
        }
        return selfLabel != null && selfLabel.length() > 0 && text.contains(selfLabel);
    }

    /**
     * 最终判定：这个窗口事件要不要顶掉。
     *
     * @param eventPkg   事件来源包名
     * @param className  窗口类名
     * @param text       窗口可见文本（可为 null）
     * @param selfPkg    本应用包名
     * @param selfLabel  本应用显示名
     * @param guardOn    防卸载开关
     * @param allowUntil 放行截止时间戳，0 表示没放行
     * @param now        当前时间戳
     */
    public static boolean shouldBlock(String eventPkg, String className, String text,
                                      String selfPkg, String selfLabel,
                                      boolean guardOn, long allowUntil, long now) {
        if (!guardOn) {
            return false;
        }
        if (allowUntil > 0 && now < allowUntil) {
            return false;
        }
        if (!isSystemInstallerUi(eventPkg)) {
            return false;
        }
        // 两个入口：安装器的卸载页（类名带 uninstall），或设置页里弹出的卸载确认框
        boolean uninstallScreen = looksLikeUninstallScreen(className);
        boolean confirmDialog = isDialog(className) && looksLikeUninstallConfirmText(text);
        if (!uninstallScreen && !confirmDialog) {
            return false;
        }
        // 读不到文本（电视上很常见）：只能凭卸载页类名判断，保守拦截
        if (text == null || text.length() == 0) {
            return uninstallScreen;
        }
        return mentionsSelf(text, selfPkg, selfLabel);
    }

    /** 放行截止时间：从当前时刻起算 ALLOW_WINDOW_MS */
    public static long allowUntilFrom(long now) {
        return now + ALLOW_WINDOW_MS;
    }
}
