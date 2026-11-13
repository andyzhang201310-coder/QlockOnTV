package com.family.studytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.LineHeightSpan;
import android.text.style.ReplacementSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;

import java.util.HashMap;
import java.util.Map;

/**
 * 极简富文本：把 AI 与题库里夹带的 LaTeX 片段渲染成可读的屏上文字，零第三方依赖。
 *
 * <p>为什么不用 WebView + KaTeX：一页公式要拖进整个 WebView（首屏 1–2 秒、几十 MB 内存），
 * 而电视端只需要小学到初中的那点符号（分数、根号、上下标、×÷≤≥）。所以只做这个子集，
 * 用 {@link ReplacementSpan} 把 {@code \frac} 画成真分数，其余走 Unicode 与字号位移。
 *
 * <p>关键约束：<b>没有公式时一个字符都不能改</b>。题库 24000 多道题全是纯文本
 * （乘除号是 Unicode ×÷），渲染器必须原样透传，否则会把现有观感改坏。
 */
public final class RichText {

    private RichText() {
    }

    public static boolean hasMarkup(CharSequence s) {
        if (s == null) {
            return false;
        }
        String t = s.toString();
        return t.indexOf('$') >= 0 || t.indexOf("\\(") >= 0
                || t.indexOf("\\[") >= 0 || t.indexOf("**") >= 0;
    }

    /**
     * 渲染入口：没有标记就原样返回同一个对象（零拷贝、零观感变化）。
     *
     * @param src    原文
     * @param textPx 当前字号（px），用来定公式里上下标与分数的相对大小
     */
    public static CharSequence render(CharSequence src, float textPx) {
        if (src == null) {
            return "";
        }
        if (!hasMarkup(src)) {
            return src;
        }
        String s = src.toString();
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        final int n = s.length();
        // AI 爱用 **加粗**：在 append 阶段就地吃掉标记，span 位置天然正确，
        // 也不会像「先 toString 再重排」那样把公式 span 弄丢。
        boolean bold = false;
        int boldStart = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                if (bold) {
                    bold = false;
                    if (out.length() > boldStart) {
                        out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                                boldStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else {
                    bold = true;
                    boldStart = out.length();
                }
                i += 2;
                continue;
            }
            int open = 0;
            int close = -1;
            if (c == '$') {
                if (i + 1 < n && s.charAt(i + 1) == '$') {
                    open = 2;
                    close = s.indexOf("$$", i + 2);
                } else {
                    open = 1;
                    close = s.indexOf('$', i + 1);
                }
            } else if (c == '\\' && i + 1 < n
                    && (s.charAt(i + 1) == '(' || s.charAt(i + 1) == '[')) {
                open = 2;
                close = s.indexOf(s.charAt(i + 1) == '(' ? "\\)" : "\\]", i + 2);
            }
            if (open == 0 || close < 0) {
                // 不是定界符，或没配对（AI 偶尔只给一个 $）→ 当普通字符
                out.append(c);
                i++;
                continue;
            }
            String expr = s.substring(i + open, close);
            out.append(math(expr, textPx));
            i = close + open;
        }
        liftLines(out, textPx);
        return out;
    }

