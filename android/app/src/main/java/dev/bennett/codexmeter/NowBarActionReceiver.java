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
        } else if (NowBarManager.ACTION_END.equals(action)) {
            NowBarManager.onScheduledEnd(context);
        } else if (NowBarManager.ACTION_REFRESH.equals(action)) {
            RefreshScheduler.scheduleImmediate(context);
        } else if (NowBarManager.ACTION_DISMISSED.equals(action)) {
            NowBarManager.onUserDismissed(context);
        } else if (NowBarResetReminder.ACTION_TOGGLE.equals(action)) {
            NowBarResetReminder.toggleFromIntent(context, intent);
        } else if (NowBarResetReminder.ACTION_FIRE.equals(action)) {
            NowBarResetReminder.fireFromIntent(context, intent);
        }
    }
}
