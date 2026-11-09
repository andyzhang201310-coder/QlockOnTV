package com.family.studytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 关机 / 重启时打标记：如果当时正在答题，下次开机必须继续答完。
 */
public class ShutdownReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (Prefs.isEnabled() && (QuizActivity.running || Prefs.isPending())) {
                Prefs.setPending(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