    /**
     * 有分数的行必须更高：上下两行小字加一条横线，竖直方向要占掉约 1.5 倍行高，
     * 不抬就会把分母压到下一行文字上（实测 20sp 时压得很明显）。
     */
    private static void liftLines(SpannableStringBuilder out, float textPx) {
        if (out.getSpans(0, out.length(), FracSpan.class).length == 0) {
            return;
        }
        final int want = (int) (textPx * 2.0f);
        out.setSpan(new LineHeightSpan() {
            @Override
            public void chooseHeight(CharSequence text, int start, int end,
                                     int spanstartv, int lineHeight, Paint.FontMetricsInt fm) {
                int have = fm.descent - fm.ascent;
                if (have >= want) {
                    return;
                }
                int extra = want - have;
                fm.ascent -= extra / 2;
                fm.descent += extra - extra / 2;
                if (fm.top > fm.ascent) {
                    fm.top = fm.ascent;
                }
                if (fm.bottom < fm.descent) {
                    fm.bottom = fm.descent;
                }
            }
        }, 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ------------------------------------------------------------------ 数学片段

    private static CharSequence math(String expr, float textPx) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        int i = 0;
        final int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '\\') {
                int[] pos = {i + 1};
                String cmd = readName(expr, pos);
                i = pos[0];
                i = command(b, expr, i, cmd.length() == 0 ? "" : cmd, textPx);
                continue;
            }
            if (c == '^' || c == '_') {
                int[] pos = {i + 1};
                String body = readUnit(expr, pos);
                i = pos[0];
                int start = b.length();
                b.append(math(body, textPx * 0.72f));
                int end = b.length();
                if (end > start) {
                    if (c == '^') {
                        b.setSpan(new SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        b.setSpan(new SubscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    b.setSpan(new RelativeSizeSpan(0.78f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }
            if (c == '{') {
                int[] pos = {i + 1};
                String body = readGroup(expr, pos);
                i = pos[0];
                b.append(math(body, textPx));   // 裸花括号在 LaTeX 里只起分组作用，不显示
                continue;
            }
            if (c == '}' || c == '&') {
                i++;                             // 分组收尾 / 对齐符，屏上不显示
                continue;
            }
            if (c == '~') {
                b.append('\u00A0');
                i++;
                continue;
            }
            b.append(c);
            i++;
        }
        return b;
    }

    /** 处理一个反斜杠命令，返回新的扫描位置 */
    private static int command(SpannableStringBuilder b, String src, int i, String cmd, float textPx) {
        if (cmd.length() == 0) {
            // \{ \} \% \$ \_ 这类单字符转义
            if (i < src.length()) {
                b.append(src.charAt(i));
                return i + 1;
            }
            return i;
        }
        if ("frac".equals(cmd) || "dfrac".equals(cmd) || "tfrac".equals(cmd)) {
            int[] pos = {i};
            String num = readGroup(src, pos);
            String den = readGroup(src, pos);
            int start = b.length();
            b.append('\uFFFC');                  // 占位符，下面用 ReplacementSpan 盖住
            b.setSpan(new FracSpan(plain(num), plain(den)), start, b.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return pos[0];
        }
        if ("sqrt".equals(cmd)) {
            int[] pos = {i};
            String index = null;
            if (pos[0] < src.length() && src.charAt(pos[0]) == '[') {
                int end = src.indexOf(']', pos[0]);
                if (end > pos[0]) {
                    index = src.substring(pos[0] + 1, end);
                    pos[0] = end + 1;
                }
            }
            String body = readGroup(src, pos);
            if (index != null && index.length() > 0) {
                b.append(math(index, textPx * 0.62f));
            }
            b.append('√');
            // 单项不用括号（√2），多项加括号避免歧义（√(x+1)）
            boolean simple = body.length() <= 1 || body.matches("[A-Za-z0-9]+");
            if (!simple) {
                b.append('(').append(math(body, textPx)).append(')');
            } else {
                b.append(math(body, textPx));
            }
            return pos[0];
        }
        if ("text".equals(cmd) || "mathrm".equals(cmd) || "textrm".equals(cmd)
                || "operatorname".equals(cmd) || "mathbf".equals(cmd) || "textbf".equals(cmd)) {
            int[] pos = {i};
            String body = readGroup(src, pos);
            int start = b.length();
            b.append(body);
            if ("mathbf".equals(cmd) || "textbf".equals(cmd)) {
                b.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return pos[0];
        }
        if ("left".equals(cmd) || "right".equals(cmd) || "big".equals(cmd)
                || "Big".equals(cmd) || "displaystyle".equals(cmd) || "limits".equals(cmd)) {
            return i;                            // 只影响排版，屏上忽略
        }
        if ("quad".equals(cmd) || "qquad".equals(cmd)) {
            b.append('\u3000');
            return i;
        }
        if (",".equals(cmd) || ";".equals(cmd) || ":".equals(cmd) || " ".equals(cmd)) {
            b.append(' ');
            return i;
        }
        if ("!".equals(cmd)) {
            return i;
        }
        String sym = SYMBOLS.get(cmd);
        if (sym != null) {
            b.append(sym);
            return i;
        }
        // 未知命令：去掉反斜杠保留名字，比直接丢掉整段更适合排查
        b.append(cmd);
        return i;
    }

    /** 读 `{...}`（支持嵌套），没有花括号就取单个字符 */
    static String readGroup(String s, int[] pos) {
        int i = pos[0];
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        if (i >= s.length()) {
            pos[0] = i;
            return "";
        }
        if (s.charAt(i) != '{') {
            pos[0] = i + 1;
            return String.valueOf(s.charAt(i));
        }
        int depth = 0;
        int start = i + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    pos[0] = i + 1;
                    return s.substring(start, i);
                }
            } else if (c == '\\') {
                i++;                             // 跳过转义，避免 \} 提前收尾
            }
            i++;
        }
        pos[0] = s.length();
        return s.substring(Math.min(start, s.length()));
    }

    /** 上下标后的「一个单元」：{...} 或单字符 */
    private static String readUnit(String s, int[] pos) {
        int i = pos[0];
        if (i < s.length() && s.charAt(i) == '{') {
            return readGroup(s, pos);
        }
        if (i < s.length() && s.charAt(i) == '\\') {
            int[] p = {i + 1};
            String cmd = readName(s, p);
            pos[0] = p[0];
            String sym = SYMBOLS.get(cmd);
            return sym == null ? cmd : sym;
        }
        if (i < s.length()) {
            pos[0] = i + 1;
            return String.valueOf(s.charAt(i));
        }
        return "";
    }

    /** 读 `\` 后面的命令名：字母串（\times）或单字符（\,） */
    private static String readName(String s, int[] pos) {
        int i = pos[0];
        if (i >= s.length()) {
            return "";
        }
        char c = s.charAt(i);
        if (Character.isLetter(c)) {
            int start = i;
            while (i < s.length() && Character.isLetter(s.charAt(i))) {
                i++;
            }
            pos[0] = i;
            return s.substring(start, i);
        }
        pos[0] = i + 1;
        return String.valueOf(c);
    }

    private static final Map<String, String> SYMBOLS = new HashMap<String, String>();

    static {
        // 运算符
        SYMBOLS.put("times", "×");
        SYMBOLS.put("div", "÷");
        SYMBOLS.put("pm", "±");
        SYMBOLS.put("mp", "∓");
        SYMBOLS.put("cdot", "·");
        SYMBOLS.put("ast", "∗");
        SYMBOLS.put("star", "⋆");
        SYMBOLS.put("circ", "∘");
        SYMBOLS.put("bullet", "•");
        SYMBOLS.put("sum", "∑");
        SYMBOLS.put("prod", "∏");
        SYMBOLS.put("int", "∫");
        SYMBOLS.put("infty", "∞");
        SYMBOLS.put("partial", "∂");
        SYMBOLS.put("nabla", "∇");
        // 关系
        SYMBOLS.put("le", "≤");
        SYMBOLS.put("leq", "≤");
        SYMBOLS.put("ge", "≥");
        SYMBOLS.put("geq", "≥");
        SYMBOLS.put("ne", "≠");
        SYMBOLS.put("neq", "≠");
        SYMBOLS.put("approx", "≈");
        SYMBOLS.put("equiv", "≡");
        SYMBOLS.put("sim", "∼");
        SYMBOLS.put("propto", "∝");
        SYMBOLS.put("ll", "≪");
        SYMBOLS.put("gg", "≫");
        // 几何
        SYMBOLS.put("angle", "∠");
        SYMBOLS.put("triangle", "△");
        SYMBOLS.put("perp", "⊥");
        SYMBOLS.put("parallel", "∥");
        SYMBOLS.put("degree", "°");
        SYMBOLS.put("deg", "°");
        SYMBOLS.put("odot", "⊙");
        SYMBOLS.put("square", "□");
        SYMBOLS.put("because", "∵");
        SYMBOLS.put("therefore", "∴");
        // 箭头
        SYMBOLS.put("to", "→");
        SYMBOLS.put("rightarrow", "→");
        SYMBOLS.put("leftarrow", "←");
        SYMBOLS.put("Rightarrow", "⇒");
        SYMBOLS.put("Leftrightarrow", "⇔");
        // 希腊字母（小学到初中够用）
        SYMBOLS.put("alpha", "α");
        SYMBOLS.put("beta", "β");
        SYMBOLS.put("gamma", "γ");
        SYMBOLS.put("delta", "δ");
        SYMBOLS.put("Delta", "Δ");
        SYMBOLS.put("epsilon", "ε");
        SYMBOLS.put("theta", "θ");
        SYMBOLS.put("lambda", "λ");
        SYMBOLS.put("mu", "μ");
        SYMBOLS.put("pi", "π");
        SYMBOLS.put("rho", "ρ");
        SYMBOLS.put("sigma", "σ");
        SYMBOLS.put("Sigma", "Σ");
        SYMBOLS.put("tau", "τ");
        SYMBOLS.put("phi", "φ");
        SYMBOLS.put("omega", "ω");
        SYMBOLS.put("Omega", "Ω");
        // 省略号与括号
        SYMBOLS.put("cdots", "⋯");
        SYMBOLS.put("ldots", "…");
        SYMBOLS.put("dots", "…");
        SYMBOLS.put("varnothing", "∅");
        SYMBOLS.put("emptyset", "∅");
        SYMBOLS.put("in", "∈");
        SYMBOLS.put("notin", "∉");
        SYMBOLS.put("subset", "⊂");
        SYMBOLS.put("cup", "∪");
        SYMBOLS.put("cap", "∩");
        SYMBOLS.put("%", "%");
        SYMBOLS.put("$", "$");
        SYMBOLS.put("_", "_");
        SYMBOLS.put("{", "{");
        SYMBOLS.put("}", "}");
    }

    /**
     * 把一段 LaTeX 展开成纯文本：符号替换、去分组、嵌套分数写成 a/b。
     *
     * <p>只给分数的上下两行用 —— 那里是 {@code Canvas.drawText} 直接画字符串，
     * 应用不了 span，所以上下标只能降级成紧邻文字（小学题目里几乎不出现）。
     */
    static String plain(String expr) {
        if (expr == null || expr.length() == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '\\') {
                int[] pos = {i + 1};
                String cmd = readName(expr, pos);
                i = pos[0];
                if (cmd.length() == 0) {
                    if (i < expr.length()) {
                        b.append(expr.charAt(i));
                        i++;
                    }
                    continue;
                }
                if ("frac".equals(cmd) || "dfrac".equals(cmd) || "tfrac".equals(cmd)) {
                    String a = readGroup(expr, pos);
                    String b2 = readGroup(expr, pos);
                    b.append(plain(a)).append('/').append(plain(b2));
                    i = pos[0];
                    continue;
                }
                if ("sqrt".equals(cmd)) {
                    int[] p2 = {i};
                    if (p2[0] < expr.length() && expr.charAt(p2[0]) == '[') {
                        int end = expr.indexOf(']', p2[0]);
                        if (end > p2[0]) {
                            p2[0] = end + 1;
                        }
                    }
                    String body = readGroup(expr, p2);
                    b.append('√').append(plain(body));
                    i = p2[0];
                    continue;
                }
                if ("text".equals(cmd) || "mathrm".equals(cmd) || "textrm".equals(cmd)
                        || "operatorname".equals(cmd) || "mathbf".equals(cmd)
                        || "textbf".equals(cmd)) {
                    int[] p3 = {i};
                    b.append(readGroup(expr, p3));
                    i = p3[0];
                    continue;
                }
                if ("quad".equals(cmd) || "qquad".equals(cmd)) {
                    b.append(' ');
                    continue;
                }
                String sym = SYMBOLS.get(cmd);
                b.append(sym != null ? sym : cmd);
                continue;
            }
            if (c == '{') {
                int[] pos = {i + 1};
                b.append(plain(readGroup(expr, pos)));
                i = pos[0];
                continue;
            }
            if (c == '}' || c == '&') {
                i++;
                continue;
            }
            if (c == '^' || c == '_') {
                int[] pos = {i + 1};
                b.append(plain(readUnit(expr, pos)));
                i = pos[0];
                continue;
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 分数

    /**
     * 真分数：上下两行小字 + 一条横线。
     *
     * <p>宽度按两行里较宽的一行算；字号全部相对 {@code paint.getTextSize()} 取比例，
     * 所以家长把学习页字号调到 200% 时分数跟着一起变大，不用重新构造 span。
     */
    private static final class FracSpan extends ReplacementSpan {
        private final CharSequence num;
        private final CharSequence den;
        private int width = -1;

        FracSpan(String num, String den) {
            this.num = num == null ? "" : num;
            this.den = den == null ? "" : den;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            float old = paint.getTextSize();
            paint.setTextSize(old * RATIO);
            int w = (int) Math.max(paint.measureText(num, 0, num.length()),
                    paint.measureText(den, 0, den.length()));
            paint.setTextSize(old);
            width = Math.max(w + pad(paint), (int) old);
            return width;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            float old = paint.getTextSize();
            float small = old * RATIO;
            paint.setTextSize(small);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float h = fm.descent - fm.ascent;
            float cx = x + (width < 0 ? 0 : width) / 2f;
            // 分数线对齐原文字的中线，分子坐在线上、分母挂在线下
            float lineY = y - h * 0.30f;
            float numBase = lineY - h * 0.26f;
            float denBase = lineY + h * 0.94f;
            centered(canvas, num, cx, numBase, paint);
            centered(canvas, den, cx, denBase, paint);
            paint.setStrokeWidth(Math.max(1.5f, old * 0.062f));
            canvas.drawLine(x + pad(paint) * 0.5f, lineY,
                    x + width - pad(paint) * 0.5f, lineY, paint);
            paint.setTextSize(old);
        }

        private static void centered(Canvas c, CharSequence s, float cx, float base, Paint p) {
            if (s.length() == 0) {
                return;
            }
            c.drawText(s, 0, s.length(), cx - p.measureText(s, 0, s.length()) / 2f, base, p);
        }

        private static int pad(Paint p) {
            return (int) Math.max(2f, p.getTextSize() * 0.12f);
        }

        private static final float RATIO = 0.74f;
    }
}
