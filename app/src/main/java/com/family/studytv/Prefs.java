package com.family.studytv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 所有可配置项集中在这里，网页后台 / 电视界面 / 服务共用一套读写。
 */
public final class Prefs {

    private static final String FILE = "studytv";

    private Prefs() {
    }

    private static SharedPreferences sp() {
        return App.get().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return sp().getBoolean("enabled", true);
    }

    public static void setEnabled(boolean v) {
        sp().edit().putBoolean("enabled", v).apply();
    }

    /** 弹题间隔（分钟），默认 30 */
    public static int intervalMinutes() {
        return Math.max(1, sp().getInt("intervalMin", 30));
    }

    public static void setIntervalMinutes(int v) {
        sp().edit().putInt("intervalMin", Math.max(1, Math.min(720, v))).apply();
    }

    // ------------------------------------------------------------ 自动弹窗 / 免打扰

    /**
     * 自动弹题总开关，默认开启。
     * 关掉后永久不再自动弹（重启、开机、到点轮询都不弹），只能手动「立即弹一次题」，
     * 用来在假期/考试周一次性停掉，不用卸载。
     */
    public static boolean autoQuiz() {
        return sp().getBoolean("autoQuiz", true);
    }

    public static void setAutoQuiz(boolean v) {
        sp().edit().putBoolean("autoQuiz", v).apply();
    }

    /** 免打扰时段开关，默认关闭 */
    public static boolean quietEnabled() {
        return sp().getBoolean("quietEnabled", false);
    }

    public static void setQuietEnabled(boolean v) {
        sp().edit().putBoolean("quietEnabled", v).apply();
    }

    /** 免打扰开始时间（一天中的分钟数），默认 22:00 */
    public static int quietStartMin() {
        return clampMinute(sp().getInt("quietStartMin", 22 * 60));
    }

    /** 免打扰结束时间（一天中的分钟数），默认 07:00 */
    public static int quietEndMin() {
        return clampMinute(sp().getInt("quietEndMin", 7 * 60));
    }

    public static void setQuietRange(int startMin, int endMin) {
        sp().edit()
                .putInt("quietStartMin", clampMinute(startMin))
                .putInt("quietEndMin", clampMinute(endMin))
                .apply();
    }

    private static int clampMinute(int v) {
        return Math.max(0, Math.min(QuietHours.MINUTES_PER_DAY - 1, v));
    }

    /** 当前是否处于免打扰时段 */
    public static boolean inQuietNow() {
        return quietEnabled() && QuietHours.inQuiet(System.currentTimeMillis(), quietStartMin(), quietEndMin());
    }

    /** 把时刻顺延到最近的允许弹窗时间（免打扰期间顺延到时段结束） */
    public static long nextAllowedAt(long ts) {
        if (!quietEnabled()) {
            return ts;
        }
        return QuietHours.nextAllowedAt(ts, quietStartMin(), quietEndMin());
    }

    /** 每次弹窗需要连续答对的题目数量，默认 5 */
    public static int quizCount() {
        return Math.max(1, Math.min(20, sp().getInt("quizCount", 5)));
    }

    public static void setQuizCount(int v) {
        sp().edit().putInt("quizCount", Math.max(1, Math.min(20, v))).apply();
    }

    /** 管理密码，默认 1234 */
    public static String pin() {
        String p = sp().getString("pin", "1234");
        return (p == null || p.length() < 4) ? "1234" : p;
    }

    public static void setPin(String v) {
        // 换密码顺手清掉输错计数，避免改完还被旧冷却挡着
        sp().edit().putString("pin", v)
                .putInt("pinFail", 0)
                .putLong("pinLockUntil", 0L)
                .apply();
    }

    /**
     * 电视端「管理」入口校验管理密码，带输错冷却。
     * 管理页的下发口令是会话 Cookie，走的是另一套，不受此影响。
     */
    public static boolean verifyPin(String in) {
        if (pinLockRemainMs() > 0) {
            return false;
        }
        if (in != null && in.trim().equals(pin())) {
            sp().edit().putInt("pinFail", 0).putLong("pinLockUntil", 0L).apply();
            return true;
        }
        int n = sp().getInt("pinFail", 0) + 1;
        long lock = UninstallCode.lockMillis(n);
        SharedPreferences.Editor e = sp().edit().putInt("pinFail", n);
        if (lock > 0) {
            e.putLong("pinLockUntil", System.currentTimeMillis() + lock);
        }
        e.apply();
        return false;
    }

    public static int pinFailCount() {
        return sp().getInt("pinFail", 0);
    }

