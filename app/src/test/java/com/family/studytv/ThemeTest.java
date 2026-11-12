package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

/** 护眼配色：色板完整性、归一化、偏好读写与「任意主题来回切」的文字色映射。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ThemeTest {

    @Before
    @After
    public void resetTheme() {
        Prefs.setTheme(Theme.DEFAULT);
    }

    @Test
    public void normalizeKeepsKnownKeysAndRejectsOthers() {
        for (String k : Theme.KEYS) {
            assertEquals(k, Theme.normalize(k));
        }
        assertEquals(Theme.DEFAULT, Theme.normalize(null));
        assertEquals(Theme.DEFAULT, Theme.normalize(""));
        assertEquals(Theme.DEFAULT, Theme.normalize("bogus"));
        assertEquals(Theme.DEFAULT, Theme.normalize("<script>alert(1)</script>"));
    }

    @Test
    public void catalogHasFiveDistinctSchemasWithPreviewColors() throws Exception {
        JSONArray arr = Theme.catalog();
        assertEquals(Theme.KEYS.length, arr.length());
        Set<String> keys = new HashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String key = o.getString("key");
            assertTrue("风格 key 不能重复", keys.add(key));
            assertEquals(key, Theme.normalize(key));
            assertTrue("名称不能为空", o.getString("name").length() > 0);
            assertTrue("说明不能为空", o.getString("tip").length() > 0);
            for (String field : new String[]{"bg", "card", "txt", "accent"}) {
                assertTrue(key + "." + field + " 应是 #RRGGBB",
                        o.getString(field).matches("#[0-9A-F]{6}"));
            }
        }
    }

    @Test
    public void palettesAreDistinctAndOpaque() {
        Set<Integer> bgs = new HashSet<Integer>();
        Set<Integer> texts = new HashSet<Integer>();
        for (String key : Theme.KEYS) {
            Theme.Palette p = Theme.of(key);
            assertTrue(key + " 底色不能重复", bgs.add(p.bg));
            assertTrue(key + " 文字色不能重复", texts.add(p.text));
            for (int c : new int[]{p.bg, p.surface, p.text, p.text2, p.muted,
                    p.accent, p.ok, p.bad, p.warn, p.badgeBg, p.badgeText}) {
                assertEquals(key + " 主体色必须不透明", 0xFF, c >>> 24);
            }
            assertTrue(key + " 底色与文字要有足够区分", p.bg != p.text);
            assertTrue(key + " 正确色与错误色不能一样", p.ok != p.bad);
        }
    }

    @Test
    public void prefsThemeRoundTripAndInvalidFallsBack() {
        Prefs.setTheme("green");
        assertEquals("green", Prefs.theme());
        Prefs.setTheme("night");
        assertEquals("night", Prefs.theme());
        Prefs.setTheme("bogus");
        assertEquals(Theme.DEFAULT, Prefs.theme());
        Prefs.setTheme(null);
        assertEquals(Theme.DEFAULT, Prefs.theme());
    }

    @Test
    public void optionBordersAreAlignedAcrossAllStates() {
        View v = new View(RuntimeEnvironment.getApplication());
        for (String key : Theme.KEYS) {
            assertAligned(key, Theme.optionBackground(v, Theme.of(key)));
        }
        Drawable xml = RuntimeEnvironment.getApplication().getDrawable(R.drawable.bg_option);
        assertTrue("XML 版选项背景也应是三态 selector", xml instanceof StateListDrawable);
        assertAligned("bg_option.xml", (StateListDrawable) xml);
    }

    /** 三态同边界、同内边距、都是单层卡片；一旦有人再套内缩光晕层，这里会失败 */
    private static void assertAligned(String label, StateListDrawable s) {
        assertEquals(label + " 应有聚焦/按下/默认三态", 3, s.getStateCount());
        Rect firstPad = null;
        for (int i = 0; i < s.getStateCount(); i++) {
            Drawable d = s.getStateDrawable(i);
            assertTrue(label + " 每态只能是单层圆角卡片，不能套光晕层",
                    d instanceof GradientDrawable);
            d.setBounds(0, 0, 300, 90);
            assertEquals(label + " 各态边界必须完全重合", new Rect(0, 0, 300, 90), d.getBounds());
            Rect pad = new Rect();
            d.getPadding(pad);
            if (firstPad == null) {
                firstPad = pad;
            } else {
                assertEquals(label + " 各态内边距必须一致，选中时文字不位移", firstPad, pad);
            }
        }
    }

    @Test
    public void textColorMappingSurvivesRepeatedThemeSwitches() {
        TextView t = new TextView(RuntimeEnvironment.getApplication());
        Theme.Palette dark = Theme.of(Theme.DEFAULT);
        Theme.Palette paper = Theme.of("paper");
        Theme.Palette night = Theme.of("night");
        t.setTextColor(dark.text);

        Theme.tintTextTree(t, paper);
        assertEquals("深色 → 羊皮纸", paper.text, t.getCurrentTextColor());
        Theme.tintTextTree(t, night);
        assertEquals("羊皮纸 → 夜间", night.text, t.getCurrentTextColor());
        Theme.tintTextTree(t, dark);
        assertEquals("夜间 → 切回默认也要还原", dark.text, t.getCurrentTextColor());

        t.setTextColor(0xFFFAFAFB);   // 布局里写死的题干白
        Theme.tintTextTree(t, paper);
        assertEquals(paper.text, t.getCurrentTextColor());
        t.setTextColor(0xFF123456);   // 未知色不该被误改
        Theme.tintTextTree(t, night);
        assertEquals(0xFF123456, t.getCurrentTextColor());
        assertFalse("夜间不是原来的近黑底", night.bg == dark.bg);
    }
}
