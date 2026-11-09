package com.family.studytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 应用更新后自动拉起守护服务。
 * 兼容部分国产盒子只发 QUICKBOOT_POWERON 的情况。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        Intent i = new Intent(context, GuardService.class);
        i.putExtra(GuardService.EXTRA_BOOT, true);
        GuardService.start(context, i);
    }
}
