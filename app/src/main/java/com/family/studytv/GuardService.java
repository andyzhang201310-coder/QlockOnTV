package com.family.studytv;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 守护服务：前台常驻，负责计时、到点拉起答题界面、维护局域网后台。
 * 采用「AlarmManager 精确闹钟 + 15 秒轮询」双保险，盒子休眠/被限制后台也能按时弹。
 */
public class GuardService extends Service {

    public static final String ACTION_TICK = "com.family.studytv.action.TICK";
    public static final String ACTION_QUIZ_PASSED = "com.family.studytv.action.QUIZ_PASSED";
    public static final String ACTION_SHOW_NOW = "com.family.studytv.action.SHOW_NOW";
    public static final String ACTION_RELOAD = "com.family.studytv.action.RELOAD";
    public static final String ACTION_RESCHEDULE = "com.family.studytv.action.RESCHEDULE";
    public static final String EXTRA_BOOT = "boot";

    private static final String CHANNEL_ID = "guard";
    private static final int NOTI_ID = 1001;
    private static final long TICK_MS = 15000L;
    private static final int REQ_ALARM = 100;
    private static final int REQ_SHOW = 101;

    private static GuardService alive;

    private Handler handler;
    private HttpServer server;
    private volatile boolean quizShowing = false;
    private long lastShowAt = 0L;
    /** 家长最近一次「立即解锁」的时间，用于避免启动自检把主动放行误判成 BAL 拦截 */
    private long unlockedAt = 0L;

    public static GuardService get() {
        return alive;
    }

