package com.family.studytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 防卸载设置。这里保留的是「必须在本机完成的动作」：
 * 激活设备管理员（系统弹窗）、卸载应用（要输入卸载码）。
 * 其余所有参数一律在手机管理页改，电视端不再提供。
 */
public class SecurityActivity extends Activity {

    private TextView tvGuard;
    private Button btnGuard;
    private Button btnA11y;
    /** 是否已经切到系统激活页（用来判断那个页面到底有没有真的起来） */
    private boolean leftForAdminPage;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onPause() {
        super.onPause();
        leftForAdminPage = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security);

        tvGuard = (TextView) findViewById(R.id.tvGuard);
        btnGuard = (Button) findViewById(R.id.btnGuard);
        btnA11y = (Button) findViewById(R.id.btnA11y);

        btnGuard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGuard();
            }
        });
        btnA11y.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openA11y();
            }
        });
        findViewById(R.id.btnOwner).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOwnerGuide();
            }
        });
        findViewById(R.id.btnUninstall).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUninstall();
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 已经从系统激活页回来了，那个「页面有没有起来」的兜底就不用再跑
        uiHandler.removeCallbacks(adminCheck);
        if (leftForAdminPage) {
            leftForAdminPage = false;
            if (Prefs.uninstallGuard() && !DeviceGuard.isAdminActive(this)) {
                // 用户点了取消，或者系统页什么都没做：别留一个「已开启但没生效」的假开关
                Prefs.setUninstallGuard(false);
                Toast.makeText(this, "还没有激活设备管理员，防卸载未开启", Toast.LENGTH_LONG).show();
            }
        }
        DeviceGuard.sync(this);
        refreshGuard();
    }

    private void refreshGuard() {
        boolean on = Prefs.uninstallGuard();
        boolean admin = DeviceGuard.isAdminActive(this);
        boolean owner = DeviceGuard.isDeviceOwner(this);
        boolean blocked = DeviceGuard.isUninstallBlocked(this);
        boolean a11y = DeviceGuard.isA11yEnabled(this);

        StringBuilder sb = new StringBuilder();
        sb.append("保护状态：").append(DeviceGuard.statusLine(this)).append("\n");
        sb.append("设备管理员：").append(admin ? "已激活" : "未激活（本机系统不支持）");
        if (owner) {
            sb.append("　设备所有者：已生效");
        } else if (admin) {
            sb.append("　设备所有者：未设置");
        }
        sb.append("\n");
        sb.append("界面拦截：").append(a11y ? "已开启" : "未开启");
        int blockedTimes = Prefs.a11yBlockCount();
        if (blockedTimes > 0) {
            sb.append("（已拦下 ").append(blockedTimes).append(" 次卸载）");
        }
        sb.append("\n");
        long allowLeft = Prefs.uninstallAllowUntil() - System.currentTimeMillis();
        if (allowLeft > 0) {
            sb.append("放行中：还剩 ").append(UninstallCode.describeLock(allowLeft))
                    .append("，可从系统里正常卸载\n");
        }
        if (owner) {
            sb.append("系统卸载锁：").append(blocked ? "已锁定" : "未锁定");
            sb.append("　恢复出厂：").append(Prefs.blockFactoryReset() && blocked ? "已禁止" : "允许");
            sb.append("\n");
        }
        sb.append("卸载码：").append(Prefs.hasCustomUninstallPin() ? "已自定义" : "仍是默认 246810，请到手机管理页修改");
        long remain = Prefs.uninstallLockRemainMs();
        if (remain > 0) {
            sb.append("\n输错锁定中：还需 ").append(UninstallCode.describeLock(remain));
        }
        long disabledAt = Prefs.adminDisabledAt();
        if (disabledAt > 0) {
            sb.append("\n注意：设备管理员曾于 ")
                    .append(new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(disabledAt)))
                    .append(" 被停用");
        }
        tvGuard.setText(sb.toString());
        btnGuard.setText(on ? "关闭防卸载" : "开启防卸载");
        btnA11y.setText(a11y ? "界面拦截已开启" : "开启界面拦截");
    }

    /**
     * 打开系统无障碍页让用户勾选本应用。电视厂商常把这一页裁掉，
     * 拉不起来时直接给 adb 命令，不让人在设置里瞎找。
     */
    private void openA11y() {
        if (DeviceGuard.isA11yEnabled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("界面拦截已开启")
                    .setMessage("没有卸载码时，从系统里点「卸载」会自动退回。\n\n"
                            + "要临时关掉，去 设置 → 无障碍 取消勾选「题锁」；"
                            + "也可以在下方复制命令用 adb 关闭。\n\n"
                            + "关闭命令：\n"
                            + "adb shell settings put secure accessibility_enabled 0")
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("复制启用命令", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            copyText("启用命令", DeviceGuard.adbA11yCommand(SecurityActivity.this));
                        }
                    })
                    .show();
            return;
        }
        if (!Prefs.uninstallGuard()) {
            Prefs.setUninstallGuard(true);
        }
        if (DeviceGuard.openA11ySettings(this)) {
            Toast.makeText(this, "在列表里找到「题锁」并打开开关", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("电视上没有无障碍设置页")
                .setMessage("Android TV 通常不做这一页，系统里只有一个占位入口。"
                        + "用电脑连一次电视，执行下面这条命令：\n\n"
                        + DeviceGuard.adbA11yCommand(this) + "\n\n"
                        + DeviceGuard.a11yRebootHint() + "\n\n"
                        + "连接方法：电视 设置 → 关于 → 连点「版本号」7 次 → 开发者选项 → 打开 USB 调试；"
                        + "电视和电脑同一个 WiFi，电脑执行 adb connect 电视IP:5555。\n\n"
                        + "命令给的是包名+类名，已经填好，直接复制粘贴即可。")
                .setPositiveButton("复制命令", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyText("启用命令", DeviceGuard.adbA11yCommand(SecurityActivity.this));
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private void toggleGuard() {
        if (Prefs.uninstallGuard()) {
            Prefs.setUninstallGuard(false);
            DeviceGuard.sync(this);
            refreshGuard();
            Toast.makeText(this, "已关闭，系统里可以直接卸载了", Toast.LENGTH_LONG).show();
            return;
        }

        // 三级策略：静默激活 → 系统确认页 → 都不行就给 adb 命令
        int r = DeviceGuard.activate(this);
        if (r == DeviceGuard.ACTIVATE_FAILED) {
            Prefs.setUninstallGuard(false);
            refreshGuard();
            showActivateHelp();
            return;
        }

        Prefs.setUninstallGuard(true);

        if (r == DeviceGuard.ACTIVATE_UI) {
            refreshGuard();
            Toast.makeText(this, "请在弹出的系统页面点「激活」，回到本页会自动刷新", Toast.LENGTH_LONG).show();
            watchAdminPage();
            return;
        }

        boolean locked = DeviceGuard.sync(this);
        refreshGuard();
        if (locked) {
            Toast.makeText(this, "系统卸载已锁定，必须输入卸载码才能卸载", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已开启：设备管理员已就位，孩子卸载前必须先停用它", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 系统可能「返回 Activity 存在、实际上根本没显示」（部分电视/精简 ROM 会吞掉这个 intent），
     * 所以要复查一次：本页没有被切走、且管理员仍然没激活，就直接给出 adb 方案。
     */
    private final Runnable adminCheck = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || leftForAdminPage) {
                return;
            }
            if (DeviceGuard.isAdminActive(SecurityActivity.this)) {
                refreshGuard();
                return;
            }
            Prefs.setUninstallGuard(false);
            refreshGuard();
            new AlertDialog.Builder(SecurityActivity.this)
                    .setTitle("系统没有打开激活页面")
                    .setMessage(DeviceGuard.activateHelp(SecurityActivity.this))
                    .setPositiveButton("复制基础保护命令", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            copyText("基础保护命令", DeviceGuard.adbActiveAdminCommand(SecurityActivity.this));
                        }
                    })
                    .setNeutralButton("复制彻底锁定命令", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            copyText("彻底锁定命令", DeviceGuard.adbCommand(SecurityActivity.this));
                        }
                    })
                    .setNegativeButton("知道了", null)
                    .show();
        }
    };

    private void watchAdminPage() {
        leftForAdminPage = false;
        uiHandler.removeCallbacks(adminCheck);
        uiHandler.postDelayed(adminCheck, 2500);
    }

    /** 电视厂商裁掉了系统激活页时的唯一可行路径：一条 adb 命令 */
    private void showActivateHelp() {
        new AlertDialog.Builder(this)
                .setTitle("这台电视没有「设备管理员」页面")
                .setMessage(DeviceGuard.activateHelp(this))
                .setPositiveButton("复制基础保护命令", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyText("基础保护命令", DeviceGuard.adbActiveAdminCommand(SecurityActivity.this));
                    }
                })
                .setNeutralButton("复制彻底锁定命令", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyText("彻底锁定命令", DeviceGuard.adbCommand(SecurityActivity.this));
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private void showOwnerGuide() {
        new AlertDialog.Builder(this)
                .setTitle("彻底锁定系统卸载按钮")
                .setMessage(DeviceGuard.ownerGuide(this))
                .setPositiveButton("复制到剪贴板", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyText("设备所有者命令", DeviceGuard.adbCommand(SecurityActivity.this));
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private void copyText(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb", text));
            Toast.makeText(this, label + "已复制，可发到电脑上粘贴", Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(this, "复制失败，请手抄命令", Toast.LENGTH_LONG).show();
        }
    }

    private void startUninstall() {
        long remain = Prefs.uninstallLockRemainMs();
        if (remain > 0) {
            Toast.makeText(this, "输错次数过多，请 " + UninstallCode.describeLock(remain) + "后再试",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new PinDialog(this, "请输入卸载码", new PinDialog.Verifier() {
            @Override
            public String check(String in) {
                if (in == null || in.length() == 0) {
                    return "请输入卸载码";
                }
                if (Prefs.uninstallLockRemainMs() > 0) {
                    return "已锁定，请 " + UninstallCode.describeLock(Prefs.uninstallLockRemainMs()) + "后再试";
                }
                if (Prefs.verifyUninstallPin(in)) {
                    return null;
                }
                long r = Prefs.uninstallLockRemainMs();
                if (r > 0) {
                    return "错误次数过多，" + UninstallCode.describeLock(r) + "后再试";
                }
                return "卸载码错误（默认 246810）";
            }
        }, new PinDialog.Listener() {
            @Override
            public void onResult(boolean ok) {
                refreshGuard();
                if (ok) {
                    confirmUninstall();
                }
            }
        }).show();
    }

    private void confirmUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("确认卸载")
                .setMessage("卸载后所有管控立即失效，孩子可以随便看电视。\n\n"
                        + "如果只是想暂停，请按「取消」，改用手机管理页的「启用拦截」开关。")
                .setPositiveButton("确定卸载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        DeviceGuard.release(SecurityActivity.this);
                        DeviceGuard.openSystemUninstall(SecurityActivity.this);
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
