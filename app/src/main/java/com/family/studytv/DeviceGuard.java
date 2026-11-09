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

    /** 设备管理员已经生效（静默激活成功或本来就是激活状态） */
    public static final int ACTIVATE_OK = 1;
    /** 已拉起系统「激活设备管理员」确认页，等用户点「激活」 */
    public static final int ACTIVATE_UI = 2;
    /** 本机既不能静默激活，也没有可用的系统确认页（要有线/无线 adb 一条命令） */
    public static final int ACTIVATE_FAILED = 3;

    /**
     * 系统「激活设备管理员」确认页的常见实现。电视与手机不在同一个设置包里，
     * 隐式 Intent 解析不到时按顺序显式尝试，避免「设置里没入口」就彻底卡死。
     */
    private static final String[] ADMIN_ADD_ACTIVITIES = {
            "com.android.settings/.applications.specialaccess.deviceadmin.DeviceAdminAdd",
            "com.android.settings/.DeviceAdminAdd",
            "com.android.settings/.Settings$DeviceAdminSettingsActivity",
            "com.android.tv.settings/.deviceadmin.DeviceAdminAdd",
            "com.android.tv.settings/.DeviceAdminAdd",
    };

    private static final String EXPLAIN =
            "开启后，卸载「题锁」需要先输入卸载码，孩子无法直接删掉。";

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

    /**
     * 拉起系统「激活设备管理员」确认页。
     *
     * <p>千万不能加 {@code FLAG_ACTIVITY_NEW_TASK}：Android 14+ 的系统激活页
     * （Settings 的 DeviceAdminAdd）发现自己是新任务启动会直接
     * 「Cannot start ADD_DEVICE_ADMIN as a new task」然后 finish，
     * 表现就是点了按钮毫无反应。只有在没有 Activity 上下文时才不得不加。
     */
    public static Intent activateIntent(Context c) {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(c));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, EXPLAIN);
        if (!(c instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return i;
    }

    /**
     * 直接把本应用设为设备管理员，不弹任何系统页面。
     *
     * <p>系统对 setActiveAdmin 的校验是「调用方必须就是该 admin 所属的包」，
     * 所以应用给自己声明的 receiver 激活是被允许的，不需要跳设置、也不需要 adb。
     * 该方法在公开 SDK 里被标成 @SystemApi（编译期不导出），只能用反射调；
     * 少数厂商 ROM 会强制走 UI 或被非 SDK 接口策略拦下，此时返回 false，由
     * {@link #activate(Context)} 走系统页 / adb 兜底。
     */
    public static boolean silentActivate(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            if (d == null) {
                return false;
            }
            java.lang.reflect.Method m = DevicePolicyManager.class
                    .getMethod("setActiveAdmin", ComponentName.class, boolean.class);
            m.setAccessible(true);
            m.invoke(d, admin(c), Boolean.TRUE);
            return d.isAdminActive(admin(c));
        } catch (Throwable e) {
            android.util.Log.w("DeviceGuard", "silentActivate failed: " + e, e);
            return false;
        }
    }

    /**
     * 激活设备管理员的三级策略：静默激活 → 系统确认页 → 交给 adb。
     *
     * @return {@link #ACTIVATE_OK} / {@link #ACTIVATE_UI} / {@link #ACTIVATE_FAILED}
     */
    public static int activate(Context c) {
        if (isAdminActive(c)) {
            return ACTIVATE_OK;
        }
        if (silentActivate(c)) {
            return ACTIVATE_OK;
        }
        if (launchAddAdmin(c)) {
            return ACTIVATE_UI;
        }
        return ACTIVATE_FAILED;
    }

    /** 先试隐式 Intent，再逐个试显式组件；成功拉起返回 true */
    private static boolean launchAddAdmin(Context c) {
        try {
            c.startActivity(activateIntent(c));
            return true;
        } catch (Throwable e) {
            android.util.Log.w("DeviceGuard", "ACTION_ADD_DEVICE_ADMIN failed: " + e, e);
        }
        for (String flat : ADMIN_ADD_ACTIVITIES) {
            ComponentName cn = ComponentName.unflattenFromString(flat);
            if (cn == null) {
                continue;
            }
            try {
                Intent i = new Intent();
                i.setComponent(cn);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(c));
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, EXPLAIN);
                if (!(c instanceof android.app.Activity)) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                c.startActivity(i);
                return true;
            } catch (Throwable e) {
                android.util.Log.w("DeviceGuard", "launch " + flat + " failed: " + e, e);
            }
        }
        return false;
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

    /**
     * 只要「基础保护」时用的命令：等价于点一次激活，但不需要电视上有确认页，
     * 也不会要求电视上没有登录账号（set-device-owner 才要求）。
     */
    public static String adbActiveAdminCommand(Context c) {
        return "adb shell dpm set-active-admin "
                + c.getPackageName() + "/" + StudyDeviceAdmin.class.getName();
    }

    /** 激活失败时给用户的说明：不绕圈子，直接给能跑的两条命令 */
    public static String activateHelp(Context c) {
        return "这台电视的系统里没有「设备管理员」确认页，点按钮也拉不起来，"
                + "这是电视厂商裁掉了系统设置页，不是应用的问题。\n\n"
                + "用电脑连一次电视（USB 或无线调试），执行下面任意一条，10 秒搞定：\n\n"
                + "【基础保护｜不要求电视上没账号】\n"
                + adbActiveAdminCommand(c) + "\n\n"
                + "【彻底锁定系统卸载按钮｜要求电视上没有登录任何账号】\n"
                + adbCommand(c) + "\n\n"
                + "命令里的包名和类名已经填好，直接复制粘贴即可。"
                + "执行完回到本页，状态会自动变成「已激活」。\n\n"
                + "连接方法：设置 → 关于 → 连点「版本号」7 次 → 开发者选项 → 打开 USB 调试 / 无线调试；"
                + "电视和电脑连同一个 WiFi，电脑执行 adb connect 电视IP:5555"
                + "（Android 11+ 用无线调试里的配对码先 adb pair）。";
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
        return "要把系统里的「卸载」按钮彻底禁用，必须用电脑执行一次下面的命令，之后永久有效：\n\n"
                + adbCommand(c) + "\n\n"
                + "步骤：\n"
                + "① 电视：设置 → 关于 → 连点「版本号」7 次，打开开发者选项，再打开 USB 调试\n"
                + "② 电视与电脑连同一个 WiFi，电视上用「无线调试」看 IP 和端口（Android 11+ 需要配对码）\n"
                + "③ 电脑执行 adb connect 电视IP:端口（Android 11+ 先 adb pair 电视IP:配对端口）\n"
                + "④ 电脑执行上面的命令，看到 Success 即成功\n"
                + "⑤ 重新打开本应用，「设备所有者」会显示已生效\n\n"
                + "只想要基础保护、又不想恢复出厂（不要求电视上没登录账号）：\n"
                + adbActiveAdminCommand(c) + "\n"
                + "执行后卸载必须先停用设备管理员，孩子搞不定，但挡不住有心人。\n\n"
                + "关于电视上找不到「设备管理员」入口：\n"
                + "手机在 设置 → 安全 → 设备管理员；Android TV / Sony BRAVIA 普遍没有这一项，"
                + "是厂商裁掉了系统页面，不用去设置里翻。点本页「开启防卸载」时，"
                + "应用会先直接给自己激活（多数机型不弹任何确认页就成功）；"
                + "只有系统强制拦截时，才会让你用上面两条 adb 命令中的一条。\n\n"
                + "验证：adb shell dpm list-owners，能看到 DeviceOwner 就对了\n\n"
                + "常见失败：\n"
                + "· Not allowed to set the device owner because there are already some accounts"
                + " —— 电视上还登着账号，先到 设置 → 账户 全部删除再试\n"
                + "· ...because there are already several users on the device —— 先删掉访客/其他用户\n"
                + "· Unknown admin: ComponentInfo{...} —— 包名或类名写错，直接从本页复制命令\n"
                + "· already set / already a device owner —— 已经设置过，不用重复执行\n"
                + "· 命令成功但界面仍显示未生效 —— 退出本页再进来（状态在 onResume 刷新）";
    }
}
