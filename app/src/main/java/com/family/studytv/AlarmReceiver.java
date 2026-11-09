package com.family.studytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 精确闹钟到点回调，唤醒守护服务做一次到点检查。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, GuardService.class);
        i.setAction(GuardService.ACTION_TICK);
        GuardService.start(context, i);
    }
}
