package com.family.studytv;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

/**
 * 卸载界面的最后一道闸门。
 *
 * <p>系统卸载器不会给第三方应用任何回调，设备管理员在部分机型（Sony BRAVIA / Android 9）
 * 又是空壳，因此只能用无障碍服务盯着窗口：一旦出现「卸载题锁」的界面，立刻执行返回，
 * 把卸载流程顶掉。家长在本应用里输对卸载码后会写入放行窗口（10 分钟），
 * 这段时间内不再拦截，可以从系统里正常卸载。
 *
 * <p>该服务只在「防卸载」开关打开时生效，且不读取任何窗口内容以外的信息。
 */
public class UninstallGuardService extends AccessibilityService {

    /** 同一个窗口内的重复事件节流，避免连续 BACK 把设置页一起退掉 */
    private static final long THROTTLE_MS = 800L;

    private long lastBlockAt;
    private String lastBlockKey;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        if (!Prefs.uninstallGuard()) {
            return;
        }
        CharSequence pkgCs = event.getPackageName();
        CharSequence clsCs = event.getClassName();
        String pkg = pkgCs == null ? null : pkgCs.toString();
        String cls = clsCs == null ? null : clsCs.toString();

        if (!UninstallShield.isSystemInstallerUi(pkg)) {
            return;
        }

        String text = readWindowText();
        boolean block = UninstallShield.shouldBlock(
                pkg, cls, text,
                getPackageName(), getString(R.string.app_name),
                true, Prefs.uninstallAllowUntil(), System.currentTimeMillis());
        if (!block) {
            return;
        }

        String key = pkg + "|" + cls;
        long now = SystemClock.elapsedRealtime();
        if (key.equals(lastBlockKey) && now - lastBlockAt < THROTTLE_MS) {
            return;
        }
        lastBlockKey = key;
        lastBlockAt = now;

        // 退回，把卸载界面顶掉；再拉回本应用，让孩子看到的是学习页而不是设置页
        performGlobalAction(GLOBAL_ACTION_BACK);
        Prefs.recordA11yBlock();
        toast("「题锁」已锁定，卸载需要卸载码");
    }

    /** 把根节点的文本与描述拼起来，用于判断是不是在卸载本应用；读不到返回 null */
    private String readWindowText() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            collect(root, sb, 0);
            try {
                root.recycle();
            } catch (Throwable ignored) {
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 12 || sb.length() > 4000) {
            return;
        }
        CharSequence t = node.getText();
        if (t != null) {
            sb.append(t).append('\n');
        }
        CharSequence d = node.getContentDescription();
        if (d != null) {
            sb.append(d).append('\n');
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                collect(child, sb, depth + 1);
            } catch (Throwable ignored) {
            } finally {
                if (child != null) {
                    try {
                        child.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
