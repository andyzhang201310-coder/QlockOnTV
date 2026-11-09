package com.family.studytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 电视端只读信息页（二维码 + 运行状态），平时不出现。
 *
 * <p>只在两种情况下显示：① 首次安装后第一次打开应用，给家长看一次怎么配；
 * ② 家长在答题页连按 5 次「上键」并输对管理密码。其余任何路径都进不来。
 * 本页只有二维码和状态，所有参数修改一律在手机管理页完成。
 */
public class MainActivity extends Activity {

    public static final String EXTRA_INFO = "info_page";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView ivQr;
    private TextView tvUrl;
    private TextView tvStatus;
    private TextView tvWarn;
    /** 本次是「首次安装」的引导页，关闭按钮变成「开始答题」 */
    private boolean firstRun = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 首次安装后开机前不会触发 BOOT_COMPLETED，这里保证服务与局域网后台已就绪
        GuardService.start(this);

        if (getIntent().getBooleanExtra(EXTRA_INFO, false)) {
            showInfo(false);
            return;
        }

        if (!Prefs.firstRunDone()) {
            // 第一次装上：先让家长扫码把密码、题库、间隔配好，只此一次
            Prefs.setFirstRunDone(true);
            showInfo(true);
            return;
        }

        // 之后打开应用 = 直接答题，看不到任何二维码与设置
        gotoQuiz();
    }

    private void showInfo(boolean first) {
        firstRun = first;
        setContentView(R.layout.activity_main);
        ivQr = (ImageView) findViewById(R.id.ivQr);
        tvUrl = (TextView) findViewById(R.id.tvUrl);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvWarn = (TextView) findViewById(R.id.tvWarn);

        if (Prefs.isEnabled()) {
            GuardService.start(this);
        }

        findViewById(R.id.btnSecurity).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SecurityActivity.class));
            }
        });
        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                render();
            }
        });
        findViewById(R.id.btnOverlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        });
        Button close = (Button) findViewById(R.id.btnClose);
        close.setText(firstRun ? "开始答题" : "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (firstRun) {
                    gotoQuiz();
                } else {
                    finish();
                }
            }
        });

        render();
    }

    /**
     * 跳系统页给本应用开「显示在其他应用上层」。
     * Android 10 起没有这个权限，到点弹窗会被系统静默拦掉（BAL），这是唯一稳定解法。
     */
    private void openOverlaySettings() {
        if (GuardService.canDrawOverlays(this)) {
            Toast.makeText(this, "后台弹窗权限已开启，到点可以直接弹出", Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable e2) {
                Toast.makeText(this, "这台设备没有悬浮窗设置页，请到系统设置里手动开启「显示在其他应用上层」",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void gotoQuiz() {
        try {
            // 答题页可能已经在任务里（家长看完二维码又点了桌面图标），复用它而不是叠一层新的
            startActivity(new Intent(this, QuizActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Throwable ignored) {
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tvStatus != null) {
            render();
        }
    }

    private void render() {
        final String url = LanUtils.url();
        tvUrl.setText(url);
        handler.post(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = QrUtil.encode(url, 720);
                if (bmp != null) {
                    ivQr.setImageBitmap(bmp);
                }
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("拦截状态：").append(Prefs.isEnabled() ? "拦截中（到点强制答题）" : "已暂停").append("\n");
        sb.append("弹题频率：每 ").append(Prefs.intervalMinutes()).append(" 分钟一次，每次 ")
                .append(Prefs.quizCount()).append(" 题\n");
        sb.append("出题年级：").append(gradeLabel()).append("\n");

        long due = Prefs.dueAt();
        if (Prefs.isEnabled() && due > 0) {
            long left = Math.max(0, due - System.currentTimeMillis());
            sb.append("下次弹题：")
                    .append(new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(due)))
                    .append("（约 ").append(left / 60000).append(" 分钟后）\n");
        }
        if (Prefs.isPending()) {
            sb.append("注意：上一次答题未完成，将立即弹出\n");
        }
        sb.append("题库存量：").append(QuestionBank.get().count()).append(" 题");
        Map<String, Integer> map = QuestionBank.get().countBySubject();
        if (!map.isEmpty()) {
            sb.append("（");
            boolean first = true;
            for (Map.Entry<String, Integer> e : map.entrySet()) {
                if (!first) {
                    sb.append(" / ");
                }
                sb.append(e.getKey()).append(" ").append(e.getValue());
                first = false;
            }
            sb.append("）");
        }
        sb.append("\n防卸载：").append(DeviceGuard.statusLine(this));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            sb.append("\n后台弹窗权限：").append(GuardService.canDrawOverlays(this)
                    ? "已允许" : "未允许（系统会拦下到点弹窗）");
        }
        tvStatus.setText(sb.toString());

        // 提示区只留「需要动手处理」的事项；引导步骤已移到左栏卡片，这里不再重复
        StringBuilder warn = new StringBuilder();
        if (android.os.Build.VERSION.SDK_INT >= 29 && !GuardService.canDrawOverlays(this)) {
            warn.append("· 后台弹窗权限未开：点「允许后台弹窗」授权，否则 Android 10+ 会拦下到点弹窗\n");
        }
        if (Prefs.balBlockedAt() > 0) {
            warn.append("· 上次到点弹窗被系统拦下，授权后即可恢复\n");
        }
        boolean pinDefault = "1234".equals(Prefs.pin());
        boolean uninstallDefault = !Prefs.hasCustomUninstallPin();
        if (pinDefault && uninstallDefault) {
            warn.append("· 管理密码 / 卸载码仍是默认值（1234 / 246810），请尽快改掉\n");
        } else if (pinDefault) {
            warn.append("· 管理密码仍是默认 1234，请尽快改掉\n");
        } else if (uninstallDefault) {
            warn.append("· 卸载码仍是默认 246810，请尽快改掉\n");
        }
        if (Prefs.uninstallGuard() && !DeviceGuard.isAdminActive(this)) {
            warn.append("· 防卸载未生效，点「防卸载设置」激活设备管理员\n");
        }
        if ("0.0.0.0".equals(LanUtils.ip())) {
            warn.append("· 电视未连上局域网，请先连 WiFi 或网线\n");
        }
        tvWarn.setText(warn.toString().trim());
        tvWarn.setVisibility(warn.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** 信息页用的年级文案，例如「3 年级」「1、2、3 年级」「1-6 年级全部」 */
    private String gradeLabel() {
        java.util.Set<Integer> gs = Prefs.gradeSet();
        if (gs.size() >= Prefs.GRADE_MAX - Prefs.GRADE_MIN + 1) {
            return "1-6 年级全部";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer g : gs) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(g);
        }
        return sb.append(" 年级").toString();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
