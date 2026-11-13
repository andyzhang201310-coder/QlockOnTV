package com.family.studytv;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 护眼配色（v1.7.25）：家长在后台「快捷操作 → 护眼配色」里选风格，
 * 家长后台网页（CSS 变量）与电视答题页（本类运行时着色）共用同一套色板。
 *
 * <p>浅色三套都刻意避开纯白与高饱和，用低饱和暖黄 / 豆绿 / 雾蓝降低长时间注视的刺激；
 * 「夜间墨黑」保留深色但压低亮度与对比度，适合关灯后使用。
 *
 * <p>色板是纯数据，不碰 SharedPreferences；读写与广播在 {@link Prefs} 里。
 */
public final class Theme {

    public static final String DEFAULT = "default";
    /** 保存风格后广播，正在显示的答题页收到即刻换色 */
    public static final String ACTION_CHANGED = "com.family.studytv.action.THEME_CHANGED";

    public static final String[] KEYS = {"default", "paper", "green", "blue", "night"};

    private static final int I_BG = 0, I_SURFACE = 1, I_SURFACE_FOCUS = 2, I_STROKE = 3,
            I_STROKE_FOCUS = 4, I_TEXT = 5, I_TEXT2 = 6, I_MUTED = 7, I_ACCENT = 8,
            I_PILL_BG = 9, I_TAG_BG = 10, I_FEEDBACK_BG = 11, I_BADGE_BG = 12,
            I_BADGE_TEXT = 13, I_OK = 14, I_BAD = 15, I_WARN = 16, I_STAR_ON = 17,
            I_STAR_OFF = 18, I_GLOW_A = 19, I_GLOW_B = 20, I_COUNT = 21;

    /** 每套 21 个 ARGB，顺序见上面的索引常量；所有颜色都带完整 Alpha。 */
    private static final int[][] TABLE = {
            // default：原有近黑底 + 暖橙光晕（保持旧观感，切换回来一模一样）
            {0xFF0B0B0E, 0xFF16161A, 0xFF2A2116, 0xFF26262C, 0xFFFFB020,
             0xFFF2F2F4, 0xFF9A9AA2, 0xFF6B6B74, 0xFFFFB020, 0xFF1A1A1F,
             0xFF332611, 0xFF121215, 0xFF2A2A30, 0xFFC9C9CF, 0xFF33D17A,
             0xFFF2545B, 0xFFFFC24A, 0xFFFFC24A, 0xFF6B6B74, 0x14FF7A1A, 0x0EFFB020},
            // paper：羊皮纸暖黄，低蓝光
            {0xFFF2EAD8, 0xFFFBF4E4, 0xFFEFE0BF, 0xFFDCCFB2, 0xFFB07A2A,
             0xFF3B3427, 0xFF6B5F49, 0xFF8A7C63, 0xFFA97428, 0xFFF7EDD9,
             0xFFF3E4C4, 0xFFF6ECD8, 0xFFE5D5B5, 0xFF4A3B24, 0xFF3E7A4F,
             0xFFB05048, 0xFF9A6B22, 0xFFC08A32, 0xFFA99A80, 0x14C08A32, 0x0ED9A44A},
            // green：豆沙绿，经典护眼绿
            {0xFFE4EFE2, 0xFFF4FAF1, 0xFFDCEBDA, 0xFFCADDC8, 0xFF4F8A5B,
             0xFF26382B, 0xFF5A6E5C, 0xFF849686, 0xFF4F8A5B, 0xFFEAF3E6,
             0xFFDDEBDA, 0xFFEDF5E9, 0xFFD3E5CF, 0xFF2B4A33, 0xFF3F7A50,
             0xFFB25550, 0xFF8A7433, 0xFF6B9A50, 0xFF9DAFA0, 0x146B9A50, 0x0E9BC47A},
            // blue：雾蓝灰，冷色低刺激
            {0xFFE6EDF2, 0xFFF5F9FC, 0xFFDDE8F0, 0xFFC8D6E0, 0xFF4A7A9A,
             0xFF263542, 0xFF5A6B78, 0xFF8495A2, 0xFF4A7A9A, 0xFFEAF1F6,
             0xFFDCE8F0, 0xFFEDF3F8, 0xFFD2E0EA, 0xFF2A4256, 0xFF397A70,
             0xFFB25560, 0xFF8A6A33, 0xFF5A8AA8, 0xFF9BAAB6, 0x145A8AA8, 0x0E7AA8C0},
            // night：夜间墨黑，低亮度低对比
            {0xFF10151A, 0xFF1A222A, 0xFF24313A, 0xFF2A3640, 0xFFC99A52,
             0xFFC7D1D8, 0xFF8A98A3, 0xFF66747E, 0xFFC99A52, 0xFF182128,
             0xFF2A2418, 0xFF161E25, 0xFF2A3640, 0xFFB8C4CC, 0xFF6FB58A,
             0xFFD08080, 0xFFD5B070, 0xFFD5B070, 0xFF66747E, 0x0CC99A52, 0x08C99A52},
    };

