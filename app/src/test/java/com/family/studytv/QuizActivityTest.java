package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.time.Duration;

/**
 * 用黑盒方式点按钮验证答题规则：5 题全对才关闭；
 * 答错先揭示正确答案，随后换一组新题从第 1 题重新开始。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QuizActivityTest {

    private ActivityController<QuizActivity> controller;
    private QuizActivity activity;

    @Before
    public void setUp() {
        QuizActivity.running = false;
        Prefs.setQuizCount(5);
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

    private String progress() {
        return ((TextView) activity.findViewById(R.id.tvProgress)).getText().toString();
    }

    private String optionText(Button b) {
        return b.getText().toString().replaceFirst("^[A-F]\\s+", "").trim();
    }

    /** 当前屏幕上四个选项的文字（去掉 A/B/C/D 前缀） */
    private java.util.List<String> shownOptions() {
        LinearLayout box = (LinearLayout) activity.findViewById(R.id.optionBox);
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (int i = 0; i < box.getChildCount(); i++) {
            out.add(optionText((Button) box.getChildAt(i)));
        }
        return out;
    }

    private String correctTextOf(String questionText, java.util.List<String> shown) {        for (QuestionBank.Question q : QuestionBank.get().all()) {
            if (!q.q.equals(questionText)) {
                continue;
            }
            // 复合诗词题的题干是复用的（「下列「诗句 — 作者 — 朝代」搭配正确的是？」），
            // 必须连选项一起比对，否则会撞到同题干的第一道题、拿错答案。
            if (shown != null) {
                if (q.options.size() != shown.size() || !q.options.containsAll(shown)) {
                    continue;
                }
            }
            return q.options.get(q.answer);
        }
        return null;
    }

    private void answer(boolean correct) {
        String qText = ((TextView) activity.findViewById(R.id.tvQuestion)).getText().toString();

        LinearLayout box = (LinearLayout) activity.findViewById(R.id.optionBox);
        assertTrue("每题应有多个选项", box.getChildCount() >= 2);

        java.util.List<String> shown = new java.util.ArrayList<String>();
        for (int i = 0; i < box.getChildCount(); i++) {
            shown.add(optionText((Button) box.getChildAt(i)));
        }
        String right = correctTextOf(qText, shown);
        assertNotNull("题目应来自题库：" + qText, right);

        Button target = null;
        for (int i = 0; i < box.getChildCount(); i++) {
            Button b = (Button) box.getChildAt(i);
            if (optionText(b).equals(right) == correct) {
                target = b;
                break;
            }
        }
        assertNotNull("应能定位到目标选项", target);
        target.performClick();
    }

    /** v1.7.24：家长后台改「学习页字号」后，已显示的答题页必须立刻跟着变，不重抽题 */
    @Test
    public void savedFontScaleRefreshesLiveQuizImmediately() throws Exception {
        TextView question = (TextView) activity.findViewById(R.id.tvQuestion);
        LinearLayout box = (LinearLayout) activity.findViewById(R.id.optionBox);
        Button first = (Button) box.getChildAt(0);
        float q0 = question.getTextSize();
        float o0 = first.getTextSize();
        String stem0 = question.getText().toString();
        String opt0 = first.getText().toString();
        assertTrue("Robolectric 应能读出布局里的基准字号", q0 > 0f && o0 > 0f);

        try {
            JSONObject bigger = Rules.get(activity);
            bigger.put("fontScale", 150);
            Rules.save(activity, bigger);          // 内部广播 ACTION_CONFIG_CHANGED
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("题干放大到 150%", question.getTextSize() > q0 * 1.4f);
            assertTrue("选项放大到 150%", first.getTextSize() > o0 * 1.4f);
            assertEquals("刷新字号不该重抽题", stem0, question.getText().toString());
            assertEquals("刷新字号不该改选项内容", opt0, first.getText().toString());

            JSONObject smaller = Rules.get(activity);
            smaller.put("fontScale", 75);
            Rules.save(activity, smaller);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("题干缩小到 75%", question.getTextSize() < q0);
            assertTrue("选项缩小到 75%", first.getTextSize() < o0);
            assertEquals(stem0, question.getText().toString());
        } finally {
            JSONObject back = Rules.get(activity);
            back.put("fontScale", 100);
            Rules.save(activity, back);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    /** v1.7.25：后台换护眼风格后，已显示的答题页立即换色，不重抽题 */
    @Test
    public void savedEyeCareThemeRepaintsLiveQuizImmediately() {
        TextView question = (TextView) activity.findViewById(R.id.tvQuestion);
        LinearLayout box = (LinearLayout) activity.findViewById(R.id.optionBox);
        Button first = (Button) box.getChildAt(0);
        String stem0 = question.getText().toString();
        Theme.Palette paper = Theme.of("paper");
        try {
            Prefs.setTheme("paper");
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("题干换成羊皮纸文字色", paper.text, question.getCurrentTextColor());
            assertEquals("选项换成羊皮纸文字色", paper.text, first.getCurrentTextColor());
            assertEquals("换配色不该重抽题", stem0, question.getText().toString());
            assertNotNull("页面底色要被重涂",
                    activity.findViewById(R.id.quizRoot).getBackground());

            Prefs.setTheme(Theme.DEFAULT);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("切回默认要还原成默认文字色",
                    Theme.of(Theme.DEFAULT).text, question.getCurrentTextColor());
        } finally {
            Prefs.setTheme(Theme.DEFAULT);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test
    public void openingQuizMarksPendingAndShowsFirstQuestion() {
        assertTrue("弹窗打开即视为未完成答题", Prefs.isPending());
        assertEquals("第 1 / 5 题", progress());
    }

    @Test
    public void fiveCorrectAnswersCloseThePopupAndReschedule() {
        long before = Prefs.dueAt();
        for (int i = 0; i < 5; i++) {
            answer(true);
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));

        assertTrue("全部答对应关闭弹窗", activity.isFinishing());
        assertFalse("pending 应被清除", Prefs.isPending());
        assertTrue("应重新排下一次弹题", Prefs.dueAt() > before);
    }

    @Test
    public void wrongAnswerRevealsCorrectOneThenStartsANewSet() {
        answer(true);
        answer(true);
        assertEquals("第 3 / 5 题", progress());

        String failedQuestion = ((TextView) activity.findViewById(R.id.tvQuestion)).getText().toString();
        String right = correctTextOf(failedQuestion, shownOptions());
        assertNotNull(right);

        answer(false);

        // ① 当场揭示正确答案，且不跳到第 1 题
        LinearLayout box = (LinearLayout) activity.findViewById(R.id.optionBox);
        boolean markedCorrect = false;
        boolean markedWrong = false;
        for (int i = 0; i < box.getChildCount(); i++) {
            String t = ((Button) box.getChildAt(i)).getText().toString();
            if (t.contains("← 正确答案")) {
                markedCorrect = true;
                assertTrue("标绿的应当是真实正确答案", t.contains(right));
            }
            if (t.contains("← 你选的")) {
                markedWrong = true;
            }
        }
        assertTrue("答错后应标出正确答案", markedCorrect);
        assertTrue("答错后应标出孩子选错的那项", markedWrong);

        TextView fb = (TextView) activity.findViewById(R.id.tvFeedback);
        assertTrue("提示里要写出正确答案：" + fb.getText(), fb.getText().toString().contains("正确答案"));
        assertEquals("揭示答案期间还停在原题", "第 3 / 5 题", progress());
        assertFalse("答错不能关闭弹窗", activity.isFinishing());
        assertTrue(Prefs.isPending());

        // ② 揭示期间再点选项不生效（还在同一题）
        ((Button) box.getChildAt(0)).performClick();
        assertEquals("揭示答案期间点击应被忽略", "第 3 / 5 题", progress());

        // ③ 等够时间 → 换一组新题，从第 1 题开始
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000));
        assertEquals("换新题后从第 1 题开始", "第 1 / 5 题", progress());
        String newQuestion = ((TextView) activity.findViewById(R.id.tvQuestion)).getText().toString();
        assertTrue("新的一组题不应包含刚答错的那道：" + newQuestion, !failedQuestion.equals(newQuestion));
        assertTrue("新的一组题目数量不变：" + progress(), progress().endsWith("/ 5 题"));
    }

    @Test
    public void backKeyCannotEscape() {
        activity.onBackPressed();
        assertFalse("返回键不能退出答题", activity.isFinishing());
        assertEquals("第 1 / 5 题", progress());
    }

    @Test
    public void passingClosesAndStaysInBackground() {
        for (int i = 0; i < 5; i++) {
            answer(true);
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));

        assertTrue("通关后页面应自动关闭", activity.isFinishing());
        assertNull("通关后不得打开二维码页，应用留在后台即可",
                shadowOf(activity).getNextStartedActivity());
        assertFalse("pending 应被清除", Prefs.isPending());
    }

    @Test
    public void noVisibleManageEntryOnQuizScreen() {
        assertNull("答题页不该再有任何可见的管理入口",
                findByText(activity.getWindow().getDecorView(), "管理"));
        assertNull("进入答题页不该弹任何键盘", ShadowDialog.getLatestDialog());
    }

    @Test
    public void upKeyGestureNeedsPasswordBeforeShowingQr() {
        // 隐藏入口：遥控器连按 5 次上键才弹管理密码键盘
        pressUpTimes(5);

        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("连按 5 次上键应弹出密码键盘", d);
        assertTrue(d.isShowing());

        // ① 密码错误：键盘不关，也打不开二维码页
        type(d, "0000");
        d.findViewById(R.id.btnOk).performClick();
        assertTrue("密码错误时键盘应留在界面上", d.isShowing());
        assertNull("密码错误不得打开二维码页", shadowOf(activity).getNextStartedActivity());

        // ② 密码正确：关闭键盘并打开只读信息页
        d.findViewById(R.id.btnClear).performClick();
        type(d, Prefs.pin());
        d.findViewById(R.id.btnOk).performClick();
        assertFalse("密码正确应关闭键盘", d.isShowing());

        Intent next = shadowOf(activity).getNextStartedActivity();
        assertNotNull("密码正确后打开二维码页", next);
        assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());
        assertTrue("必须以只读信息页模式打开", next.getBooleanExtra(MainActivity.EXTRA_INFO, false));
        assertFalse("答题页应留在下面，关掉信息页即回到答题", activity.isFinishing());
    }

    @Test
    public void fourUpsAreNotEnough() {
        pressUpTimes(4);
        assertNull("差一次不该弹键盘", ShadowDialog.getLatestDialog());
        assertEquals("答题进度不受影响", "第 1 / 5 题", progress());
    }

    @Test
    public void slowUpsDoNotCountAsGesture() {
        for (int i = 0; i < 5; i++) {
            pressUp(0);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));  // 超过 800ms 的间隔
        }
        assertNull("零散按上键（间隔过大）不该触发", ShadowDialog.getLatestDialog());
    }

    @Test
    public void otherKeyBreaksTheSequence() {
        pressUpTimes(3);
        long t = android.os.SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, 0));
        pressUpTimes(3);
        assertNull("中间按了别的键应重新计数", ShadowDialog.getLatestDialog());
    }

    @Test
    public void longPressRepeatsAreIgnored() {
        for (int i = 0; i < 8; i++) {
            pressUp(i);     // 模拟长按：除第一次外都是 repeatCount>0 的重复事件
        }
        assertNull("按住上键不放不该凑数", ShadowDialog.getLatestDialog());
    }

    @Test
    public void upKeyGestureIsSilentWhenLockedOut() {
        Prefs.setPin("1234");
        for (int i = 0; i < 5; i++) {
            Prefs.verifyPin("0000");
        }
        assertTrue("连错 5 次应进入冷却", Prefs.pinLockRemainMs() > 0);

        pressUpTimes(5);
        assertNull("冷却期内连键盘都不弹", ShadowDialog.getLatestDialog());
        TextView fb = (TextView) activity.findViewById(R.id.tvFeedback);
        assertTrue("应提示还要等多久：" + fb.getText(), fb.getText().toString().contains("输错次数过多"));
        assertFalse("冷却期内页面不能被关掉", activity.isFinishing());
    }

    /** 模拟遥控器按一次上键，repeat 用于模拟长按产生的重复事件 */
    private boolean pressUp(int repeat) {
        long t = android.os.SystemClock.uptimeMillis();
        return activity.dispatchKeyEvent(
                new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, repeat));
    }

    private void pressUpTimes(int n) {
        for (int i = 0; i < n; i++) {
            pressUp(0);
        }
    }

    /** 家长在手机后台点「立即解锁」，答题页要能被远程关掉 */
    @Test
    public void forceUnlockFinishesQuizPage() {
        assertTrue("答题页应处于显示状态", QuizActivity.running);
        QuizActivity.forceUnlock();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("解锁后答题页应结束，孩子才能正常看电视", activity.isFinishing());
    }

    /** 在视图树里按文字找控件，用来确认界面上没有可见的管理入口 */
    private View findByText(View root, String text) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().contains(text)) {
                return root;
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByText(g.getChildAt(i), text);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    @Test
    public void topBarShowsSystemClockInsteadOfPromptCopy() {
        // 原来的「答对全部题目才能继续看电视」提示已换成系统时钟
        int oldId = activity.getResources().getIdentifier("tvMode", "id", activity.getPackageName());
        if (oldId != 0) {
            assertNull("旧的提示文案控件应当已经删掉", activity.findViewById(oldId));
        }
        TextView clock = (TextView) activity.findViewById(R.id.tvClock);
        assertNotNull("右上角应有系统时钟", clock);
        String txt = clock.getText().toString();
        assertTrue("时钟格式应为 HH:mm:ss，实际是「" + txt + "」", txt.matches("\\d{2}:\\d{2}:\\d{2}"));
        assertFalse("不应再显示原来的提示文案", "答对全部题目才能继续看电视".equals(txt));
        TextView question = (TextView) activity.findViewById(R.id.tvQuestion);
        assertTrue("时钟字号要明显小于题干", clock.getTextSize() < question.getTextSize());
    }

    @Test
    public void clockHasNoBorderOrBackground() {
        // v1.8.1：时钟只显示文字，不要胶囊底、描边和内边距
        TextView clock = (TextView) activity.findViewById(R.id.tvClock);
        assertNotNull(clock);
        assertNull("时钟不应有背景或描边", clock.getBackground());
        assertEquals("时钟不应有左内边距", 0, clock.getPaddingLeft());
        assertEquals("时钟不应有上内边距", 0, clock.getPaddingTop());
    }

    @Test
    public void optionsHaveNoDropShadowSoCornersStayClean() {
        // v1.8.3：Material Button 自带 2dp elevation，深色底看不出来，
        // 换羊皮纸 / 豆沙绿 / 雾蓝灰后圆角外会浮出一圈灰边
        LinearLayout box = activity.findViewById(R.id.optionBox);
        assertTrue("应已渲染出选项", box.getChildCount() > 0);
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            assertEquals("选项不应有投影高度", 0f, v.getElevation(), 0.01f);
            assertNull("选项不应带按压抬升动画", v.getStateListAnimator());
        }
    }

    @Test
    public void subjectTagAndFeedbackUseSmallRadius() {
        // v1.8.4：全站圆角统一 6dp，反馈条无描边
        View tag = activity.findViewById(R.id.tvSubject);
        GradientDrawable tagBg = (GradientDrawable) tag.getBackground();
        assertEquals("学科标签圆角应为 6dp", dp(tag, 6), tagBg.getCornerRadius(), 0.5f);
        assertTrue("学科标签保留强调描边", strokeWidth(tagBg) > 0);

        View feedback = activity.findViewById(R.id.tvFeedback);
        GradientDrawable fbBg = (GradientDrawable) feedback.getBackground();
        assertEquals("反馈条圆角应为 6dp", dp(feedback, 6), fbBg.getCornerRadius(), 0.5f);
        assertTrue("反馈条不应有描边", strokeWidth(fbBg) <= 0);
    }

    /** GradientDrawable 没有公开的描边读取接口，只能读 mGradientState 里的字段 */
    private static int strokeWidth(GradientDrawable g) {
        try {
            java.lang.reflect.Field sf = GradientDrawable.class.getDeclaredField("mGradientState");
            sf.setAccessible(true);
            Object state = sf.get(g);
            java.lang.reflect.Field f = state.getClass().getDeclaredField("mStrokeWidth");
            f.setAccessible(true);
            return f.getInt(state);
        } catch (Exception e) {
            throw new AssertionError("读不到描边宽度", e);
        }
    }

    private static float dp(View v, float value) {
        return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP,
                value, v.getResources().getDisplayMetrics());
    }

    private void type(Dialog d, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int id = d.getContext().getResources()
                    .getIdentifier("btn" + c, "id", d.getContext().getPackageName());
            View v = d.findViewById(id);
            assertNotNull("键盘上应有按键 " + c, v);
            v.performClick();
        }
    }
}