    public static long pinLockRemainMs() {
        return Math.max(0L, sp().getLong("pinLockUntil", 0L) - System.currentTimeMillis());
    }

    /** 首次安装是否已经给家长看过一次二维码说明页 */
    public static boolean firstRunDone() {
        return sp().getBoolean("firstRunDone", false);
    }

    public static void setFirstRunDone(boolean v) {
        sp().edit().putBoolean("firstRunDone", v).apply();
    }

    /** 局域网后台端口，默认 8899 */
    public static int port() {
        return sp().getInt("port", 8899);
    }

    public static void setPort(int v) {
        sp().edit().putInt("port", v).apply();
    }

    /** 开机后是否立刻弹一次题（用来防止小孩靠关机逃避） */
    public static boolean bootQuiz() {
        return sp().getBoolean("bootQuiz", true);
    }

    public static void setBootQuiz(boolean v) {
        sp().edit().putBoolean("bootQuiz", v).apply();
    }

    /** 是否还有未完成的答题（关机/被杀后会保留） */
    public static boolean isPending() {
        return sp().getBoolean("pending", false);
    }

    public static void setPending(boolean v) {
        sp().edit().putBoolean("pending", v).apply();
    }

    public static long dueAt() {
        return sp().getLong("dueAt", 0L);
    }

    public static void setDueAt(long v) {
        sp().edit().putLong("dueAt", v).putBoolean("duePushed", false).apply();
    }

    /**
     * 设置下次弹题时间；如果落在免打扰时段内，自动顺延到时段结束并打标记。
     *
     * <p>打标记是为了让闹钟改用「不唤醒设备」的类型：免打扰结束那一刻通常是清晨，
     * 用 RTC_WAKEUP 会把待机中的电视点亮，正是这次要解决的问题。
     */
    public static void setDueAtAligned(long raw) {
        long aligned = nextAllowedAt(raw);
        sp().edit()
                .putLong("dueAt", aligned)
                .putBoolean("duePushed", aligned != raw)
                .apply();
    }

    /**
     * 规则版顺延：由 Rules 统一算出下一个允许弹题的时刻（含周计划 / 网课 / 豁免），
     * 这里只负责落盘并保留「被顺延过」标记，闹钟据此降级为非唤醒类型。
     */
    public static void setDueAtAlignedTo(long raw, long aligned) {
        sp().edit()
                .putLong("dueAt", aligned)
                .putBoolean("duePushed", aligned != raw)
                .apply();
    }

    /** 当前 dueAt 是否因为免打扰被顺延过 */
    public static boolean duePushed() {
        return sp().getBoolean("duePushed", false);
    }

    public static long lastPassAt() {
        return sp().getLong("lastPassAt", 0L);
    }

    public static void setLastPassAt(long v) {
        sp().edit().putLong("lastPassAt", v).apply();
    }

    /**
     * 最近一次「到点弹窗被系统拦下」的时间（Android 10+ 的后台启动限制）。
     * 用来在信息页提醒家长去开悬浮窗权限，0 表示最近一次是正常的。
     */
    public static long balBlockedAt() {
        return sp().getLong("balBlockedAt", 0L);
    }

    public static void setBalBlockedAt(long v) {
        sp().edit().putLong("balBlockedAt", v).apply();
    }

    /** 启用的学科，逗号分隔 */
    public static String subjects() {
        return sp().getString("subjects", "语文,数学,英语");
    }

    public static void setSubjects(String v) {
        sp().edit().putString("subjects", v).apply();
    }

    // ------------------------------------------------------------ 年级

    /** 小学全部年级 */
    public static final int GRADE_MIN = 1;
    public static final int GRADE_MAX = 6;

    /**
     * 出题的年级范围，逗号分隔（如 "3"、"1,2,3"、"1,2,3,4,5,6"）。
     * 默认全部年级；空值也按全部处理，避免误配导致出不了题。
     */
    public static String grades() {
        String g = sp().getString("grades", "1,2,3,4,5,6");
        if (g == null || g.trim().length() == 0) {
            return "1,2,3,4,5,6";
        }
        return g;
    }

    public static void setGrades(String v) {
        sp().edit().putString("grades", normalizeGrades(v)).apply();
    }

    /** 解析成 1–6 的集合；全部合法值都没了则视为全部年级 */
    public static java.util.Set<Integer> gradeSet() {
        return parseGrades(grades());
    }

