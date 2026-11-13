package com.family.studytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 桌面图标的显示 / 隐藏。
 *
 * <p>启动入口是 Manifest 里的 activity-alias {@code .LauncherAlias}，目标是 MainActivity。
 * 只禁用 alias：MainActivity 本身仍是 enabled，后台 GuardService、开机广播、答题页
 * 都不受影响，答题锁照常按时间弹出；家长也仍可用
 * {@code adb shell am start -n com.family.studytv/.MainActivity} 显式进入应用，
 * 或在手机管理页点「显示桌面图标」恢复。
 *
 * <p>普通应用开启/关闭自己的组件不需要任何系统权限，也不需要设备管理员或无障碍，
 * 因此不会像无障碍服务那样破坏电视桌面的确认键行为。
 */
public final class LauncherIcon {

    private static final String ALIAS = "com.family.studytv.LauncherAlias";

    private LauncherIcon() {
    }

    public static ComponentName alias(Context c) {
        return new ComponentName(c, ALIAS);
    }

    /** PackageManager 返回的组件状态是否属于「已禁用」 */
    public static boolean isDisabledState(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    /** 以系统实际状态为准；查询异常时退回用户设定，避免管理页显示错误 */
    public static boolean isHidden(Context c) {
        try {
            return isDisabledState(c.getPackageManager().getComponentEnabledSetting(alias(c)));
        } catch (Throwable t) {
            return Prefs.launcherHidden();
        }
    }

    /** 返回「系统实际状态是否已变成目标状态」；同时把家长意图落到 Prefs */
    public static boolean setHidden(Context c, boolean hidden) {
        int want = hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        try {
            c.getPackageManager().setComponentEnabledSetting(alias(c), want,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            return false;
        }
        boolean ok = isHidden(c) == hidden;
        if (ok) {
            Prefs.setLauncherHidden(hidden);
        }
        return ok;
    }

    /**
     * 应用更新 / 开机后把家长设定的隐藏状态重新落到系统。
     * 只在实际状态与设定不一致时写一次，正常情况零开销。
     */
    public static void enforce(Context c) {
        try {
            if (isHidden(c) != Prefs.launcherHidden()) {
                setHidden(c, Prefs.launcherHidden());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 图标藏起来后，家长在电脑上的兜底进入方式 */
    public static String adbOpenCommand() {
        return "adb shell am start -n com.family.studytv/.MainActivity";
    }
}
