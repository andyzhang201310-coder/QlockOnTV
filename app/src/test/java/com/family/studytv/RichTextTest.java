package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * v1.9.1 公式渲染：LaTeX 片段转屏上文字。
 *
 * <p>最重要的一条是「纯文本必须原样透传」—— 题库 24704 道题里一条 LaTeX 都没有，
 * 渲染器一旦对普通文本动了一个字符，就是把现有 18000 道数学题的观感改坏。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RichTextTest {

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString();
    }

    private static boolean has(CharSequence c, Class<?> cls) {
        if (!(c instanceof Spanned)) {
            return false;
        }
        Spanned s = (Spanned) c;
        return s.getSpans(0, s.length(), cls).length > 0;
    }

    // ------------------------------------------------------------------ 透传

    @Test
    public void 纯文本原样返回同一个对象() {
        String plain = "12 × 4 = ?";
        assertFalse(RichText.hasMarkup(plain));
        assertSame("没有标记时必须返回原对象，零开销零改动", plain,
                RichText.render(plain, 40f));
    }

    @Test
    public void 题库常见形态不受影响() {
        String[] samples = {
                "「尖」的读音是第几声？",
                "【错别字辨析】「按步就班」这个词语里有错别字，正确写法是「按部就班」。",
                "12 × 4 = ?",
                "240 ÷ 6 = ?",
                "3/4 和 5/8 哪个大？",
                "45° 的余角是多少度？",
        };
        for (String s : samples) {
            assertEquals(s, str(RichText.render(s, 40f)));
        }
    }

    // ------------------------------------------------------------------ 定界符

    @Test
    public void 三种定界符都能识别() {
        assertEquals("x 的平方", str(RichText.render("$x$ 的平方", 40f)));
        assertEquals("x 的平方", str(RichText.render("\\(x\\) 的平方", 40f)));
        assertEquals("x 的平方", str(RichText.render("\\[x\\] 的平方", 40f)));
        assertEquals("x 的平方", str(RichText.render("$$x$$ 的平方", 40f)));
    }

    @Test
    public void 未配对的美元号当普通字符() {
        // 未配对的 $ 原样留着，不能吃掉，否则「5 美元」这种文本会被静默改掉
        assertEquals("价格 5$ 元", str(RichText.render("价格 5$ 元", 40f)));
        assertEquals("$", str(RichText.render("$", 40f)));
    }

    @Test
    public void 中英文与公式混排() {
        assertEquals("答案是 3 × 4 = 12，所以选 C。",
                str(RichText.render("答案是 $3 \\times 4 = 12$，所以选 C。", 40f)));
    }

    // ------------------------------------------------------------------ 符号与结构

    @Test
    public void 运算符换成Unicode() {
        assertEquals("a × b ÷ c", str(RichText.render("$a \\times b \\div c$", 40f)));
        assertEquals("x ≤ 5 且 x ≠ 3", str(RichText.render("$x \\le 5$ 且 $x \\ne 3$", 40f)));
        // 命令后的空格保留：运算符两侧留白比挤在一起好读
        assertEquals("∠ A = 60°", str(RichText.render("$\\angle A = 60\\degree$", 40f)));
        assertEquals("π ≈ 3.14", str(RichText.render("$\\pi \\approx 3.14$", 40f)));
    }

    @Test
    public void 上标下标变成Span() {
        CharSequence c = RichText.render("$x^2 + y_1$", 40f);
        assertEquals("x2 + y1", str(c));
        assertTrue("x^2 要有上标", has(c, SuperscriptSpan.class));
        assertTrue("y_1 要有下标", has(c, SubscriptSpan.class));
        assertTrue("上下标要比正文小一号", has(c, RelativeSizeSpan.class));
    }

    @Test
    public void 上标内容带花括号() {
        assertEquals("a10", str(RichText.render("$a^{10}$", 40f)));
    }

    @Test
    public void 分数画成真分数() {
        CharSequence c = RichText.render("$\\frac{1}{2}$", 40f);
        assertEquals("分数整体只占一个占位符", "\uFFFC", str(c));
        assertTrue("要有自绘分数 span", has(c, android.text.style.ReplacementSpan.class));
    }

    @Test
    public void 分数上下行会把命令展开() {
        assertEquals("1/2", RichText.plain("\\frac{1}{2}"));
        assertEquals("√2/2", RichText.plain("\\frac{\\sqrt{2}}{2}"));
        assertEquals("a/b", RichText.plain("\\frac{a}{b}"));
    }

    @Test
    public void 根号单项不加括号多项加() {
        assertEquals("√2", str(RichText.render("$\\sqrt{2}$", 40f)));
        assertEquals("√(x+1)", str(RichText.render("$\\sqrt{x+1}$", 40f)));
        assertEquals("3√8", str(RichText.render("$\\sqrt[3]{8}$", 40f)));
    }

    @Test
    public void text命令里的中文原样输出() {
        assertEquals("所以答案是 语文",
                str(RichText.render("$\\text{所以答案是} \\text{语文}$", 40f)));
    }

    @Test
    public void left_right只当括号() {
        assertEquals("( 1 + 2 )", str(RichText.render("$\\left( 1 + 2 \\right)$", 40f)));
    }

    @Test
    public void 裸花括号只起分组作用() {
        assertEquals("12", str(RichText.render("${1}{2}$", 40f)));
    }

    // ------------------------------------------------------------------ 降级

    @Test
    public void 未知命令保留名字不吞内容() {
        assertEquals("foo 1", str(RichText.render("$\\foo 1$", 40f)));
    }

    @Test
    public void 复杂环境不崩() {
        String[] nasty = {
                "$\\begin{matrix} a & b \\end{matrix}$",
                "$\\frac{1}$",           // 少一个参数
                "$\\sqrt$",              // 少内容
                "$\\frac{\\frac{1}{2}}{3}$",
                "$$",                    // 空公式
                "\\(\\",
        };
        for (String s : nasty) {
            String out = str(RichText.render(s, 40f));
            assertTrue("不能抛异常也不能返回 null：" + s, out != null);
        }
    }

    @Test
    public void 嵌套分数能算出可读文本() {
        assertEquals("1/2/3", RichText.plain("\\frac{\\frac{1}{2}}{3}"));
    }

    // ------------------------------------------------------------------ markdown

    @Test
    public void 双星号变粗体且去掉星号() {
        CharSequence c = RichText.render("**重点**：先算括号", 40f);
        assertEquals("重点：先算括号", str(c));
        assertTrue(has(c, android.text.style.StyleSpan.class));
        assertTrue("星号必须消失", str(c).indexOf("**") < 0);
    }

    @Test
    public void 单个星号保持原样() {
        assertEquals("3 * 4", str(RichText.render("3 * 4", 40f)));
    }

    @Test
    public void 粗体与公式可以同时存在() {
        CharSequence c = RichText.render("**先算** $\\frac{1}{2}$ **再约分**", 40f);
        assertEquals("先算 \uFFFC 再约分", str(c));
        assertTrue(has(c, android.text.style.StyleSpan.class));
        assertTrue(has(c, android.text.style.ReplacementSpan.class));
    }
}