    private static final String[] NAMES = {
            "米绿（默认）", "羊皮纸", "豆沙绿", "雾蓝灰", "夜间墨黑"
    };
    private static final String[] TIPS = {
            "原有配色，浅米绿后台 + 深色电视页",
            "暖黄纸色，减少蓝光，长时间阅读不刺眼",
            "低饱和豆绿，最经典的护眼色",
            "冷调雾蓝灰，柔和冷静、对比不冲",
            "低亮度深色，关灯后看题不晃眼"
    };

    private Theme() {
    }

    /** 非法 / 未知值一律回落到默认，防止网页端传畸形值 */
    public static String normalize(String key) {
        if (key != null) {
            for (String k : KEYS) {
                if (k.equals(key)) {
                    return k;
                }
            }
        }
        return DEFAULT;
    }

    public static String name(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(normalize(key))) {
                return NAMES[i];
            }
        }
        return NAMES[0];
    }

    public static String tip(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(normalize(key))) {
                return TIPS[i];
            }
        }
        return TIPS[0];
    }

    /** 供 /api/state 下发，后台色块按这里的色值预览，色板只维护这一处 */
    public static JSONArray catalog() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < KEYS.length; i++) {
            int[] v = TABLE[i];
            JSONObject o = new JSONObject();
            try {
                o.put("key", KEYS[i]);
                o.put("name", NAMES[i]);
                o.put("tip", TIPS[i]);
                o.put("bg", hex(v[I_BG]));
                o.put("card", hex(v[I_SURFACE]));
                o.put("txt", hex(v[I_TEXT]));
                o.put("accent", hex(v[I_ACCENT]));
            } catch (Throwable ignored) {
            }
            arr.put(o);
        }
        return arr;
    }

    private static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /** 一套配色的只读快照 */
    public static final class Palette {
        public final int bg, surface, surfaceFocus, stroke, strokeFocus, text, text2, muted,
                accent, pillBg, tagBg, feedbackBg, badgeBg, badgeText, ok, bad, warn,
                starOn, starOff, glowA, glowB;
        private final int[] values;

        Palette(int[] v) {
            if (v.length != I_COUNT) {
                throw new IllegalArgumentException("色板字段数量不对");
            }
            values = v;
            bg = v[I_BG];
            surface = v[I_SURFACE];
            surfaceFocus = v[I_SURFACE_FOCUS];
            stroke = v[I_STROKE];
            strokeFocus = v[I_STROKE_FOCUS];
            text = v[I_TEXT];
            text2 = v[I_TEXT2];
            muted = v[I_MUTED];
            accent = v[I_ACCENT];
            pillBg = v[I_PILL_BG];
            tagBg = v[I_TAG_BG];
            feedbackBg = v[I_FEEDBACK_BG];
            badgeBg = v[I_BADGE_BG];
            badgeText = v[I_BADGE_TEXT];
            ok = v[I_OK];
            bad = v[I_BAD];
            warn = v[I_WARN];
            starOn = v[I_STAR_ON];
            starOff = v[I_STAR_OFF];
            glowA = v[I_GLOW_A];
            glowB = v[I_GLOW_B];
        }

        /** 按字段索引取值，给「任意主题 → 任意主题」的文字色映射用 */
        public int at(int index) {
            return values[index];
        }

        /** 判断反馈 / 选项文字当前是「正确」「错误」还是普通态 */
        public boolean isOk(int color) {
            return color == ok;
        }

        public boolean isBad(int color) {
            return color == bad;
        }
    }

    public static Palette of(String key) {
        String k = normalize(key);
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(k)) {
                return new Palette(TABLE[i]);
            }
        }
        return new Palette(TABLE[0]);
    }

    // ------------------------------------------------------------------ 运行时着色工具

    private static int dp(View v, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                v.getResources().getDisplayMetrics());
    }

    /** 圆角小卡片，用来替换 XML 里写死的 bg_pill / bg_tag / bg_feedback */
    public static GradientDrawable card(View v, int fill, int line, float radiusDp, int linePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(v, radiusDp));
        if (linePx > 0) {
            d.setStroke(linePx, line);
        }
        return d;
    }

    /**
     * 关掉控件自带的高度阴影（v1.8.3）。
     *
     * <p>Android 5+ 的 {@code Theme.DeviceDefault} Button 默认带 2dp elevation 与 stateListAnimator，
     * 深色底上几乎看不见，换成羊皮纸 / 豆沙绿 / 雾蓝灰这类浅色底后，圆角外就浮出一圈灰边，
     * 看着像「卡片没对齐」。选项底色本来就是纯色块，不需要投影。
     */
    public static void flatten(View v) {
        if (Build.VERSION.SDK_INT >= 21) {
            v.setElevation(0f);
            v.setStateListAnimator(null);
        }
    }

    /** 页面底色：纯色 + 两团极淡的径向光晕，等价于原来的 bg_glow.xml */
    public static Drawable background(View v, Palette p) {
        Drawable[] layers = new Drawable[3];
        layers[0] = new ColorDrawable(p.bg);
        layers[1] = glow(v, p.glowA, 760, 0.92f, 0.0f);
        layers[2] = glow(v, p.glowB, 640, 0.02f, 1.0f);
        return new LayerDrawable(layers);
    }

    private static GradientDrawable glow(View v, int start, float radiusDp, float cx, float cy) {
        int transparent = start & 0x00FFFFFF;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{start, transparent});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(v, radiusDp));
        g.setGradientCenter(cx, cy);
        return g;
    }

    /** 选项圆角：v1.8.2 起 18dp → 10dp，去掉描边后大圆角会显得「药丸化」，收小更利落 */
    private static final float OPTION_RADIUS_DP = 10f;

    /**
     * 选项三态背景：默认、聚焦（遥控器选中）、按下。
     *
     * <p>v1.8.2 起默认态**不画边框**，只留一层同色填充；只有被选中的项才高亮描边，
     * 每道题视觉上就一条框，不再四框齐飞。三态的圆角与边界仍完全一致，
     * 遥控器上下移动时框只换色、不跳位。
     */
    public static StateListDrawable optionBackground(View v, Palette p) {
        return optionBackground(v, p, "");
    }

    /**
     * 带角色的选项背景：揭示答案时正确项描绿、错选项描红（文字色之外再给一层框，
     * 隔一米看电视也能一眼扫到）。普通态 / 「变暗」态一律无边框。
     *
     * @param role "ok"、"bad"，其余值按普通选项处理
     */
    public static StateListDrawable optionBackground(View v, Palette p, String role) {
        StateListDrawable s = new StateListDrawable();
        int r = dp(v, OPTION_RADIUS_DP);
        int line = Math.max(1, dp(v, 2));

        int mark = 0;
        if ("ok".equals(role)) {
            mark = p.ok;
        } else if ("bad".equals(role)) {
            mark = p.bad;
        }
        // 揭示态自己带框，颜色语义优先于焦点色：否则正确项一拿到焦点就被橙框盖掉
        if (mark != 0) {
            s.addState(new int[]{android.R.attr.state_focused},
                    card(v, p.surfaceFocus, mark, r, line));
            s.addState(new int[]{android.R.attr.state_pressed},
                    card(v, p.surfaceFocus, mark, r, line));
            s.addState(new int[]{}, card(v, p.surface, mark, r, line));
            return s;
        }

        s.addState(new int[]{android.R.attr.state_focused},
                card(v, p.surfaceFocus, p.strokeFocus, r, line));
        s.addState(new int[]{android.R.attr.state_pressed},
                card(v, p.surfaceFocus, p.accent, r, line));
        s.addState(new int[]{}, card(v, p.surface, 0, r, 0));
        return s;
    }

    /**
     * 把布局里按旧深色写的文字色映射到当前配色。只认已知的原始色值，
     * 运行时动态设的反馈色 / 星级色由 QuizActivity 自己按角色重设。
     */
    public static void tintTextTree(View root, Palette p) {
        if (root instanceof TextView) {
            TextView t = (TextView) root;
            int c = t.getCurrentTextColor();
            int next = mapped(c, p);
            if (next != 0) {
                t.setTextColor(next);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                tintTextTree(g.getChildAt(i), p);
            }
        }
    }

    private static int mapped(int c, Palette p) {
        // 先按「这个颜色属于色板的哪个字段」映射，任意主题之间来回切都不会失配
        for (int[] v : TABLE) {
            for (int i = 0; i < I_COUNT; i++) {
                if (v[i] == c) {
                    return p.at(i);
                }
            }
        }
        // 布局里少数没进色板的旧硬编码（题干白、斜杠灰等）
        if (c == 0xFFFAFAFB) {
            return p.text;
        }
        if (c == 0xFF4A4A52) {
            return p.text2;
        }
        return 0;   // 0 = 不动
    }
}
