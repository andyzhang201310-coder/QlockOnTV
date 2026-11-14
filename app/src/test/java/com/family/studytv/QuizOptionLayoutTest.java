package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.text.Layout;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** 长成语解释不能被裁：按钮高度必须跟着文字走，页面还要能滚动。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QuizOptionLayoutTest {

    /** 题库里真实存在的最长成语解释 */
    private static final String LONG_IDIOM =
            "步古时以五尺为一步，步步表示距离短，军队每向前推进一步就设下一首营垒，形容防守严密，行动谨慎";

    private ActivityController<QuizActivity> controller;
    private QuizActivity activity;

    @Before
    public void setUp() {
        QuizActivity.running = false;
        Prefs.setQuizCount(1);
        Prefs.setSubjects("语文,数学,英语");
        QuestionBank.resetForTest();
        QuestionBank.get().ensureSeeded();
        controller = Robolectric.buildActivity(QuizActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        try {
            controller.pause().stop().destroy();
        } catch (Throwable ignored) {
        }
        QuizActivity.running = false;
    }

    /** 短答案两列时 optionBox 的直接子 View 是行容器，取按钮要递归。 */
    private java.util.List<Button> buttons() {
        java.util.List<Button> out = new java.util.ArrayList<Button>();
        collect(optionBox(), out);
        return out;
    }

    private static void collect(View v, java.util.List<Button> out) {
        if (v instanceof Button) {
            out.add((Button) v);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    private LinearLayout optionBox() {
        return (LinearLayout) activity.findViewById(R.id.optionBox);
    }

    @Test
    public void optionHeightIsWrapContentNotWeightedSplit() {
        java.util.List<Button> bs = buttons();
        assertTrue(bs.size() >= 2);
        for (Button b : bs) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            assertEquals("选项不能再被 0dp + weight 强制平分固定高度",
                    LinearLayout.LayoutParams.WRAP_CONTENT, lp.height);
            if (lp.weight > 0f) {
                assertEquals("两列布局的权重只能用于宽度等分，不能拿来分高度", 0, lp.width);
            }
            assertTrue("必须允许换行", b.getMaxLines() > 1);
            assertFalse("不能是单行变换", b.getTransformationMethod()
                    instanceof android.text.method.SingleLineTransformationMethod);
        }
    }

    @Test
    public void longAnswerRendersFullyAndKeepsHangingIndent() {
        Button b = buttons().get(0);
        CharSequence t = QuizActivity.optionText(b, 0, LONG_IDIOM, null);
        b.setText(t);

        assertTrue("完整的成语解释必须保留在按钮文本里", t.toString().contains(LONG_IDIOM));
        assertTrue("首行要有 A 标号", t.toString().startsWith("A"));
        assertTrue("选项文字应带 span", t instanceof Spanned);
        LeadingMarginSpan[] spans = ((Spanned) t).getSpans(0, t.length(), LeadingMarginSpan.class);
        assertTrue("换行后需悬挂对齐到标号之后", spans.length > 0);
    }

    /**
     * Robolectric 没有真实字体，TextView.onMeasure 算不出多行高度（长短都返回 minHeight），
     * 所以这里只校验「不再限制高度」的静态条件；真实行高在 MuMu 上用 uiautomator bounds 验证。
     */
    @Test
    public void longAnswerIsFreeToGrow() {
        java.util.List<Button> bs = buttons();
        Button shortOne = bs.get(0);
        Button longOne = bs.get(1);

        shortOne.setText(QuizActivity.optionText(shortOne, 0, "忍受暂时的屈辱", null));
        longOne.setText(QuizActivity.optionText(longOne, 1, LONG_IDIOM, "    ← 正确答案"));

        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT,
                ((LinearLayout.LayoutParams) longOne.getLayoutParams()).height);
        assertTrue("长答案文本必须完整", longOne.getText().toString().contains(LONG_IDIOM));
        assertTrue("长答案确实比短答案长得多",
                longOne.getText().length() > shortOne.getText().length() + 20);
        assertTrue("允许多行", longOne.getMaxLines() > 1);
        assertNull("不能省略尾部文字", longOne.getEllipsize());
        assertEquals("长短答案共享同一最小点击高度",
                shortOne.getMinHeight(), longOne.getMinHeight());
    }

    /**
     * v1.7.26：A/B/C/D 徽标必须按整个文本块垂直居中。ReplacementSpan 只在首行绘制，
     * 以前两行答案的徽标会贴在第一行；现在行数一变，中心跟着文本块高度走。
     */
    @Test
    public void badgeCenterFollowsWholeTextBlockNotFirstLine() {
        float firstLineCenter = 30f;
        assertEquals("单行仍保持原来的第一行中心",
                firstLineCenter, QuizActivity.badgeCenterY(1, 60f, firstLineCenter), 0f);
        for (int lines = 2; lines <= 5; lines++) {
            float blockHeight = lines * 60f;
            assertEquals(lines + " 行时徽标要落在整个文本块正中",
                    blockHeight / 2f,
                    QuizActivity.badgeCenterY(lines, blockHeight, firstLineCenter), 0f);
        }
        assertTrue("两行答案的徽标必须从第一行下移",
                QuizActivity.badgeCenterY(2, 120f, firstLineCenter) > firstLineCenter);
        assertEquals("首帧还没有 Layout 时退回第一行中心，不能崩",
                firstLineCenter, QuizActivity.badgeCenterY(1, 0f, firstLineCenter), 0f);
    }

    @Test
    public void multiLineButtonSuppliesItsLayoutLineCount() {
        Button b = buttons().get(0);
        b.setText(QuizActivity.optionText(b, 2, "第一行文字\n第二行文字\n第三行文字", null));
        b.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        b.layout(0, 0, 600, Math.max(1, b.getMeasuredHeight()));
        Layout layout = b.getLayout();
        assertNotNull("测量后应能拿到文本 Layout", layout);
        assertTrue("显式换行的答案应有多行", layout.getLineCount() >= 3);
        float first = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f;
        assertEquals("徽标中心应等于文本块高度的一半", layout.getHeight() / 2f,
                QuizActivity.badgeCenterY(layout.getLineCount(), layout.getHeight(), first), 0f);
        assertTrue("多行时不能再用第一行中心",
                QuizActivity.badgeCenterY(layout.getLineCount(), layout.getHeight(), first) > first);
    }

    /**
     * v1.7.27：四个选项 + 题干 + 底部反馈要在一屏内放下，卡片不能再按 72dp 保底，
     * 否则实机上最后一个选项会被反馈条/提示文字挤掉半截。
     */
    @Test
    public void optionCardsAreCompactEnoughForOneScreen() {
        float d = activity.getResources().getDisplayMetrics().density;
        java.util.List<Button> bs = buttons();
        assertTrue(bs.size() >= 2);
        for (Button b : bs) {
            assertTrue("选项最小高度要收紧到 56dp 以内，否则四选项同屏放不下",
                    b.getMinHeight() <= Math.round(56 * d) + 1);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            assertTrue("选项之间的间距要收紧到 5dp 以内",
                    lp.topMargin <= Math.round(5 * d) + 1);
        }
    }

    @Test
    public void quizPageIsScrollableWhenOptionsOverflow() {
        ScrollView scroll = (ScrollView) activity.findViewById(R.id.quizScroll);
        assertNotNull("超出一屏的长答案要能滚动看全", scroll);
        assertTrue("短答案时仍要撑满整屏", scroll.isFillViewport());
        assertEquals("选项区自身也要按内容高度测量",
                LinearLayout.LayoutParams.WRAP_CONTENT, optionBox().getLayoutParams().height);
    }
}
