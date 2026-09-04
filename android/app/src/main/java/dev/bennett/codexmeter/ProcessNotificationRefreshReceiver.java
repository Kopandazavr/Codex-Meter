package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Minute-level refresh tick for calendar-backed long-running process notifications. */
public final class ProcessNotificationRefreshReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ProcessNotificationScheduler.ACTION_REFRESH.equals(intent.getAction())) {
            return;
        }
        if (!NowBarManager.isActive(context)) {
            ProcessNotificationScheduler.cancel(context);
            ProcessNotificationManager.clearAll(context);
            return;
        }
        DualUsageNotificationManager.repostFromCache(context);
        ProcessNotificationScheduler.schedule(context);
    }
}
