package com.family.studytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.ReplacementSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 强制答题页：一次出 N 题（默认 5），全部答对才关闭，服务随即重新计时。
 * 中途答错会先揭示正确答案，停几秒让孩子看清，再换一组全新的随机题目重新开始。
 *
 * <p>答题页同时是「家长管理」的唯一入口，且入口是隐藏的：遥控器连按 5 次「上键」
 * 才会弹出管理密码键盘，输对才显示二维码说明页。界面上没有任何按钮、文字提示这个入口，
 * 孩子即使把遥控器每个键都按一遍也很难撞上（长按重复事件不计入）。
 */
public class QuizActivity extends Activity {

    /** 供守护服务判断当前是否正在答题（同一进程内静态标记） */
    public static volatile boolean running = false;

    /** 当前显示在屏幕上的答题页，供手机后台「立即解锁」远程关闭 */
    private static volatile QuizActivity active = null;

    /**
     * 电视待机时被系统拉起来，onCreate 里直接 finish 掉（v1.7.33）。
     * 标记下来，避免 onResume/onPause 去碰还没创建的控件。
     */
    private boolean bailed = false;

    /** 答错后停留多久再换新题（毫秒），留足看正确答案的时间；规则可改 */
    private static final long REVEAL_MS = 4500;
    /** 通关后停留多久自动关闭 */
    private static final long PASS_MS = 900;

    /** 隐藏入口：连续按「上键」几次打开家长管理 */
    private static final int GESTURE_UP_COUNT = 5;
    /** 相邻两次上键的最大间隔，超时即作废重新数（必须"连按"才有效） */
    private static final long GESTURE_GAP_MS = 800;

