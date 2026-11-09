package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 纯 JVM 用例：卸载码摘要与冷却策略 */
public class UninstallCodeTest {

    @Test
    public void hashIsStableAndNotPlainText() {
        String h = UninstallCode.hash("246810");
        assertEquals(64, h.length());
        assertEquals(h, UninstallCode.hash(" 246810 "));
        assertFalse("摘要里不能出现明文", h.contains("246810"));
        assertNotEquals(h, UninstallCode.hash("246811"));
    }

    @Test
    public void matchesOnlyExactCode() {
        String stored = UninstallCode.hash("839201");
        assertTrue(UninstallCode.matches("839201", stored));
        assertTrue(UninstallCode.matches("  839201  ", stored));
        assertFalse(UninstallCode.matches("839202", stored));
        assertFalse(UninstallCode.matches("83920", stored));
        assertFalse(UninstallCode.matches("", stored));
    }

    @Test
    public void matchesRejectsNullOrEmptyHash() {
        assertFalse(UninstallCode.matches("246810", null));
        assertFalse(UninstallCode.matches("246810", ""));
        assertFalse(UninstallCode.matches(null, UninstallCode.hash("246810")));
    }

    @Test
    public void acceptableCodeRules() {
        assertTrue(UninstallCode.isAcceptable("246810"));
        assertTrue(UninstallCode.isAcceptable("abcd"));
        assertTrue(UninstallCode.isAcceptable("Ab12Cd"));
        assertFalse("太短", UninstallCode.isAcceptable("123"));
        assertFalse("太长", UninstallCode.isAcceptable("1234567890123"));
        assertFalse("含符号", UninstallCode.isAcceptable("12-34"));
        assertFalse("含空格", UninstallCode.isAcceptable("12 34"));
        assertFalse("空值", UninstallCode.isAcceptable(null));
        assertFalse("空串", UninstallCode.isAcceptable("   "));
    }

    @Test
    public void lockEscalatesWithFailures() {
        assertEquals(0L, UninstallCode.lockMillis(0));
        assertEquals(0L, UninstallCode.lockMillis(4));
        assertEquals(60_000L, UninstallCode.lockMillis(5));
        assertEquals(60_000L, UninstallCode.lockMillis(7));
        assertEquals(5 * 60_000L, UninstallCode.lockMillis(8));
        assertEquals(30 * 60_000L, UninstallCode.lockMillis(12));
        assertEquals(30 * 60_000L, UninstallCode.lockMillis(50));
    }

    @Test
    public void describeLockIsHumanReadable() {
        assertEquals("", UninstallCode.describeLock(0));
        assertTrue(UninstallCode.describeLock(30_000L).contains("30 秒"));
        assertTrue(UninstallCode.describeLock(90_000L).contains("1 分"));
    }
}