    /**
     * 是否拿到了「显示在其他应用上层」权限。
     *
     * <p>Android 10 起后台启动 Activity 受限制，Android 14/15 收紧到连 targetSdk&lt;29 也拦；
     * 实测 Android 15 上连 PendingIntent opt-in 都还是 BAL_BLOCK，唯一稳定放行条件就是
     * 拥有非应用可见窗口（即本权限）。老系统（&lt;23）没有这个权限概念，直接视为可用。
     */
    public static boolean canDrawOverlays(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable e) {
            return false;
        }
    }

    public static void start(Context c) {
        start(c, null);
    }

    public static void start(Context c, Intent intent) {
        try {
            Intent i = intent != null ? new Intent(intent) : new Intent(c, GuardService.class);
            i.setClass(c, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        alive = this;
        handler = new Handler(Looper.getMainLooper());
        createChannels();
        try {
            startForeground(NOTI_ID, buildNotification());
        } catch (Throwable ignored) {
        }
        try {
            QuestionBank.get().ensureSeeded();
        } catch (Throwable ignored) {
        }
        try {
            // 每次服务启动（开机 / 更新后）都把系统卸载锁重新对一遍
            DeviceGuard.sync(this);
        } catch (Throwable ignored) {
        }
        startHttpServer();
        handler.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        boolean boot = intent != null && intent.getBooleanExtra(EXTRA_BOOT, false);

        if (ACTION_QUIZ_PASSED.equals(action)) {
            onQuizPassed();
        } else if (ACTION_SHOW_NOW.equals(action)) {
            quizShowing = false;
            showQuiz();
        } else if (ACTION_RELOAD.equals(action)) {
            startHttpServer();
            updateNotification();
        } else if (ACTION_RESCHEDULE.equals(action)) {
            Prefs.setDueAtAligned(System.currentTimeMillis() + intervalMs());
            scheduleAlarm(this);
            updateNotification();
        } else if (ACTION_TICK.equals(action)) {
            checkDue();
        } else {
            bootstrap(boot);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = null;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        stopHttpServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 启动逻辑

    private void bootstrap(boolean fromBoot) {
        long now = System.currentTimeMillis();

        // 总开关关掉：一个闹钟都不留，彻底不唤醒待机中的电视
        if (!Prefs.isEnabled() || !Prefs.autoQuiz()) {
            scheduleAlarm(this);
            updateNotification();
            return;
        }

        boolean quiet = Prefs.inQuietNow();

        // 1) 上次弹窗没答完（含直接关机逃避）→ 立刻补上；免打扰期间顺延到时段结束
        if (Prefs.isPending()) {
            if (quiet) {
                Prefs.setDueAtAligned(now);
                scheduleAlarm(this);
                updateNotification();
                return;
            }
            showSoon(2000);
            return;
        }

        // 2) 首次运行：从当前时间起算
        if (Prefs.dueAt() <= 0) {
            Prefs.setDueAtAligned(now + intervalMs());
            scheduleAlarm(this);
            updateNotification();
            return;
        }

        // 3) 开机后立即答题（防止靠关机逃避）
        if (fromBoot && Prefs.bootQuiz()) {
            if (quiet) {
                Prefs.setDueAtAligned(now);
                scheduleAlarm(this);
                updateNotification();
                return;
            }
            showSoon(3000);
            return;
        }

        // 4) 已经到点（关机期间欠下的）
        if (Prefs.dueAt() <= now) {
            if (quiet) {
                Prefs.setDueAtAligned(now);
                scheduleAlarm(this);
                updateNotification();
                return;
            }
            showSoon(1500);
            return;
        }

        scheduleAlarm(this);
        updateNotification();
    }

    private void showSoon(final long delay) {
        if (handler == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!QuizActivity.running) {
                    showQuiz();
                }
            }
        }, delay);
    }

    // ------------------------------------------------------------------ 计时

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                checkDue();
            } catch (Throwable ignored) {
            }
            if (handler != null) {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private long intervalMs() {
        return Prefs.intervalMinutes() * 60000L;
    }

    private void checkDue() {
        if (!Prefs.isEnabled() || !Prefs.autoQuiz()) {
            return;
        }
        // 免打扰时段：绝不弹窗（也就不会点亮待机中的电视），到点欠着的留到时段结束再补
        if (Prefs.inQuietNow()) {
            return;
        }
        long now = System.currentTimeMillis();
        // quizShowing 卡住（界面没成功拉起）时，60 秒后重试
        if (QuizActivity.running) {
            return;
        }
        if (quizShowing && now - lastShowAt < 60000L) {
            return;
        }
        long due = Prefs.dueAt();
        if (due <= 0) {
            Prefs.setDueAtAligned(now + intervalMs());
            scheduleAlarm(this);
            updateNotification();
            return;
        }
        if (now >= due) {
            showQuiz();
        }
    }

    private void showQuiz() {
        if (QuizActivity.running) {
            return;
        }
        quizShowing = true;
        lastShowAt = System.currentTimeMillis();
        Prefs.setPending(true);
        Intent i = new Intent(this, QuizActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if (!launchViaPendingIntent(i)) {
            try {
                startActivity(i);
            } catch (Throwable e) {
                // 极少数盒子拦截后台启动，用全屏通知兜底
                postFullScreenFallback();
            }
        }
        verifyLaunched();
        updateNotification();
    }

    /**
     * Android 10 起后台启动 Activity 受 BAL 限制，Android 14/15 收紧到连 targetSdk&lt;29 也拦
     * （实测 Android 15 上 startActivity 被静默 abort，不抛异常）。
     * API 34+ 用 PendingIntent + ActivityOptions 显式 opt-in 是官方给的合法通道；
     * 低版本没有这个限制，继续走 startActivity。
     */
    private boolean launchViaPendingIntent(Intent i) {
        if (Build.VERSION.SDK_INT < 34) {
            return false;
        }
        try {
            PendingIntent pi = PendingIntent.getActivity(this, REQ_SHOW, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            pi.send(this, 0, null, null, null, null, opts.toBundle());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 启动后自检：答题页 2.5 秒还没 onCreate，说明被系统拦了。
     * 记下时间给信息页提示家长，同时发全屏通知兜底（锁屏时能直接拉起）。
     */
    private void verifyLaunched() {
        if (handler == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 家长刚点了「立即解锁」，界面消失是预期行为，不要写成 BAL_BLOCK 或补发全屏通知
                if (System.currentTimeMillis() - unlockedAt < 5000L) {
                    return;
                }
                if (QuizActivity.running) {
                    Prefs.setBalBlockedAt(0L);
                    return;
                }
                Prefs.setBalBlockedAt(System.currentTimeMillis());
                postFullScreenFallback();
            }
        }, 2500);
    }

    private void onQuizPassed() {
        quizShowing = false;
        Prefs.setPending(false);
        long now = System.currentTimeMillis();
        Prefs.setLastPassAt(now);
        Prefs.setDueAtAligned(now + intervalMs());
        scheduleAlarm(this);
        updateNotification();
    }

    /**
     * 家长在手机后台点「立即解锁」：退出电视上正在显示的答题弹窗，让孩子能正常看电视。
     *
     * <p>只把 Activity finish 掉是不够的：到点后 checkDue() 会在下一个 15 秒轮询里
     * 再把答题页拉回来。所以必须同时清掉「本轮未完成」标记，并从现在重排下一轮闹钟，
     * 语义上等价于「本轮由家长放行，下一轮重新计时」。不写 lastPassAt，因为这不算答对通关。
     *
     * <p>写成静态方法而不是只发 Service Action，是为了后台线程能同步生效：
     * 即使服务刚被系统回收，SharedPreferences 和闹钟也已经落盘/重排。
     */
    public static void applyUnlock(Context c) {
        long now = System.currentTimeMillis();
        Prefs.setPending(false);
        Prefs.setDueAtAligned(now + Prefs.intervalMinutes() * 60000L);
        scheduleAlarm(c.getApplicationContext());

        GuardService s = alive;
        if (s != null) {
            s.quizShowing = false;
            s.lastShowAt = now;
            s.unlockedAt = now;
            try {
                s.updateNotification();
            } catch (Throwable ignored) {
            }
        }
        QuizActivity.forceUnlock();
    }

    private static void scheduleAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = PendingIntent.getBroadcast(c, REQ_ALARM,
                new Intent(c, AlarmReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.cancel(pi);
        } catch (Throwable ignored) {
        }

        // 自动弹窗关掉 / 整体暂停：一个闹钟都不留，别把待机中的电视叫醒
        if (!Prefs.isEnabled() || !Prefs.autoQuiz()) {
            return;
        }

        long raw = Prefs.dueAt();
        if (raw <= 0) {
            return;
        }
        long fireAt = Prefs.nextAllowedAt(raw);
        // dueAt 已经顺延过（等于免打扰结束时刻）时也算：那一刻同样不能唤醒待机设备
        boolean quietPushed = Prefs.duePushed() || fireAt != raw;

        try {
            if (quietPushed) {
                // 免打扰顺延后的这次触发不能用 WAKEUP：清晨待机的电视照样会被点亮。
                // 用非唤醒闹钟，设备醒着就正常触发，睡着就等下次开机由轮询补上。
                am.setExact(AlarmManager.RTC, fireAt, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
            } else if (Build.VERSION.SDK_INT >= 19) {
                am.setExact(AlarmManager.RTC_WAKEUP, fireAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, fireAt, pi);
            }
        } catch (Throwable e) {
            try {
                am.set(AlarmManager.RTC, fireAt, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 通知

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel guard = new NotificationChannel(CHANNEL_ID, "题锁运行状态",
                    NotificationManager.IMPORTANCE_LOW);
            guard.setShowBadge(false);
            nm.createNotificationChannel(guard);

            NotificationChannel quiz = new NotificationChannel("quiz", "答题提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            quiz.setShowBadge(true);
            nm.createNotificationChannel(quiz);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        String text;
        if (!Prefs.isEnabled()) {
            text = "已暂停拦截";
        } else if (!Prefs.autoQuiz()) {
            text = "自动弹题已关闭（可手动催一次）";
        } else {
            long due = Prefs.dueAt();
            String when = due > 0 ? new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(due)) : "--:--";
            text = "下次答题 " + when + " · 每 " + Prefs.intervalMinutes() + " 分钟";
            if (Prefs.inQuietNow()) {
                text = "免打扰中，下次 " + when;
            }
        }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("题锁运行中")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setCategory(Notification.CATEGORY_SERVICE);
        }
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTI_ID, buildNotification());
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void postFullScreenFallback() {
        try {
            Intent i = new Intent(this, QuizActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 9, i, PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, "quiz");
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("该答题啦")
                    .setContentText("答对题目后继续看电视")
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(1002, b.build());
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 局域网后台

    private void startHttpServer() {
        stopHttpServer();
        try {
            server = new HttpServer(this, Prefs.port());
            server.start();
        } catch (Throwable e) {
            server = null;
        }
    }

    private void stopHttpServer() {
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable ignored) {
            }
            server = null;
        }
    }

    public String serverError() {
        return server == null ? "未启动" : server.error();
    }
}