    public static java.util.Set<Integer> parseGrades(String v) {
        java.util.Set<Integer> set = new java.util.LinkedHashSet<Integer>();
        if (v != null) {
            for (String s : v.split("[,，\\s]+")) {
                try {
                    int g = Integer.parseInt(s.trim());
                    if (g >= GRADE_MIN && g <= GRADE_MAX) {
                        set.add(g);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (set.isEmpty()) {
            for (int g = GRADE_MIN; g <= GRADE_MAX; g++) {
                set.add(g);
            }
        }
        return set;
    }

    /** 去重、排序、限量到 1–6，输出规范化字符串 */
    public static String normalizeGrades(String v) {
        java.util.Set<Integer> set = new java.util.LinkedHashSet<Integer>();
        if (v != null) {
            for (String s : v.split("[,，\\s]+")) {
                try {
                    int g = Integer.parseInt(s.trim());
                    if (g >= GRADE_MIN && g <= GRADE_MAX) {
                        set.add(g);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (set.isEmpty()) {
            for (int g = GRADE_MIN; g <= GRADE_MAX; g++) {
                set.add(g);
            }
        }
        java.util.List<Integer> sorted = new java.util.ArrayList<Integer>(set);
        java.util.Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (Integer g : sorted) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(g);
        }
        return sb.toString();
    }

    /** 是否全选 1–6 年级 */
    public static boolean allGrades() {
        return gradeSet().size() >= GRADE_MAX - GRADE_MIN + 1;
    }

    /** 题库是否被家长改过（改过就不再被内置题库覆盖） */
    public static boolean bankCustom() {
        return sp().getBoolean("bankCustom", false);
    }

    public static void setBankCustom(boolean v) {
        sp().edit().putBoolean("bankCustom", v).apply();
    }

    // ------------------------------------------------------------ 防卸载

    /** 是否开启卸载保护，默认开启 */
    public static boolean uninstallGuard() {
        return sp().getBoolean("uninstallGuard", true);
    }

    public static void setUninstallGuard(boolean v) {
        sp().edit().putBoolean("uninstallGuard", v).apply();
    }

    /** 设备所有者模式下是否同时禁止「恢复出厂设置」 */
    public static boolean blockFactoryReset() {
        return sp().getBoolean("blockFactoryReset", false);
    }

    public static void setBlockFactoryReset(boolean v) {
        sp().edit().putBoolean("blockFactoryReset", v).apply();
    }

    /** 卸载码摘要；若未设置过，用出厂默认码的摘要 */
    public static String uninstallHash() {
        String h = sp().getString("uninstallHash", null);
        if (h == null || h.length() == 0) {
            return UninstallCode.hash(UninstallCode.DEFAULT_CODE);
        }
        return h;
    }

    public static void setUninstallPin(String plain) {
        sp().edit()
                .putString("uninstallHash", UninstallCode.hash(plain.trim()))
                .putBoolean("uninstallPinCustom", true)
                .putInt("uninstallFail", 0)
                .putLong("uninstallLockUntil", 0L)
                .apply();
    }

    public static boolean hasCustomUninstallPin() {
        return sp().getBoolean("uninstallPinCustom", false);
    }

    public static int uninstallFailCount() {
        return sp().getInt("uninstallFail", 0);
    }

    public static void recordUninstallFail() {
        int n = uninstallFailCount() + 1;
        long lock = UninstallCode.lockMillis(n);
        SharedPreferences.Editor e = sp().edit().putInt("uninstallFail", n);
        if (lock > 0) {
            e.putLong("uninstallLockUntil", System.currentTimeMillis() + lock);
        }
        e.apply();
    }

    public static void resetUninstallFail() {
        sp().edit().putInt("uninstallFail", 0).putLong("uninstallLockUntil", 0L).apply();
    }

    public static long uninstallLockUntil() {
        return sp().getLong("uninstallLockUntil", 0L);
    }

    public static long uninstallLockRemainMs() {
        return Math.max(0L, uninstallLockUntil() - System.currentTimeMillis());
    }

    /**
     * 校验卸载码。冷却期内一律拒绝；失败会累计并触发冷却，成功则清零。
     */
    public static boolean verifyUninstallPin(String code) {
        if (uninstallLockRemainMs() > 0) {
            return false;
        }
        if (UninstallCode.matches(code, uninstallHash())) {
            resetUninstallFail();
            return true;
        }
        recordUninstallFail();
        return false;
    }

    /** 设备管理员被停用的时间点，用于主界面提醒 */
    public static long adminDisabledAt() {
        return sp().getLong("adminDisabledAt", 0L);
    }

    public static void setAdminDisabledAt(long v) {
        sp().edit().putLong("adminDisabledAt", v).apply();
    }
}
