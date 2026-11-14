package com.family.studytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 每日轮数用完的锁屏页（v1.8.1）。
 *
 * <p>家长在规则里打开「启用每日轮数上限」后，当天弹满上限轮数就不再是「悄悄不弹」，
 * 而是把电视整屏锁在这一页：告诉孩子今天的次数用完了、明天 0 点恢复。
 * 到点唤醒、开电视、点图标进来都会撞上这一页。
 *
 * <p>与答题页共享三件事：
 * <ul>
 *   <li>待机闸门：电视待机被系统拉起时直接 finish，绝不点亮面板；</li>
 *   <li>离屏拉回：孩子按 HOME 逃到桌面，1.2 秒后拉回（家长操作期间不拉）；</li>
 *   <li>隐藏入口：连按 5 次「上键」弹管理密码键盘，输对即家长放行并进设置页。</li>
 * </ul>
 */
public class LimitActivity extends Activity {

    /** 供守护服务判断锁屏页实例是否还在（同一进程静态标记，不代表在屏幕上） */
    public static volatile boolean running = false;

    /** 锁屏页是否真的在前台可见；按 HOME 被压后台时 running 仍为 true */
    public static volatile boolean foreground = false;

    /** 守护服务可否把被切到后台的锁屏页拉回前台（家长操作 / 已放行时为 false） */
    public static volatile boolean allowBringBack = false;

    /** 当前显示中的锁屏页，供手机后台「立即解锁」远程关闭 */
    private static volatile LimitActivity active = null;

    /** 电视待机时被拉起来：onCreate 里直接退出，后面的生命周期不再碰控件 */
    private boolean bailed = false;

    /** 家长正在看管理页（或刚输对密码）：此时不要再把自己拉回前台 */
    private boolean managing = false;

    /** 已经放行 / 已结束，不再重绘也不再拉回 */
    private boolean released = false;

    private static final int GESTURE_UP_COUNT = 5;
    private static final long GESTURE_GAP_MS = 800;
    private static final long RELAUNCH_MS = 1200;
    /** HOME 后系统 app-switch 保护会静默丢弃启动请求，失败后每 2 秒重试 */
    private static final long RELAUNCH_RETRY_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView tvFootnote;

    private int upHits = 0;
    private long lastUpAt = 0;

    /**
     * 孩子按 HOME 逃到桌面时把自己拉回来；电视睡着了就等下次唤醒。
     * v1.10.2：连按主菜单时系统的 app-switch 保护会静默丢弃后台切前台请求，
     * 单次尝试收不到异常，必须持续重试直到 onResume 真正把回调撤掉。
     */
    private final Runnable relaunch = new Runnable() {
        @Override
        public void run() {
            if (!allowBringBack || foreground || released || managing || isFinishing()) {
                return;
            }
            if (!DeviceAwake.isAwake(LimitActivity.this)) {
                handler.removeCallbacks(relaunch);
                handler.postDelayed(relaunch, RELAUNCH_RETRY_MS * 3);
                return;
            }
            GuardService.ensureVisible(LimitActivity.this, true);
            handler.removeCallbacks(relaunch);
            handler.postDelayed(relaunch, RELAUNCH_RETRY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!DeviceAwake.isAwake(this)) {
            bailed = true;
            foreground = false;
            allowBringBack = false;
            finish();
            return;
        }
        // 与答题页一致：不加 FLAG_TURN_SCREEN_ON，走到这里说明屏幕本来就亮着
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_limit);
        hideSystemUi();

        tvTitle = (TextView) findViewById(R.id.tvLimitTitle);
        tvSubtitle = (TextView) findViewById(R.id.tvLimitSubtitle);
        tvFootnote = (TextView) findViewById(R.id.tvLimitFootnote);

        running = true;
        active = this;
        GuardService.start(this);
        renderLimit();
    }

    /** 按当前规则把三段文案填回去；规则被家长改过也只会变数字，不会出现空屏 */
    private void renderLimit() {
        JSONObject cfg = Rules.get(this);
        if (tvTitle != null) {
            tvTitle.setText("今天的次数已用完");
        }
        if (tvSubtitle != null) {
            tvSubtitle.setText(Rules.limitResetText());
        }
        if (tvFootnote != null) {
            tvFootnote.setText(Rules.limitFootnote(this, cfg));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bailed) {
            return;
        }
        foreground = true;
        allowBringBack = false;
        managing = false;
        upHits = 0;
        handler.removeCallbacks(relaunch);
        hideSystemUi();
        renderLimit();
        // 家长在期间把上限调高或关掉上限：这一页已经没有意义，立刻退出
        if (!Rules.limitLocked(this)) {
            released = true;
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        allowBringBack = !released && !managing && !isFinishing();
        handler.removeCallbacks(relaunch);
        if (allowBringBack) {
            handler.postDelayed(relaunch, RELAUNCH_MS);
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        foreground = false;
        allowBringBack = false;
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

    // ------------------------------------------------------------------ 家长放行

    /**
     * 家长在手机后台点「立即解锁」：关掉锁屏页并记下「今天已放行」，今天不再锁屏。
     * HTTP 线程调用，切回主线程收尾。
     */
    public static void forceUnlock() {
        final LimitActivity a = active;
        if (a == null) {
            return;
        }
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (a.isFinishing()) {
                    return;
                }
                a.released = true;
                a.managing = false;
                allowBringBack = false;
                a.handler.removeCallbacksAndMessages(null);
                a.finish();
            }
        });
    }

    /** 隐藏入口：连按 5 次上键 → 管理密码 → 今天放行 + 打开设置页 */
    private void askManagePin() {
        new PinDialog(this, new PinDialog.Listener() {
            @Override
            public void onResult(boolean ok) {
                if (!ok) {
                    return;
                }
                // 走与手机后台「立即解锁」完全相同的路径：记「今天已放行」+ 从现在重排下一轮。
                // 只记放行不重排的话，欠着的那一轮会在 15 秒后立刻弹出来，家长一放行就撞题。
                GuardService.applyUnlock(LimitActivity.this);
                released = true;
                managing = true;
                handler.removeCallbacks(relaunch);
                try {
                    startActivity(new Intent(LimitActivity.this, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_INFO, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable ignored) {
                }
                finish();
            }
        }).show();
    }

    // ------------------------------------------------------------------ 按键

    /** 锁屏页没有任何出口：返回键吞掉，避免孩子一次次按出桌面 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // 同 onKeyDown：不退出
    }

    /**
     * 隐藏手势必须在 dispatchKeyEvent 里截，理由与答题页相同：
     * 方向键会先被焦点吃掉。这里只「旁听」上键，前 4 次照常放行。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getRepeatCount() == 0 && hitUp()) {
                    return true;
                }
            } else {
                upHits = 0;
            }
        }
        return super.dispatchKeyEvent(event);
    }

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
}
