package com.family.studytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

    /** 答错后停留多久再换新题（毫秒），留足看正确答案的时间 */
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

    /** 隐藏手势的计数状态 */
    private int upHits = 0;
    private long lastUpAt = 0;

    private TextView tvProgress;
    private TextView tvSubject;
    private TextView tvQuestion;
    private TextView tvFeedback;
    private LinearLayout optionBox;
    private ScrollView quizScroll;

    /** 小孩按 HOME 逃到桌面时把自己拉回来 */
    private final Runnable relaunch = new Runnable() {
        @Override
        public void run() {
            if (passed || managing || isFinishing()) {
                return;
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
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
            if (pm == null) {
                return true;
            }
            if (android.os.Build.VERSION.SDK_INT >= 20) {
                return pm.isInteractive();
            }
            return pm.isScreenOn();
        } catch (Throwable e) {
            return true;
        }
    }

    private static final class Item {
        QuestionBank.Question q;
        List<String> options;
        int answer;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_quiz);
        hideSystemUi();

        tvProgress = (TextView) findViewById(R.id.tvProgress);
        tvSubject = (TextView) findViewById(R.id.tvSubject);
        tvQuestion = (TextView) findViewById(R.id.tvQuestion);
        tvFeedback = (TextView) findViewById(R.id.tvFeedback);
        optionBox = (LinearLayout) findViewById(R.id.optionBox);
        quizScroll = (ScrollView) findViewById(R.id.quizScroll);

        running = true;
        active = this;
        // 任何进入答题的路径都确保守护服务（含局域网后台）在跑
        GuardService.start(this);
        Prefs.setPending(true);
        buildSession();
        show(0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从管理页回来，恢复「离开就拉回」的约束
        managing = false;
        upHits = 0;
        handler.removeCallbacks(relaunch);
        hideSystemUi();
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
        List<QuestionBank.Question> picked;
        try {
            // usedIds 是上一组题，尽量避开，保证「换一组新题」不是原题重放
            picked = QuestionBank.get().pick(Prefs.quizCount(), usedIds);
        } catch (Throwable e) {
            picked = new ArrayList<QuestionBank.Question>();
        }
        items.clear();
        usedIds.clear();
        Random rnd = new Random();
        for (QuestionBank.Question q : picked) {
            Item it = new Item();
            it.q = q;
            List<String> opts = new ArrayList<String>(q.options);
            String correct = opts.get(q.answer);
            Collections.shuffle(opts, rnd);          // 每次出题打乱选项，避免死记位置
            it.options = opts;
            it.answer = opts.indexOf(correct);
            items.add(it);
            usedIds.add(q.id);
        }
    }

    private static String letter(int k) {
        return k < LETTERS.length ? LETTERS[k] : String.valueOf(k + 1);
    }

    private void show(final int i) {
        if (items.isEmpty()) {
            failOpen();
            return;
        }
        index = i;
        final Item it = items.get(i);
        tvProgress.setText("第 " + (i + 1) + " / " + items.size() + " 题");
        tvSubject.setText(it.q.subject);
        tvQuestion.setText(it.q.q);

        optionBox.removeAllViews();
        for (int k = 0; k < it.options.size(); k++) {
            Button b = (Button) getLayoutInflater().inflate(R.layout.item_option, optionBox, false);
            b.setText(optionText(b, k, it.options.get(k), null));
            final int choice = k;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    answer(choice);
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
     * 换行后的行首与 A/B/C 标号后的正文对齐（悬挂缩进），长成语解释不会顶回行首。
     * 按钮高度由 wrap_content 自行决定，文本不做任何截断。
     */
    static CharSequence optionText(Button b, int k, String text, String suffix) {
        String label = letter(k) + "    ";
        SpannableString s = new SpannableString(label + text + (suffix == null ? "" : suffix));
        int indent = (int) b.getPaint().measureText(label);
        s.setSpan(new LeadingMarginSpan.Standard(0, indent), 0, s.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return s;
    }

    /** 换题后回到顶部，避免上一题滚动位置残留导致看不到题干 */
    private void scrollToTop() {
        if (quizScroll == null) {
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
        if (busy || passed || index >= items.size()) {
            return;
        }
        Item it = items.get(index);
        if (choice == it.answer) {
            int next = index + 1;
            if (next >= items.size()) {
                pass();
            } else {
                feedback(0xFF4ADE80, "答对了！继续下一题");
                show(next);
            }
        } else {
            busy = true;
            revealAnswer(it, choice);
            feedback(0xFFFF6B6B, "答错了！正确答案是 " + letter(it.answer)
                    + "、" + it.options.get(it.answer) + "，看清后换一组新题");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    busy = false;
                    buildSession();   // 重新随机抽一组，不重复刚才这套题
                    show(0);
                    feedback(0xFF8FA0B8, "新的一组题，答对 5 道才能继续看电视");
                }
            }, REVEAL_MS);
        }
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
                b.setText(optionText(b, k, it.options.get(k), "    ← 正确答案"));
                b.setTextColor(0xFF4ADE80);
                correctView = b;
            } else if (k == choice) {
                b.setText(optionText(b, k, it.options.get(k), "    ← 你选的"));
                b.setTextColor(0xFFFF6B6B);
            } else {
                b.setText(optionText(b, k, it.options.get(k), null));
                b.setTextColor(0xFF6B7688);
            }
            b.setOnClickListener(null);
            b.setClickable(false);
        }
        if (correctView != null) {
            correctView.requestFocus();
        }
    }

    private void feedback(int color, String text) {
        tvFeedback.setTextColor(color);
        tvFeedback.setText(text);
    }

    private void pass() {
        passed = true;
        running = false;
        long now = System.currentTimeMillis();
        Prefs.setPending(false);
        Prefs.setLastPassAt(now);
        Prefs.setDueAt(now + Prefs.intervalMinutes() * 60000L);
        try {
            startService(new Intent(this, GuardService.class).setAction(GuardService.ACTION_QUIZ_PASSED));
        } catch (Throwable ignored) {
        }
        feedback(0xFF4ADE80, "全部答对，可以继续看电视啦");
        Toast.makeText(this, "全部答对！", Toast.LENGTH_SHORT).show();
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
            feedback(0xFFFFC857, "密码输错次数过多，请 "
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
            feedback(0xFFFFC857, "打不开管理页，请重试");
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
        Prefs.setDueAt(System.currentTimeMillis() + Prefs.intervalMinutes() * 60000L);
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
            feedback(0xFFFFC857, "答完题才能继续看电视哦");
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
        feedback(0xFFFFC857, "答完题才能继续看电视哦");
    }
}
