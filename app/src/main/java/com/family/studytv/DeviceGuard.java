package com.family.studytv;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserManager;

/**
 * 防卸载的统一入口：设备管理员（无需 adb）+ 设备所有者（需 adb 一次，可禁用系统卸载按钮）。
 *
 * <p>关键事实：系统卸载流程由系统安装器控制，第三方应用没有任何机会拦截并弹出自己的输入框。
 * 因此「必须输卸载码才能卸载」只能两段式实现：
 * ① 设备所有者调用 setUninstallBlocked 让系统卸载按钮直接置灰；
 * ② 想卸载时必须回到本应用，输入卸载码通过后才解封并跳转系统卸载界面。
 */
public final class DeviceGuard {

    private DeviceGuard() {
    }

    public static ComponentName admin(Context c) {
        return new ComponentName(c, StudyDeviceAdmin.class);
    }

    private static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static boolean isAdminActive(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            return d != null && d.isAdminActive(admin(c));
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isDeviceOwner(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            return d != null && d.isDeviceOwnerApp(c.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isUninstallBlocked(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            return d != null && d.isUninstallBlocked(admin(c), c.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    /** 拉起系统「激活设备管理员」确认页 */
    public static Intent activateIntent(Context c) {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(c));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "开启后，卸载「题锁」需要先输入卸载码，孩子无法直接删掉。");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * 按当前设置同步系统状态。返回系统卸载锁是否真的生效（只有设备所有者才能生效）。
     */
    public static boolean sync(Context c) {
        boolean want = Prefs.uninstallGuard();
        boolean owner = isDeviceOwner(c);

        if (owner) {
            try {
                dpm(c).setUninstallBlocked(admin(c), c.getPackageName(), want);
            } catch (Throwable ignored) {
            }
            try {
                // 设备所有者可用用户限制禁止「恢复出厂设置」，避免一恢复就绕过全部管控
                boolean freezeFactoryReset = want && Prefs.blockFactoryReset();
                if (freezeFactoryReset) {
                    dpm(c).addUserRestriction(admin(c), UserManager.DISALLOW_FACTORY_RESET);
                } else {
                    dpm(c).clearUserRestriction(admin(c), UserManager.DISALLOW_FACTORY_RESET);
                }
            } catch (Throwable ignored) {
            }
        } else if (!want && isAdminActive(c)) {
            try {
                dpm(c).setUninstallBlocked(admin(c), c.getPackageName(), false);
            } catch (Throwable ignored) {
            }
        }
        return want && isUninstallBlocked(c);
    }

    /**
     * 卸载码校验通过后的解锁流程：解除系统锁 → 退出设备所有者 → 注销设备管理员 → 跳系统卸载页。
     */
    public static boolean release(Context c) {
        boolean ok = true;
        try {
            if (isDeviceOwner(c)) {
                dpm(c).setUninstallBlocked(admin(c), c.getPackageName(), false);
                dpm(c).clearUserRestriction(admin(c), UserManager.DISALLOW_FACTORY_RESET);
                dpm(c).clearDeviceOwnerApp(c.getPackageName());
            }
        } catch (Throwable e) {
            ok = false;
        }
        try {
            if (isAdminActive(c)) {
                dpm(c).removeActiveAdmin(admin(c));
            }
        } catch (Throwable e) {
            ok = false;
        }
        Prefs.setUninstallGuard(false);
        return ok;
    }

    /** 打开系统卸载界面（必须在 release() 之后调用，否则按钮是灰的） */
    public static void openSystemUninstall(Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    public static String adbCommand(Context c) {
        return "adb shell dpm set-device-owner "
                + c.getPackageName() + "/" + StudyDeviceAdmin.class.getName();
    }

    /** 电视端 / 手机端共用的状态描述 */
    public static String statusLine(Context c) {
        if (!Prefs.uninstallGuard()) {
            return "已关闭（系统里可直接卸载）";
        }
        if (isUninstallBlocked(c)) {
            return "已锁定（系统卸载按钮已禁用，必须输入卸载码）";
        }
        if (isDeviceOwner(c)) {
            return "设备所有者已就绪，但系统锁未生效，请开关一次防卸载";
        }
        if (isAdminActive(c)) {
            return "基础保护（卸载前必须先停用设备管理员）";
        }
        return "未生效（请先激活设备管理员）";
    }

    /** 设备所有者设置步骤，电视端弹窗与手机管理页共用 */
    public static String ownerGuide(Context c) {
        return "要把系统里的「卸载」按钮彻底禁用，需要电脑执行一次下面的命令，之后永久有效：\n\n"
                + adbCommand(c) + "\n\n"
                + "步骤：\n"
                + "① 电视/盒子进入 设置 → 关于 → 连点版本号打开「开发者选项」，再打开 USB 调试\n"
                + "② 或用同一 WiFi 的无线调试：adb connect 电视IP:5555\n"
                + "③ 电脑执行上面的命令，看到 Success 即成功\n"
                + "④ 重新打开本应用，「设备所有者」会显示已生效\n\n"
                + "常见失败：\n"
                + "· Not allowed to set the device owner —— 设备上还有已登录账号，"
                + "先到 设置 → 账户 里全部删除再试\n"
                + "· already set —— 已经设置过，不需要重复执行\n\n"
                + "没有电脑也能用：只激活设备管理员，孩子从系统设置卸载时会先被要求"
                + "「停用设备管理员」，多一道他搞不定的门槛。";
    }
}
