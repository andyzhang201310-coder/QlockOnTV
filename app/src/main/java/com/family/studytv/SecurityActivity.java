package com.family.studytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security);

        tvGuard = (TextView) findViewById(R.id.tvGuard);
        btnGuard = (Button) findViewById(R.id.btnGuard);

        btnGuard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGuard();
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
        DeviceGuard.sync(this);
        refreshGuard();
    }

    private void refreshGuard() {
        boolean on = Prefs.uninstallGuard();
        boolean admin = DeviceGuard.isAdminActive(this);
        boolean owner = DeviceGuard.isDeviceOwner(this);
        boolean blocked = DeviceGuard.isUninstallBlocked(this);

        StringBuilder sb = new StringBuilder();
        sb.append("保护状态：").append(DeviceGuard.statusLine(this)).append("\n");
        sb.append("设备管理员：").append(admin ? "已激活" : "未激活");
        if (owner) {
            sb.append("　设备所有者：已生效");
        } else if (admin) {
            sb.append("　设备所有者：未设置");
        }
        sb.append("\n");
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
    }

    private void toggleGuard() {
        if (Prefs.uninstallGuard()) {
            Prefs.setUninstallGuard(false);
            DeviceGuard.sync(this);
            refreshGuard();
            Toast.makeText(this, "已关闭，系统里可以直接卸载了", Toast.LENGTH_LONG).show();
            return;
        }

        Prefs.setUninstallGuard(true);

        if (!DeviceGuard.isAdminActive(this)) {
            try {
                startActivity(DeviceGuard.activateIntent(this));
                Toast.makeText(this, "请在系统页面点「激活」", Toast.LENGTH_LONG).show();
            } catch (Throwable e) {
                Toast.makeText(this, "无法打开设备管理器页面，请到系统设置里手动激活", Toast.LENGTH_LONG).show();
            }
            refreshGuard();
            return;
        }

        boolean locked = DeviceGuard.sync(this);
        refreshGuard();
        if (locked) {
            Toast.makeText(this, "系统卸载已锁定，必须输入卸载码才能卸载", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已开启基础保护：卸载前必须先停用设备管理员", Toast.LENGTH_LONG).show();
        }
    }

    private void showOwnerGuide() {
        new AlertDialog.Builder(this)
                .setTitle("彻底锁定系统卸载按钮")
                .setMessage(DeviceGuard.ownerGuide(this))
                .setPositiveButton("复制到剪贴板", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyCommand();
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private void copyCommand() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb", DeviceGuard.adbCommand(this)));
            Toast.makeText(this, "命令已复制，可发到手机/电脑上粘贴", Toast.LENGTH_LONG).show();
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
        new PinDialog(this, "输入卸载码", new PinDialog.Verifier() {
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
