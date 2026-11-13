package com.family.studytv;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;
import android.view.WindowManager;

/**
 * 电视此刻是不是「醒着」（屏幕亮着、可以正常显示）。
 *
 * <p>待机 / 休眠时绝不弹答题页：答题页带 FLAG_TURN_SCREEN_ON，一 create 就会把面板点亮，
 * 到点弹题等于把睡着的电视叫醒。所以这项判定放在所有弹题条件之前，优先级最高。
 *
 * <p>两层判定：PowerManager 的交互状态 + 默认显示屏状态。有些电视待机时只关背光，
 * PowerManager 仍报 interactive，只靠一层会漏判。取不到状态时按「醒着」处理，
 * 避免异常导致永远不弹题、孩子可以靠异常逃避。
 */
public final class DeviceAwake {

    private DeviceAwake() {
    }

    public static boolean isAwake(Context c) {
        if (c == null) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        int st = displayState(c);
        return st != Display.STATE_OFF && st != Display.STATE_DOZE_SUSPEND;
    }

    /** 默认显示屏状态，-1 表示取不到 */
    public static int displayState(Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null) {
                    return d.getState();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                return wm.getDefaultDisplay().getState();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 给后台诊断用的一句话 */
    public static String statusName(Context c) {
        if (isAwake(c)) {
            return "使用中";
        }
        return displayState(c) == Display.STATE_DOZE_SUSPEND ? "深度待机" : "待机";
    }
}
