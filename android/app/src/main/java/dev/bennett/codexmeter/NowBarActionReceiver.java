package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the explicit actions attached to the finite Now Bar Live Update. */
public final class NowBarActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (NowBarManager.ACTION_STOP.equals(action)) {
            NowBarManager.stop(context, true);
            ProcessNotificationManager.clearAll(context);
            ProcessNotificationScheduler.cancel(context);
        } else if (NowBarManager.ACTION_END.equals(action)) {
            NowBarManager.onScheduledEnd(context);
            DualUsageNotificationManager.repostDelayed(context, 450L);
        } else if (NowBarManager.ACTION_REFRESH.equals(action)) {
            RefreshScheduler.scheduleImmediate(context);
        } else if (ProcessNotificationScheduler.ACTION_REFRESH.equals(action)) {
            if (!NowBarManager.isActive(context)) {
                ProcessNotificationScheduler.cancel(context);
                ProcessNotificationManager.clearAll(context);
            } else {
                DualUsageNotificationManager.repostFromCache(context);
                ProcessNotificationScheduler.schedule(context);
            }
        } else if (NowBarManager.ACTION_DISMISSED.equals(action)) {
            NowBarManager.onUserDismissed(context);
            DualUsageNotificationManager.repostDelayed(context, 500L);
        } else if (NowBarResetReminder.ACTION_TOGGLE.equals(action)) {
            NowBarResetReminder.toggleFromIntent(context, intent);
            DualUsageNotificationManager.repostDelayed(context, 150L);
        } else if (NowBarResetReminder.ACTION_FIRE.equals(action)) {
            NowBarResetReminder.fireFromIntent(context, intent);
            DualUsageNotificationManager.repostDelayed(context, 500L);
        } else if (IdleReminderManager.ACTION_TOGGLE.equals(action)) {
            IdleReminderManager.toggleFromIntent(context, intent);
        } else if (IdleReminderManager.ACTION_DISMISS_ROW.equals(action)) {
            IdleReminderManager.dismissRowFromIntent(context, intent);
        } else if (IdleReminderManager.ACTION_FIRE.equals(action)) {
            IdleReminderManager.fireFromIntent(context, intent);
        }
    }
}
