package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    private LinearLayout optionBox() {
        return (LinearLayout) activity.findViewById(R.id.optionBox);
    }

    @Test
    public void optionHeightIsWrapContentNotWeightedSplit() {
        LinearLayout box = optionBox();
        assertTrue(box.getChildCount() >= 2);
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            assertEquals("选项不能再被 0dp + weight 强制平分固定高度",
                    LinearLayout.LayoutParams.WRAP_CONTENT, lp.height);
            assertEquals("等分权重必须移除，否则长答案仍会被压缩", 0f, lp.weight, 0f);
            Button b = (Button) v;
            assertTrue("必须允许换行", b.getMaxLines() > 1);
            assertFalse("不能是单行变换", b.getTransformationMethod()
                    instanceof android.text.method.SingleLineTransformationMethod);
        }
    }

    @Test
    public void longAnswerRendersFullyAndKeepsHangingIndent() {
        Button b = (Button) optionBox().getChildAt(0);
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
        LinearLayout box = optionBox();
        Button shortOne = (Button) box.getChildAt(0);
        Button longOne = (Button) box.getChildAt(1);

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

    @Test
    public void quizPageIsScrollableWhenOptionsOverflow() {
        ScrollView scroll = (ScrollView) activity.findViewById(R.id.quizScroll);
        assertNotNull("超出一屏的长答案要能滚动看全", scroll);
        assertTrue("短答案时仍要撑满整屏", scroll.isFillViewport());
        assertEquals("选项区自身也要按内容高度测量",
                LinearLayout.LayoutParams.WRAP_CONTENT, optionBox().getLayoutParams().height);
    }
}
