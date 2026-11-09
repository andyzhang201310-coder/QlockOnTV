package com.family.studytv;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * 卸载码的纯逻辑实现（不依赖 Android，便于单元测试）。
 *
 * <p>本地只保存 SHA-256 摘要，不保存明文；连续输错按次数递增冷却时间，
 * 避免 6 位数字被逐个试出来。
 */
public final class UninstallCode {

    /** 出厂默认卸载码，与管理密码（1234）刻意不同 */
    public static final String DEFAULT_CODE = "246810";

    private static final String SALT = "studytv.uninstall.v1::";
    private static final String HEX = "0123456789abcdef";

    private UninstallCode() {
    }

    /** 4~12 位，只允许数字和字母 */
    public static boolean isAcceptable(String code) {
        if (code == null) {
            return false;
        }
        String c = code.trim();
        if (c.length() < 4 || c.length() > 12) {
            return false;
        }
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            boolean digit = ch >= '0' && ch <= '9';
            boolean lower = ch >= 'a' && ch <= 'z';
            boolean upper = ch >= 'A' && ch <= 'Z';
            if (!digit && !lower && !upper) {
                return false;
            }
        }
        return true;
    }

    public static String hash(String code) {
        if (code == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SALT + code.trim()).getBytes("UTF-8"));
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                out[i * 2] = HEX.charAt(v >>> 4);
                out[i * 2 + 1] = HEX.charAt(v & 0x0F);
            }
            return new String(out);
        } catch (Throwable e) {
            return "";
        }
    }

    /** 定长比较，避免按字符提前返回带来的时间差 */
    public static boolean matches(String code, String storedHash) {
        if (code == null || storedHash == null || storedHash.length() == 0) {
            return false;
        }
        String actual = hash(code);
        if (actual.length() != storedHash.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < actual.length(); i++) {
            diff |= actual.charAt(i) ^ storedHash.charAt(i);
        }
        return diff == 0;
    }

    /** 累计失败次数对应的冷却时长（毫秒），返回 0 表示不冷却 */
    public static long lockMillis(int failCount) {
        if (failCount >= 12) {
            return 30L * 60_000L;
        }
        if (failCount >= 8) {
            return 5L * 60_000L;
        }
        if (failCount >= 5) {
            return 60_000L;
        }
        return 0L;
    }

    /** 供界面显示的冷却剩余描述 */
    public static String describeLock(long millis) {
        long sec = (millis + 999) / 1000;
        if (sec <= 0) {
            return "";
        }
        if (sec < 60) {
            return sec + " 秒";
        }
        return String.format(Locale.CHINA, "%d 分 %d 秒", sec / 60, sec % 60);
    }
}
