package com.family.studytv;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 设备管理员 / 设备所有者的回调入口。
 *
 * <p>激活后系统卸载器会先要求「停用设备管理员」才能卸载，这是不需要 adb 的第一道门槛；
 * 被设为设备所有者（device owner）时还能直接禁用系统卸载按钮，成为真正的强制锁。
 */
public class StudyDeviceAdmin extends DeviceAdminReceiver {

    /** 停用确认框里的警告文案（Android 7.0+ 显示） */
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "停用后「题锁」将失去防卸载保护，孩子可以直接卸载本应用。\n\n"
                + "如确需停用，请先在应用内用卸载码解除保护。";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Prefs.setAdminDisabledAt(System.currentTimeMillis());
        try {
            Toast.makeText(context, "防卸载保护已被停用", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        Prefs.setAdminDisabledAt(0L);
        DeviceGuard.sync(context);
    }
}
