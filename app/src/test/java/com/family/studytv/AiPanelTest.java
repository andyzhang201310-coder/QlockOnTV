package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * v1.10.6 AI 讲解面板：上下键每次只换一行且落点必须是行首，
 * 翻页提示与「继续下一题」同一水平线，按钮不抢正文的视觉重心。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AiPanelTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication().getApplicationContext();
    }

    @Test
    public void 每次上下键只换一行且落在行首() {
        int[] tops = {0, 40, 80, 120, 160, 200, 240, 280, 320};
        int maxY = 200;
        assertEquals(200, QuizActivity.maxSnapY(tops, maxY));
        assertEquals(40, QuizActivity.stepOneLine(tops, 0, true, maxY, 40));
        assertEquals(80, QuizActivity.stepOneLine(tops, 40, true, maxY, 40));
        assertEquals(200, QuizActivity.stepOneLine(tops, 160, true, maxY, 40));
        assertEquals(200, QuizActivity.stepOneLine(tops, 200, true, maxY, 40));
        assertEquals(160, QuizActivity.stepOneLine(tops, 200, false, maxY, 40));
        assertEquals(0, QuizActivity.stepOneLine(tops, 40, false, maxY, 40));
        for (int t : new int[]{40, 80, 200, 160, 0}) {
            boolean onLine = false;
            for (int top : tops) {
                onLine |= top == t;
            }
            assertTrue("落点 " + t + " 必须是某一行的行首", onLine);
        }
    }

    @Test
    public void 没有排版信息时按行高回退() {
        assertEquals(40, QuizActivity.stepOneLine(null, 0, true, 300, 40));
        assertEquals(80, QuizActivity.stepOneLine(null, 40, true, 300, 40));
        assertEquals(0, QuizActivity.stepOneLine(null, 40, false, 300, 40));
        assertEquals(300, QuizActivity.stepOneLine(null, 280, true, 300, 40));
        assertEquals(300, QuizActivity.maxSnapY(null, 300));
    }

    @Test
    public void 被系统clamp到行中间时退回最近行首() {
        int[] tops = {0, 40, 80, 120, 160};
        assertEquals(120, QuizActivity.nearestLineTopAtOrBelow(tops, 137));
        assertEquals(160, QuizActivity.nearestLineTopAtOrBelow(tops, 999));
        assertEquals(0, QuizActivity.nearestLineTopAtOrBelow(tops, 12));
        assertEquals(137, QuizActivity.nearestLineTopAtOrBelow(null, 137));
    }

    @Test
    public void 翻页提示与继续下一题在同一水平线且按钮改小() {
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_ai, null);
        Button close = (Button) root.findViewById(R.id.btnAiClose);
        Button retry = (Button) root.findViewById(R.id.btnAiRetry);
        TextView more = (TextView) root.findViewById(R.id.tvAiMore);
        ViewGroup row = (ViewGroup) close.getParent();
        assertEquals("提示要和按钮在同一行容器里", row, more.getParent());
        assertTrue("提示排在按钮左侧", row.indexOfChild(more) < row.indexOfChild(close));
        float d = ctx.getResources().getDisplayMetrics().density;
        assertEquals(128f, close.getMinWidth() / d, 1f);
        assertEquals(44f, close.getMinHeight() / d, 1f);
        assertEquals(15f, close.getTextSize() / d, 0.5f);
        assertEquals("继续下一题", close.getText().toString());
        assertEquals("重试按钮同尺寸风格", close.getTextSize(), retry.getTextSize(), 0.5f);
        assertEquals(44f, retry.getMinHeight() / d, 1f);
    }
}