    private static final String[] LETTERS = {"A", "B", "C", "D", "E", "F"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<Item>();
    private int index = 0;
    private boolean passed = false;
    private boolean busy = false;
    /** 家长正在看管理页：此时不要把答题页拉回前台，否则会盖住二维码 */
    private boolean managing = false;
    /** 上一轮答错后被换掉的题，用于下一组尽量避开 */
    private final Set<String> usedIds = new LinkedHashSet<String>();

    // ---- 管理规则（每次进入答题页重新读一次，家长改完规则下一轮即生效）
    private JSONObject cfg;
    private String playMode = "round";
    private double passRatio = 1.0;
    private boolean shuffleOptions = true;
    private boolean reviewEnabled = false;
    private long revealMs = REVEAL_MS;
    private float fontScale = 1f;
    private int targetCount = 5;

    /** 玩法计数：round 用组内正确率，streak 用连对数，count 用累计答对数 */
    private int groupCorrect = 0;
    private int groupAnswered = 0;
    private int streak = 0;
    private int totalCorrect = 0;
    /** 孩子自己选的年级（childSelectLevel），null = 沿用后台范围 */
    private Set<Integer> sessionGrades = null;
    private boolean choosingGrade = false;
    private float baseQuestionPx = 0f;
    private float baseOptionPx = 0f;

    /** 家长后台保存规则（含「学习页字号」）后即时刷新，不等 Activity 重建 */
    private final BroadcastReceiver rulesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing()) {
                        return;
                    }
                    if (Theme.ACTION_CHANGED.equals(intent.getAction())) {
                        applyTheme();
                    } else {
                        refreshFontScale();
                    }
                }
            });
        }
    };
    private boolean rulesReceiverRegistered = false;

    /** 当前护眼配色；默认深色，后台改风格后由广播换成对应色板 */
    private Theme.Palette theme = Theme.of(Theme.DEFAULT);
    /** 反馈文字的角色（ok / bad / warn / neutral），换配色时按角色重新取色 */
    private String feedbackRole = "neutral";

    /** 隐藏手势的计数状态 */
    private int upHits = 0;
    private long lastUpAt = 0;

    private TextView tvProgress;
    private TextView tvSubject;
    private TextView tvClock;
    private TextView tvQuestion;
    private TextView tvFeedback;
    private LinearLayout optionBox;
    private ScrollView quizScroll;

    /** 顶栏右上角的系统时钟：每秒对一次表，秒数变化时不会左右抖 */
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (tvClock != null) {
                tvClock.setText(clockText());
                handler.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L));
            }
        }
    };

    private static String clockText() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis());
        return pad2(c.get(java.util.Calendar.HOUR_OF_DAY)) + ":"
                + pad2(c.get(java.util.Calendar.MINUTE)) + ":"
                + pad2(c.get(java.util.Calendar.SECOND));
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** 小孩按 HOME 逃到桌面时把自己拉回来 */
    private final Runnable relaunch = new Runnable() {
        @Override
        public void run() {
            if (passed || managing || isFinishing() || "practice".equals(playMode)) {
                return;   // 自由练习允许孩子自己退出，不再拉回
            }
            // 屏幕熄了就不用拉回来，避免反复点亮屏幕
            if (!screenOn()) {
                return;
            }
            try {
                Intent i = new Intent(QuizActivity.this, QuizActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    };

    private boolean screenOn() {
        return DeviceAwake.isAwake(this);
    }

    private static final class Item {
        QuestionBank.Question q;
        List<String> options;
        int answer;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 电视待机时被系统拉起来（服务侧拦下后仍可能有排队 Activity 被 create）：
        // 直接退出，绝不点亮面板。
        if (!DeviceAwake.isAwake(this)) {
            bailed = true;
            finish();
            return;
        }
        // v1.7.33 起不再加 FLAG_TURN_SCREEN_ON：能走到这里说明屏幕本来就亮着，
        // 加它只会在息屏场景把睡着的电视点亮。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_quiz);
        hideSystemUi();

        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvSubject = (TextView) findViewById(R.id.tvSubject);
        tvClock = (TextView) findViewById(R.id.tvClock);
        if (tvClock != null) {
            tvClock.setText(clockText());
            handler.post(clockTick);
        }
        tvQuestion = (TextView) findViewById(R.id.tvQuestion);
        tvFeedback = (TextView) findViewById(R.id.tvFeedback);
        optionBox = (LinearLayout) findViewById(R.id.optionBox);
        quizScroll = (ScrollView) findViewById(R.id.quizScroll);

        // 字号基准取布局里的原始值，规则里的 fontScale 在这个基础上缩放
        baseQuestionPx = tvQuestion.getTextSize();
        Button probe = (Button) getLayoutInflater().inflate(R.layout.item_option, optionBox, false);
        baseOptionPx = probe.getTextSize();

        try {
            IntentFilter filter = new IntentFilter(Rules.ACTION_CONFIG_CHANGED);
            filter.addAction(Theme.ACTION_CHANGED);
            registerReceiver(rulesReceiver, filter);
            rulesReceiverRegistered = true;
        } catch (Throwable ignored) {
        }

        running = true;
        active = this;
        // 任何进入答题的路径都确保守护服务（含局域网后台）在跑
        GuardService.start(this);
        Prefs.setPending(true);
        loadRules();
        // 护眼配色：先把静态布局染成当前风格，随后 show() 里新建的选项直接用同一色板
        applyTheme();
        if (needsGradeChoice()) {
            showGradePicker();
        } else {
            buildSession();
            show(0);
        }
    }

    /** 每次进入答题页重读规则；家长在后台改完，下一轮弹题就按新规则来 */
    private void loadRules() {
        cfg = Rules.get(this);
        playMode = cfg.optString("playMode", "round");
        passRatio = Math.max(0.1d, Math.min(1.0d, cfg.optDouble("passRatio", 1.0d)));
        shuffleOptions = cfg.optBoolean("shuffleOptions", true);
        reviewEnabled = cfg.optBoolean("reviewEnabled", false);
        revealMs = cfg.optInt("wrongReviewSeconds", 5) * 1000L;
        if (revealMs <= 0L) {
            revealMs = 600L;   // 0 = 不强制停留，但仍留一瞬看反馈
        }
        fontScale = cfg.optInt("fontScale", 100) / 100f;
        targetCount = Rules.questionCount(this, cfg);
    }

    private List<Integer> allowedLevels() {
        Set<Integer> g = Prefs.gradeSet();
        List<Integer> out = new ArrayList<Integer>();
        for (int i = Prefs.GRADE_MIN; i <= Prefs.GRADE_MAX; i++) {
            if (g.isEmpty() || g.contains(i)) {
                out.add(i);
            }
        }
        return out;
    }

    private boolean needsGradeChoice() {
        return cfg != null && cfg.optBoolean("childSelectLevel", false)
                && allowedLevels().size() > 1;
    }

    /** 「孩子选择年级」：把选项区临时当作年级选择器，选完才开始本轮答题 */
    private void showGradePicker() {
        choosingGrade = true;
        tvProgress.setText("选择年级");
        tvSubject.setText("管理规则");
        tvQuestion.setText("今天想做几年级的题？");
        applyQuestionFont();
        feedback(0, "");
        optionBox.removeAllViews();
        final List<Integer> levels = allowedLevels();
        for (int k = 0; k < levels.size(); k++) {
            final int g = levels.get(k);
            Button b = (Button) getLayoutInflater().inflate(R.layout.item_option, optionBox, false);
            setOptionText(b, k, g + " 年级", null, null);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sessionGrades = new LinkedHashSet<Integer>();
                    sessionGrades.add(g);
                    choosingGrade = false;
                    buildSession();
                    show(0);
                }
            });
            b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        ensureVisible(v);
                    }
                }
            });
            optionBox.addView(b);
        }
        if (optionBox.getChildCount() > 0) {
            optionBox.getChildAt(0).requestFocus();
        }
        scrollToTop();
    }

    private void applyQuestionFont() {
        if (baseQuestionPx > 0f) {
            tvQuestion.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    baseQuestionPx * fontScale);
        }
    }

    private void applyOptionFont(Button b) {
        if (baseOptionPx > 0f) {
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseOptionPx * fontScale);
        }
    }

    /** 选项文字按角色取色：换配色时只要角色不变，重画后颜色自动跟着走 */
    private int optionTextColor(String role) {
        if ("ok".equals(role)) {
            return theme.ok;
        }
        if ("bad".equals(role)) {
            return theme.bad;
        }
        if ("dim".equals(role)) {
            return theme.muted;
        }
        return theme.text;
    }

    /**
     * 统一渲染一个选项：把原始文本、反馈后缀、颜色角色记在 tag 里。
     * 字母徽标与悬挂缩进都按当前字号绘制，字号 / 配色一变必须重画，
     * 光改 textSize 或 textColor 会让徽标错位。
     */
    private void setOptionText(Button b, int k, String text, String suffix, String role) {
        applyOptionFont(b);
        String r = role == null ? "" : role;
        b.setTag(new String[]{text, suffix == null ? "" : suffix, r});
        b.setTextColor(optionTextColor(r));
        b.setBackground(Theme.optionBackground(b, theme));
        b.setText(optionText(b, k, text, suffix, theme));
    }

    /** 按当前配色与字号重画已显示的选项，不改题目、不改进度、不打断揭示 */
    private void repaintOptions() {
        for (int k = 0; k < optionBox.getChildCount(); k++) {
            View v = optionBox.getChildAt(k);
            if (!(v instanceof Button)) {
                continue;
            }
            Button b = (Button) v;
            Object tag = b.getTag();
            if (!(tag instanceof String[])) {
                continue;
            }
            String[] parts = (String[]) tag;
            setOptionText(b, k, parts[0],
                    parts[1].length() == 0 ? null : parts[1],
                    parts.length > 2 ? parts[2] : "");
        }
    }

    private static int px(View v, float dp) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, dp, v.getResources().getDisplayMetrics());
    }

    /** 家长后台换护眼风格后立刻生效：重涂底色、文字、徽标与反馈，不重抽题 */
    private void applyTheme() {
        theme = Theme.of(Prefs.theme());
        // 必须染在布局根节点上：它自己带 bg_glow 背景，染到 content 上会被盖住
        View root = findViewById(R.id.quizRoot);
        if (root == null) {
            return;
        }
        root.setBackground(Theme.background(root, theme));
        Theme.tintTextTree(root, theme);
        // v1.8.1：右上角时钟只留文字，不再套胶囊底/描边（换配色也不补背景）
        if (tvClock != null) {
            tvClock.setBackground(null);
        }
        tvSubject.setBackground(Theme.card(tvSubject, theme.tagBg, theme.accent, 999f,
                Math.max(1, px(tvSubject, 2))));
        tvFeedback.setBackground(Theme.card(tvFeedback, theme.feedbackBg, theme.stroke, 16f,
                Math.max(1, px(tvFeedback, 2))));
        if (!items.isEmpty() && index >= 0 && index < items.size()) {
            renderSubject(items.get(index).q);   // 星级颜色跟着换
        }
        repaintOptions();
        applyFeedbackColor();
    }

    private int feedbackColor() {
        if ("ok".equals(feedbackRole)) {
            return theme.ok;
        }
        if ("bad".equals(feedbackRole)) {
            return theme.bad;
        }
        if ("warn".equals(feedbackRole)) {
            return theme.warn;
        }
        return theme.text2;
    }

    private void applyFeedbackColor() {
        tvFeedback.setTextColor(feedbackColor());
    }

    /**
     * 家长后台改「学习页字号」后立即生效：不重抽题、不重置进度、不打断答错揭示，
     * 只按新的 fontScale 重画题干与已显示的选项（含「← 正确答案」后缀）。
     */
    private void refreshFontScale() {
        float next = Math.max(0.75f, Math.min(2f, Rules.get(this).optInt("fontScale", 100) / 100f));
        if (Math.abs(next - fontScale) < 0.001f) {
            return;
        }
        fontScale = next;
        applyQuestionFont();
        repaintOptions();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bailed) {
            return;
        }
        // 从管理页回来，恢复「离开就拉回」的约束
        managing = false;
        upHits = 0;
        handler.removeCallbacks(relaunch);
        hideSystemUi();
        // 兜底：广播漏掉时（如后台保存时页面不在前台），回到前台也按最新规则重画
        refreshFontScale();
        // 时钟在暂停期间可能停走过，回前台立刻对一次表
        if (tvClock != null) {
            tvClock.setText(clockText());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!passed && !managing && !isFinishing()) {
            handler.removeCallbacks(relaunch);
            handler.postDelayed(relaunch, 1200);
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (rulesReceiverRegistered) {
            try {
                unregisterReceiver(rulesReceiver);
            } catch (Throwable ignored) {
            }
            rulesReceiverRegistered = false;
        }
        if (active == this) {
            active = null;
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void hideSystemUi() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 组题

    private void buildSession() {
        List<QuestionBank.Question> picked = new ArrayList<QuestionBank.Question>();
        Set<String> seen = new LinkedHashSet<String>();
        // 本轮序号 +1。错题按「轮」冷却：这一轮错的题，至少满 N 轮（默认 3 轮）才允许再出现，
        // 所以同一轮里不会刚错完就原题重现，孩子也没法靠记答案过关。
        Rules.nextRoundSeq(this);
        int gap = Rules.reviewGap(cfg);
        Set<String> cooling = Rules.coolingIds(this, gap);
        try {
            // 间隔重复 / 错题重练：一半名额优先给已经过了冷却期的错题，其余照常抽新题
            if (reviewEnabled) {
                Set<String> due = new LinkedHashSet<String>(Rules.dueWrongIds(this, gap));
                due.removeAll(cooling);
                int want = Math.min(targetCount, Math.max(1, targetCount / 2));
                for (QuestionBank.Question q : QuestionBank.get().byIds(due, want)) {
                    if (seen.add(q.id)) {
                        picked.add(q);
                    }
                }
            }
            if (picked.size() < targetCount) {
                Set<String> exclude = new LinkedHashSet<String>(usedIds);
                // 冷却期内的错题本轮一律不出，交给「至少隔 N 轮」的规则
                exclude.addAll(cooling);
                for (QuestionBank.Question q : picked) {
                    exclude.add(q.id);
                }
                // sessionGrades 非空时只从孩子选中的年级抽题
                List<QuestionBank.Question> more = QuestionBank.get()
                        .pick(targetCount - picked.size(), exclude, sessionGrades, null);
                for (QuestionBank.Question q : more) {
                    if (seen.add(q.id)) {
                        picked.add(q);
                    }
                }
            }
        } catch (Throwable e) {
            // 题库异常时保留已经抽到的题；空列表由 show() 的 failOpen 兜底
        }
        items.clear();
        usedIds.clear();
        Random rnd = new Random();
        for (QuestionBank.Question q : picked) {
            Item it = new Item();
            it.q = q;
            List<String> opts = new ArrayList<String>(q.options);
            String correct = opts.get(q.answer);
            if (shuffleOptions) {
                Collections.shuffle(opts, rnd);   // 打乱选项，避免死记位置
            }
            it.options = opts;
            it.answer = opts.indexOf(correct);
            items.add(it);
            usedIds.add(q.id);
        }
        groupCorrect = 0;
        groupAnswered = 0;
        index = 0;
    }

    private static String letter(int k) {
        return k < LETTERS.length ? LETTERS[k] : String.valueOf(k + 1);
    }

    /**
     * 顶部标签：「科目 · 3年级　★★★☆☆」。
     * 实心星用暖金色、空心星用灰色，孩子扫一眼就知道这题几星难度。
     */
    private void renderSubject(QuestionBank.Question q) {
        String head = q.label();
        String stars = q.stars();
        SpannableString s = new SpannableString(head + "    " + stars);
        int start = head.length() + 4;
        for (int k = 0; k < stars.length(); k++) {
            char ch = stars.charAt(k);
            int color = ch == '★' ? theme.starOn : theme.starOff;
            s.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start + k, start + k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvSubject.setText(s);
    }

    private String progressText(int i) {
        if ("streak".equals(playMode)) {
            return "连对 " + streak + " / " + targetCount + " 题";
        }
        if ("count".equals(playMode)) {
            return "累计答对 " + totalCorrect + " / " + targetCount + " 题";
        }
        if ("practice".equals(playMode)) {
            return "自由练习 · 第 " + (i + 1) + " 题（按返回键结束）";
        }
        return "第 " + (i + 1) + " / " + items.size() + " 题";
    }

    private void show(final int i) {
        if (items.isEmpty()) {
            failOpen();
            return;
        }
        index = i;
        final Item it = items.get(i);
        tvProgress.setText(progressText(i));
        // 标签显示「科目 · 年级」+ 难度星级，让孩子和家长一眼看出是哪个年级、多难的题
        renderSubject(it.q);
        tvQuestion.setText(it.q.q);
        applyQuestionFont();

        optionBox.removeAllViews();
        for (int k = 0; k < it.options.size(); k++) {
            Button b = (Button) getLayoutInflater().inflate(R.layout.item_option, optionBox, false);
            setOptionText(b, k, it.options.get(k), null, null);
            final int choice = k;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    answer(choice);
                }
            });
            b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        ensureVisible(v);
                    }
                }
            });
            optionBox.addView(b);
        }
        if (optionBox.getChildCount() > 0) {
            optionBox.getChildAt(0).requestFocus();
        }
        scrollToTop();
    }

    /**
     * A/B/C 画成方块字母徽标；换行后的行首与徽标后的正文对齐（悬挂缩进），
     * 长成语解释不会顶回行首。按钮高度由 wrap_content 自行决定，文本不做任何截断。
     * 徽标按整个文本块垂直居中，两行 / 三行答案也不会贴在第一行。
     */
    /** 兼容单测里的旧调用；正式渲染都走带配色参数的重载 */
    static CharSequence optionText(Button b, int k, String text, String suffix) {
        return optionText(b, k, text, suffix, Theme.of(Theme.DEFAULT));
    }

    static CharSequence optionText(Button b, int k, String text, String suffix, Theme.Palette p) {
        String label = letter(k) + "    ";
        SpannableString s = new SpannableString(label + text + (suffix == null ? "" : suffix));
        float textSize = b.getTextSize();
        int indent = (int) (badgeWidth(textSize) + b.getPaint().measureText("    "));
        s.setSpan(new LeadingMarginSpan.Standard(0, indent), 0, s.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        s.setSpan(new LetterSpan(letter(k), textSize, p, b), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /**
     * 徽标的垂直中心：单行沿用第一行中心（保持原观感）；多行改成整个文本块的中心。
     *
     * <p>ReplacementSpan 只在第一行被绘制，默认会把 A/B/C/D 钉在首行；TextView 绘制前
     * 已把 canvas 平移到文本起点，span 收到的 top/bottom 与 layout.getHeight() 同属
     * Layout 坐标系，所以文本块中心就是高度的一半，用它即可让徽标随行数自动居中。
     */
    static float badgeCenterY(int lineCount, float blockHeight, float firstLineCenter) {
        return lineCount > 1 ? blockHeight / 2f : firstLineCenter;
    }

    private static float badgeWidth(float textSize) {
        return textSize * 1.47f;   // 徽标边长 1.05 + 后置间距 0.42
    }

    /** 选项前缀的方块字母徽标，正确 / 错误时随文字变成绿 / 红底 */
    private static final class LetterSpan extends ReplacementSpan {
        private final String ch;
        private final float box;
        private final float gap;
        private final Theme.Palette p;
        /** 需要读取宿主 TextView 的 Layout 行数，才能把徽标放到整个文本块中心 */
        private final TextView host;

        LetterSpan(String ch, float textSize, Theme.Palette p, TextView host) {
            this.ch = ch;
            this.box = textSize * 1.05f;
            this.gap = textSize * 0.42f;
            this.p = p;
            this.host = host;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            if (fm != null) {
                Paint.FontMetricsInt p = paint.getFontMetricsInt();
                fm.ascent = p.ascent;
                fm.descent = p.descent;
                fm.top = p.top;
                fm.bottom = p.bottom;
            }
            return (int) (box + gap);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            int body = paint.getColor();
            boolean right = p.isOk(body);
            boolean wrong = p.isBad(body);
            float firstLineCenter = (top + bottom) / 2f;
            Layout layout = host == null ? null : host.getLayout();
            float cy = badgeCenterY(layout == null ? 1 : layout.getLineCount(),
                    layout == null ? 0f : layout.getHeight(), firstLineCenter);
            RectF r = new RectF(x, cy - box / 2f, x + box, cy + box / 2f);

            Paint.Align align = paint.getTextAlign();
            float textSize = paint.getTextSize();
            Typeface tf = paint.getTypeface();

            paint.setColor(right ? p.ok : (wrong ? p.bad : p.badgeBg));
            canvas.drawRoundRect(r, box * 0.28f, box * 0.28f, paint);

            paint.setColor(right || wrong ? p.surface : p.badgeText);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(box * 0.56f);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(ch, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, paint);

            paint.setColor(body);
            paint.setTextAlign(align);
            paint.setTextSize(textSize);
            paint.setTypeface(tf);
        }
    }

    /**
     * v1.7.27：把指定选项滚动到可视区内。
     * 底部反馈条出现时会挤压 ScrollView 高度，最后一个选项容易被裁掉半截，
     * 焦点移动或反馈出现后调用一次，保证当前项始终完整可见。
     */
    private void ensureVisible(final View v) {
        if (quizScroll == null || v == null) {
            return;
        }
        quizScroll.post(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                Rect r = new Rect();
                try {
                    quizScroll.offsetDescendantRectToMyCoords(v, r);
                } catch (Throwable ignored) {
                    return;
                }
                int viewH = quizScroll.getHeight();
                int margin = Math.max(6, (int) (8 * getResources().getDisplayMetrics().density));
                int dy = 0;
                if (r.bottom + margin > viewH) {
                    dy = r.bottom + margin - viewH;
                } else if (r.top - margin < 0) {
                    dy = r.top - margin;
                }
                if (dy != 0) {
                    quizScroll.smoothScrollBy(0, dy);
                }
            }
        });
    }

    /** 换题后回到顶部，避免上一题滚动位置残留导致看不到题干 */
    private void scrollToTop() {        if (quizScroll == null) {
            return;
        }
        quizScroll.scrollTo(0, 0);
        quizScroll.post(new Runnable() {
            @Override
            public void run() {
                quizScroll.scrollTo(0, 0);
            }
        });
    }

    // ------------------------------------------------------------------ 判定

    private void answer(int choice) {
        if (busy || passed || choosingGrade || index >= items.size()) {
            return;
        }
        final Item it = items.get(index);
        groupAnswered++;
        if (choice == it.answer) {
            groupCorrect++;
            streak++;
            totalCorrect++;
            Rules.clearWrong(this, it.q.id);   // 答对就把这题从错题库移出
            StudyStats.record(this, it.q.subject, QType.of(it.q), true);
            feedback(theme.ok, "答对了！继续下一题");
            if ("streak".equals(playMode) && streak >= targetCount) {
                pass("连对 " + targetCount + " 题，可以继续看电视啦");
                return;
            }
            if ("count".equals(playMode) && totalCorrect >= targetCount) {
                pass("累计答对 " + targetCount + " 题，可以继续看电视啦");
                return;
            }
            advance();
            return;
        }

        // 答错：揭示答案 + 记录错题（记下当前轮次，之后至少隔 N 轮才会再出现）
        busy = true;
        streak = 0;
        Rules.noteWrong(this, it.q.id);
        StudyStats.record(this, it.q.subject, QType.of(it.q), false);
        revealAnswer(it, choice);
        feedback(theme.bad, "答错了！正确答案是 " + letter(it.answer)
                + "、" + it.options.get(it.answer) + "，看清答案再继续");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                busy = false;
                if ("round".equals(playMode) && passRatio >= 1.0d) {
                    // 严格模式沿用题锁原有行为：答错就换一组全新的题重来
                    rebuild("全对才能通关，换一组新题");
                } else {
                    advance();
                }
            }
        }, revealMs);
    }

    /** 答完一题后推进：本组没完就下一题，到组尾按玩法判定是否通关 */
    private void advance() {
        if (index + 1 < items.size()) {
            show(index + 1);
            return;
        }
        if ("round".equals(playMode)) {
            double ratio = groupAnswered > 0 ? (double) groupCorrect / groupAnswered : 0d;
            if (ratio + 1e-9 >= passRatio) {
                pass("这组通过，可以继续看电视啦");
            } else {
                rebuild("正确率没达标，再来一组");
            }
            return;
        }
        // streak / count / practice：换一组继续，累计进度不清零
        rebuild("");
    }

    private void rebuild(String msg) {
        buildSession();   // 重新随机抽一组，尽量不重复刚才这套题
        show(0);
        feedback(theme.text2, msg);
    }

    /** 揭示答案：正确项标绿并加文字，选错项标红，其余变暗，同时锁住点击 */
    private void revealAnswer(Item it, int choice) {
        Button correctView = null;
        for (int k = 0; k < optionBox.getChildCount() && k < it.options.size(); k++) {
            View v = optionBox.getChildAt(k);
            if (!(v instanceof Button)) {
                continue;
            }
            Button b = (Button) v;
            if (k == it.answer) {
                setOptionText(b, k, it.options.get(k), "    ← 正确答案", "ok");
                correctView = b;
            } else if (k == choice) {
                setOptionText(b, k, it.options.get(k), "    ← 你选的", "bad");
            } else {
                setOptionText(b, k, it.options.get(k), null, "dim");
            }
            b.setOnClickListener(null);
            b.setClickable(false);
        }
        if (correctView != null) {
            correctView.requestFocus();
        }
    }

    private void feedback(int color, String text) {
        feedbackRole = color == theme.ok ? "ok"
                : (color == theme.bad ? "bad"
                : (color == theme.warn ? "warn" : "neutral"));
        tvFeedback.setTextColor(color);
        tvFeedback.setText(text);
        // 未答题时反馈卡片不占位，四个短答案选项能同屏显示完整
        tvFeedback.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        // 反馈条出现会挤压可视区，保证当前选项（尤其是最后一个）不被裁掉
        if (tvFeedback.getVisibility() == View.VISIBLE) {
            View focus = getCurrentFocus();
            if (focus == null && optionBox.getChildCount() > 0) {
                focus = optionBox.getChildAt(optionBox.getChildCount() - 1);
            }
            ensureVisible(focus);
        }
    }

    private void pass() {
        pass("全部答对，可以继续看电视啦");
    }

    private void pass(String msg) {
        if (passed) {
            return;
        }
        passed = true;
        running = false;
        long now = System.currentTimeMillis();
        Prefs.setPending(false);
        Prefs.setLastPassAt(now);
        long raw = now + Prefs.intervalMinutes() * 60000L;
        Prefs.setDueAtAlignedTo(raw, Rules.alignDue(this, raw));
        try {
            startService(new Intent(this, GuardService.class).setAction(GuardService.ACTION_QUIZ_PASSED));
        } catch (Throwable ignored) {
        }
        feedback(theme.ok, msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        // 通关即收工：关掉界面退回电视/桌面，守护服务继续在后台计时
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, PASS_MS);
    }

    // ------------------------------------------------------------------ 家长管理

    private void askManagePin() {
        long remain = Prefs.pinLockRemainMs();
        if (remain > 0) {
            feedback(theme.warn, "密码输错次数过多，请 "
                    + UninstallCode.describeLock(remain) + "后再试");
            return;
        }
        new PinDialog(this, "请输入管理密码", new PinDialog.Verifier() {
            @Override
            public String check(String in) {
                if (in == null || in.length() == 0) {
                    return "请输入管理密码";
                }
                long r = Prefs.pinLockRemainMs();
                if (r > 0) {
                    return "已锁定，" + UninstallCode.describeLock(r) + "后再试";
                }
                if (Prefs.verifyPin(in)) {
                    return null;
                }
                long lock = Prefs.pinLockRemainMs();
                if (lock > 0) {
                    return "错误次数过多，" + UninstallCode.describeLock(lock) + "后再试";
                }
                return "管理密码错误（默认 1234）";
            }
        }, new PinDialog.Listener() {
            @Override
            public void onResult(boolean ok) {
                if (ok) {
                    openManagement();
                }
            }
        }).show();
    }

    /** 打开只读信息页（二维码）。答题页留在下面，关掉信息页即回到答题。 */
    void openManagement() {
        managing = true;
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_INFO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) {
            managing = false;
            feedback(theme.warn, "打不开管理页，请重试");
        }
    }

    /**
     * 家长在手机后台点「立即解锁」：关掉当前答题弹窗，让孩子回到电视节目。
     *
     * <p>必须把 passed 置 true 并清空 handler 回调，否则 onPause() 里的 relaunch
     * 会在 1.2 秒后把答题页重新拉回前台，解锁等于没做。HTTP 线程调用，切回主线程收尾。
     */
    public static void forceUnlock() {
        final QuizActivity a = active;
        if (a == null) {
            return;
        }
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (a.isFinishing()) {
                    return;
                }
                a.passed = true;
                a.managing = false;
                a.handler.removeCallbacksAndMessages(null);
                a.finish();
            }
        });
    }

    /** 题库为空时的兜底：不能把电视彻底锁死 */
    private void failOpen() {
        Prefs.setPending(false);
        long raw = System.currentTimeMillis() + Prefs.intervalMinutes() * 60000L;
        Prefs.setDueAtAlignedTo(raw, Rules.alignDue(this, raw));
        try {
            startService(new Intent(this, GuardService.class).setAction(GuardService.ACTION_QUIZ_PASSED));
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "题库为空，请用手机扫码上传题目", Toast.LENGTH_LONG).show();
        finish();
    }

    // ------------------------------------------------------------------ 按键

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if ("practice".equals(playMode) && !choosingGrade && !passed) {
                pass("练习结束，可以继续看电视啦");
            } else {
                feedback(theme.warn, "答完题才能继续看电视哦");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 隐藏手势必须在 dispatchKeyEvent 里截：方向键会先被焦点上的选项按钮吃掉，
     * 到不了 onKeyDown。这里只「旁听」上键，前 4 次照样放行让方向键正常导航。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                // 长按上键会产生 repeatCount>0 的重复事件，不计入，避免孩子按住不放误触
                if (event.getRepeatCount() == 0 && hitUp()) {
                    return true;    // 第 5 次吞掉，免得顺手把焦点也移走
                }
            } else {
                upHits = 0;         // 中间按了别的键就重新数
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 计一次「上键」。返回 true 表示刚好凑满 5 次，此时已弹出管理密码键盘。
     * 两次间隔超过 {@link #GESTURE_GAP_MS} 就从头数，所以孩子正常上下选题不会凑数。
     */
    private boolean hitUp() {
        long now = SystemClock.uptimeMillis();
        if (now - lastUpAt > GESTURE_GAP_MS) {
            upHits = 0;
        }
        lastUpAt = now;
        upHits++;
        if (upHits < GESTURE_UP_COUNT) {
            return false;
        }
        upHits = 0;
        askManagePin();
        return true;
    }

    @Override
    public void onBackPressed() {
        if ("practice".equals(playMode) && !choosingGrade && !passed) {
            pass("练习结束，可以继续看电视啦");
        } else {
            feedback(theme.warn, "答完题才能继续看电视哦");
        }
    }
}
