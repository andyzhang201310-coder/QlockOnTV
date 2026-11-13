package com.family.studytv;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

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
    /** 规则保存后只重排闹钟，不把计时起点重置为「现在」 */
    public static final String ACTION_RULE_CHANGED = "com.family.studytv.action.RULE_CHANGED";
    public static final String EXTRA_BOOT = "boot";

    private static final String CHANNEL_ID = "guard";
    private static final int NOTI_ID = 1001;
    private static final long TICK_MS = 15000L;
    private static final int REQ_ALARM = 100;
    private static final int REQ_SHOW = 101;
    private static final int REQ_LIMIT = 103;

    private static GuardService alive;

    private Handler handler;
    private HttpServer server;
    private volatile boolean quizShowing = false;
    private long lastShowAt = 0L;
    /** 每日轮数用完的锁屏页是否刚拉起过，避免 15 秒轮询反复重排界面 */
    private volatile boolean limitShowing = false;
    private long lastLimitAt = 0L;
    /** 家长最近一次「立即解锁」的时间，用于避免启动自检把主动放行误判成 BAL 拦截 */
    private long unlockedAt = 0L;
    private boolean wakeReceiverRegistered = false;

    /**
     * 电视被唤醒（屏幕亮起）时立刻补一次到点检查（v1.7.33）。
     * 只靠 15 秒轮询的话，孩子开电视后有最多 15 秒的空窗能正常看；既然待机期间不弹，
     * 醒来的第一时间就要把欠下的那一轮补上。
     */
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (handler == null) {
                return;
            }
            handler.removeCallbacks(wakeTick);
            handler.postDelayed(wakeTick, 1200);   // 等系统界面稳定，别刚亮屏就被盖住
        }
    };

    private final Runnable wakeTick = new Runnable() {
        @Override
        public void run() {
            checkDue();
        }
    };

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
        // 日历计划：每天最多一次异步同步网络节假日，断网时静默退回内置表。
        // 必须放子线程：HTTP 请求不能落在主线程，且不能拖慢前台服务启动。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = Rules.get(GuardService.this)
                            .optString("calendarUrl", CalendarPlan.DEFAULT_URL);
                    if (CalendarPlan.syncIfDue(GuardService.this, url)) {
                        scheduleAlarm(GuardService.this);
                        updateNotification();
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "calendar-sync").start();
        startHttpServer();
        registerWakeReceiver();
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
        } else if (ACTION_RULE_CHANGED.equals(action)) {
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
        unregisterWakeReceiver();
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

    // ------------------------------------------------------------------ 屏幕状态

    private void registerWakeReceiver() {
        if (wakeReceiverRegistered) {
            return;
        }
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(wakeReceiver, f);
            wakeReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private void unregisterWakeReceiver() {
        if (!wakeReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(wakeReceiver);
        } catch (Throwable ignored) {
        }
        wakeReceiverRegistered = false;
    }

    /** 电视待机时不能弹题；这条判定排在所有规则之前 */
    private static boolean awake(Context c) {
        return DeviceAwake.isAwake(c);
    }

    // ------------------------------------------------------------------ 规则时段

    /** 此刻是否落在规则允许的弹题时间：免打扰 / 周计划 / 网课 / 大人豁免统一判定 */
    private static boolean timeAllowed(Context c, long ts) {
        return Rules.quizAllowedAt(Rules.get(c), ts, Prefs.quietEnabled(),
                Prefs.quietStartMin(), Prefs.quietEndMin());
    }

    /** 把时刻顺延到规则允许的最近时刻；本来就允许则原样返回 */
    private static long alignedDue(Context c, long raw) {
        return Rules.alignDue(c, raw);
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

        // 规则里的「可用时段 / 网课 / 大人豁免」与旧的免打扰一起判，语义统一为一句话：
        // 此刻不允许弹题 → 一律顺延，绝不点亮待机中的电视。
        boolean quiet = !timeAllowed(this, now);

        // 0) 今天次数已用完（每日轮数上限）：不管在不在时段内，开电视就是锁屏页
        if (Rules.limitLocked(this)) {
            showSoon(1500);
            return;
        }

        // 1) 上次弹窗没答完（含直接关机逃避）→ 立刻补上；免打扰期间顺延到时段结束
        if (Prefs.isPending()) {
            if (quiet) {
                Prefs.setDueAtAlignedTo(now, alignedDue(this, now));
                scheduleAlarm(this);
                updateNotification();
                return;
            }
            showSoon(2000);
            return;
        }

        // 2) 首次运行：从当前时间起算
        if (Prefs.dueAt() <= 0) {
            long raw = now + intervalMs();
            Prefs.setDueAtAlignedTo(raw, alignedDue(this, raw));
            scheduleAlarm(this);
            updateNotification();
            return;
        }

        // 3) 开机后立即答题（防止靠关机逃避）
        if (fromBoot && Prefs.bootQuiz()) {
            if (quiet) {
                Prefs.setDueAtAlignedTo(now, alignedDue(this, now));
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
                Prefs.setDueAtAlignedTo(now, alignedDue(this, now));
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
        // ---- 最高优先级：电视待机 / 休眠时一律不弹（v1.7.33）----
        // 答题页带 FLAG_TURN_SCREEN_ON，一拉起就会点亮面板，晚上到点等于自己把电视叫醒。
        // 这里不弹、不计轮数、不动 dueAt、也不重排闹钟：欠下的那一轮留着，
        // 电视一唤醒（wakeReceiver）立刻补上，孩子不能靠关电视逃避。
        if (!awake(this)) {
            long asleepNow = System.currentTimeMillis();
            long asleepDue = Prefs.dueAt();
            // 到点却被待机挡下：记一笔给后台看，5 分钟内只记一次，别每 15 秒写盘
            if (asleepDue > 0 && asleepDue <= asleepNow
                    && asleepNow - Prefs.asleepDeferAt() > 300000L) {
                Prefs.setAsleepDeferAt(asleepNow);
            }
            return;
        }
        if (!Prefs.isEnabled() || !Prefs.autoQuiz()) {
            return;
        }
        // 家长已经输对卸载码、正在走系统卸载流程：这段时间绝不弹答题页，
        // 否则答题页会把系统卸载界面盖掉，家长根本点不到「确定」。
        if (Prefs.uninstallAllowUntil() > System.currentTimeMillis()) {
            return;
        }
        long now = System.currentTimeMillis();
        // quizShowing 卡住（界面没成功拉起）时，60 秒后重试
        if (QuizActivity.running || LimitActivity.running) {
            return;
        }
        if (quizShowing && now - lastShowAt < 60000L) {
            return;
        }
        if (limitShowing && now - lastLimitAt < 60000L) {
            return;
        }
        // 每日轮数用完（v1.8.1）：不再是「今天悄悄不弹」，而是整屏锁住。
        // 放在时段判断之前 —— 次数用完了就不该再挑时段，开电视一律撞锁屏页。
        if (Rules.limitLocked(this)) {
            showLimit();
            return;
        }
        // 规则禁用时段：绝不弹窗（也就不会点亮待机中的电视），到点欠着的留到允许时刻再补
        if (!timeAllowed(this, now)) {
            long due = Prefs.dueAt();
            if (due > 0 && due <= now) {
                long aligned = alignedDue(this, now);
                if (aligned > due) {
                    Prefs.setDueAtAlignedTo(due, aligned);
                    scheduleAlarm(this);
                    updateNotification();
                }
            }
            return;
        }
        // 今日弹题轮数用满：今天不再弹，跨天自动恢复（不改动 dueAt）
        if (!Rules.roundsLeft(this, Rules.get(this))) {
            return;
        }
        long due = Prefs.dueAt();
        if (due <= 0) {
            long raw = now + intervalMs();
            Prefs.setDueAtAlignedTo(raw, alignedDue(this, raw));
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
        // 兜底闸门：showSoon / 开机补题 / 后台「立即弹一次」都会走到这里，
        // 待机时一律不拉起答题页，避免把睡着的电视点亮。
        if (!awake(this)) {
            long t = System.currentTimeMillis();
            if (t - Prefs.asleepDeferAt() > 300000L) {
                Prefs.setAsleepDeferAt(t);
            }
            return;
        }
        // 今天次数已经用完：出题换成锁屏页（后台「立即弹一次」也照此处理）
        if (Rules.limitLocked(this)) {
            showLimit();
            return;
        }
        quizShowing = true;
        lastShowAt = System.currentTimeMillis();
        Prefs.setPending(true);
        Rules.noteRound(this);   // 每日轮数上限按「实际弹出」计数
        Intent i = new Intent(this, QuizActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if (!launchViaPendingIntent(i, REQ_SHOW)) {
            try {
                startActivity(i);
            } catch (Throwable e) {
                // 极少数盒子拦截后台启动，用全屏通知兜底
                postFullScreenFallback(false);
            }
        }
        verifyLaunched();
        updateNotification();
    }

    /**
     * 拉起「今天的次数已用完」锁屏页（v1.8.1）。
     * 与答题页共用同一套闸门：待机不拉、走 PendingIntent 的 BAL 放行通道、失败用全屏通知兜底。
     */
    private void showLimit() {
        if (LimitActivity.running) {
            return;
        }
        if (!awake(this)) {
            long t = System.currentTimeMillis();
            if (t - Prefs.asleepDeferAt() > 300000L) {
                Prefs.setAsleepDeferAt(t);
            }
            return;
        }
        limitShowing = true;
        lastLimitAt = System.currentTimeMillis();
        Intent i = new Intent(this, LimitActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if (!launchViaPendingIntent(i, REQ_LIMIT)) {
            try {
                startActivity(i);
            } catch (Throwable e) {
                postFullScreenFallback(true);
            }
        }
        updateNotification();
    }

    /**
     * Android 10 起后台启动 Activity 受 BAL 限制，Android 14/15 收紧到连 targetSdk&lt;29 也拦
     * （实测 Android 15 上 startActivity 被静默 abort，不抛异常）。
     * API 34+ 用 PendingIntent + ActivityOptions 显式 opt-in 是官方给的合法通道；
     * 低版本没有这个限制，继续走 startActivity。
     */
    private boolean launchViaPendingIntent(Intent i, int requestCode) {
        if (Build.VERSION.SDK_INT < 34) {
            return false;
        }
        try {
            PendingIntent pi = PendingIntent.getActivity(this, requestCode, i,
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
                // 电视在这 2.5 秒里睡着了（或本来就待机）：不算启动失败，别污染诊断
                if (!awake(GuardService.this)) {
                    return;
                }
                if (QuizActivity.running || LimitActivity.running) {
                    Prefs.setBalBlockedAt(0L);
                    return;
                }
                Prefs.setBalBlockedAt(System.currentTimeMillis());
                postFullScreenFallback(false);
            }
        }, 2500);
    }

    private void onQuizPassed() {
        quizShowing = false;
        Prefs.setPending(false);
        long now = System.currentTimeMillis();
        Prefs.setLastPassAt(now);
        long raw = now + intervalMs();
        Prefs.setDueAtAlignedTo(raw, alignedDue(this, raw));
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
        // v1.8.1：如果电视上正锁着「今天的次数已用完」，这次放行同时记「今天已放行」，
        // 否则 15 秒后轮询会把锁屏页又拉回来。
        Rules.grantLimitPass(c);
        long raw = now + Prefs.intervalMinutes() * 60000L;
        Prefs.setDueAtAlignedTo(raw, Rules.alignDue(c, raw));
        scheduleAlarm(c.getApplicationContext());

        GuardService s = alive;
        if (s != null) {
            s.quizShowing = false;
            s.limitShowing = false;
            s.lastShowAt = now;
            s.unlockedAt = now;
            try {
                s.updateNotification();
            } catch (Throwable ignored) {
            }
        }
        QuizActivity.forceUnlock();
        LimitActivity.forceUnlock();
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
        // 规则（免打扰 / 周计划 / 网课 / 豁免）统一顺延到最近的允许时刻
        long fireAt = alignedDue(c, raw);
        // dueAt 已经顺延过（等于禁用时段结束时刻）时也算：那一刻同样不能唤醒待机设备
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
        } else if (Rules.limitLocked(this)) {
            // 次数用完：通知栏也保持一致，家长一眼看出为什么电视停在这一页
            text = "今天的次数已用完，明天 0 点恢复";
        } else {
            long due = Prefs.dueAt();
            String when = due > 0 ? new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(due)) : "--:--";
            text = "下次答题 " + when + " · 每 " + Prefs.intervalMinutes() + " 分钟";
            if (!timeAllowed(this, System.currentTimeMillis())) {
                text = "规则停用中，下次 " + when;
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
    private void postFullScreenFallback(boolean limit) {
        // 全屏通知在部分机型上会把屏幕点亮，待机时干脆不发，靠唤醒后的补弹兜住
        if (!awake(this)) {
            return;
        }
        try {
            Intent i = limit ? new Intent(this, LimitActivity.class)
                    : new Intent(this, QuizActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, limit ? 10 : 9, i,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, "quiz");
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(limit ? "今天的次数已用完" : "该答题啦")
                    .setContentText(limit ? "明天 0 点恢复" : "答对题目后继续看电视")
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(limit ? 1003 : 1002, b.build());
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
