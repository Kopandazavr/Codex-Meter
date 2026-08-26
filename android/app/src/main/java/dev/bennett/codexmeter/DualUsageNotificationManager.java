package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Notification-shade presentation for the live monitor.
 *
 * <p>Android ProgressStyle and Samsung's ongoing-activity template both expose one native
 * progress track. This renderer deliberately replaces that shade card with a compact custom
 * layout containing both allowance windows. The monitor state, alarms, actions, and reset
 * reminder remain owned by {@link NowBarManager}.</p>
 */
final class DualUsageNotificationManager {
    private static final String CHANNEL_ID = "codex_live_monitor_v2";
    private static final int NOTIFICATION_ID = 8610;
    private static final int REQUEST_CONTENT = 9760;
    private static final int REQUEST_STOP = 9761;
    private static final int REQUEST_REFRESH = 9762;
    private static final int REQUEST_DISMISSED = 9763;

    private DualUsageNotificationManager() {
    }

    static boolean postFromSnapshot(Context context, UsageSnapshot snapshot) {
        if (context == null || snapshot == null || !NowBarManager.isActive(context)
                || !NowBarManager.canPostNotifications(context)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long observedAt = snapshot.fetchedAtMillis;
        UsageWindow fiveHour = UsageSnapshot.currentWindow(snapshot.fiveHour, observedAt, now);
        UsageWindow longWindow = UsageSnapshot.currentWindow(snapshot.longWindow(), observedAt, now);
        if (fiveHour == null && longWindow == null) return false;

        String longLabel = snapshot.longWindowIsMonthly() ? "Monthly" : "Weekly";
        String focus = NowBarManager.activeFocusMetric(context);
        if (focus == null) {
            focus = NowBarPercentMode.lowerRemainingFocus(fiveHour, longWindow);
        }
        UsageWindow paceWindow = NowBarPercentMode.selectWindow(focus, fiveHour, longWindow);
        String fiveResetTime = formatResetTime(fiveHour, observedAt);
        String longResetTime = formatResetTime(longWindow, observedAt);

        try {
            SubscriptionStore.seedFromJwt(context, SecureTokenStore.load(context), now);
        } catch (RuntimeException ignored) {
        }
        SubscriptionInfo subscription = SubscriptionStore.load(context);
        String planText = formatSubscription(subscription);

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, REQUEST_CONTENT, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getBroadcast(context, REQUEST_STOP,
                new Intent(context, NowBarActionReceiver.class).setAction(NowBarManager.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent refreshIntent = PendingIntent.getBroadcast(context, REQUEST_REFRESH,
                new Intent(context, NowBarActionReceiver.class).setAction(NowBarManager.ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent dismissedIntent = PendingIntent.getBroadcast(context, REQUEST_DISMISSED,
                new Intent(context, NowBarActionReceiver.class).setAction(NowBarManager.ACTION_DISMISSED),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon stopIcon = Icon.createWithResource(context, R.drawable.ic_notification);
        Icon refreshIcon = Icon.createWithResource(context, R.drawable.ic_refresh);
        RemoteViews compact = buildViews(context, fiveHour, longWindow, longLabel,
                observedAt, now, planText, fiveResetTime, longResetTime);
        RemoteViews expanded = buildViews(context, fiveHour, longWindow, longLabel,
                observedAt, now, planText, fiveResetTime, longResetTime);

        String fiveText = NowBarCopy.limitText("5-hour", fiveHour, observedAt, now);
        String longText = NowBarCopy.limitText(longLabel, longWindow, observedAt, now);
        // Keep the platform fallback exactly as compact as the last known-good dual-bar build.
        // Samsung's decorated custom notification can change the amount of vertical space it
        // grants the custom RemoteViews when this fallback becomes too long. The reset timestamps
        // belong only in the custom rows below, where they do not affect that template decision.
        String fallbackText = fiveText + " · " + longText
                + (longResetTime.isEmpty() ? ""
                : " · " + longLabel + " reset: " + longResetTime);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Codex usage")
                .setContentText(fallbackText)
                .setContentIntent(contentIntent)
                .setDeleteIntent(dismissedIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(Color.rgb(3, 129, 254))
                .setShowWhen(false)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(compact)
                .setCustomBigContentView(expanded)
                .addAction(new Notification.Action.Builder(stopIcon, "Stop", stopIntent).build())
                .addAction(new Notification.Action.Builder(refreshIcon, "Refresh", refreshIntent).build());

        boolean weeklyFocus = NowBarPercentMode.isWeeklyFocus(focus);
        String reminderMetric = weeklyFocus
                ? (snapshot.longWindowIsMonthly() ? "monthly" : "weekly") : "five_hour";
        Notification.Action reminder = NowBarResetReminder.buildAction(
                context, reminderMetric, paceWindow, observedAt);
        if (reminder != null) builder.addAction(reminder);

        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
            DiagnosticLog.info(context, "now_bar", "dual_notification_posted",
                    "five_hour", fiveHour != null,
                    "long_window", longWindow != null,
                    "plan_expiry", subscription != null && subscription.activeUntilMillis > 0L,
                    "five_hour_reset", fiveHour != null && !fiveResetTime.isEmpty(),
                    "long_reset", longWindow != null && !longResetTime.isEmpty());
            return true;
        } catch (RuntimeException exception) {
            DiagnosticLog.error(context, "now_bar", "dual_notification_post_failed", exception);
            return false;
        }
    }

    static boolean repostFromCache(Context context) {
        if (context == null || !NowBarManager.isActive(context)) return false;
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(context);
        return snapshot != null && postFromSnapshot(context, snapshot);
    }

    static void repostDelayed(Context context, long delayMillis) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> repostFromCache(app), Math.max(0L, delayMillis));
    }

    private static RemoteViews buildViews(Context context, UsageWindow fiveHour,
            UsageWindow longWindow, String longLabel, long observedAt, long now,
            String planText, String fiveResetTime, String longResetTime) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                R.layout.notification_usage_dual_bars);
        int textColor = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                ? Color.WHITE : Color.rgb(32, 33, 36);

        if (planText.isEmpty()) {
            views.setViewVisibility(R.id.notification_plan_text, View.GONE);
        } else {
            views.setViewVisibility(R.id.notification_plan_text, View.VISIBLE);
            views.setTextViewText(R.id.notification_plan_text, planText);
            views.setTextColor(R.id.notification_plan_text, textColor);
        }

        if (fiveHour == null) {
            views.setViewVisibility(R.id.notification_five_row, View.GONE);
        } else {
            views.setViewVisibility(R.id.notification_five_row, View.VISIBLE);
            String fiveText = NowBarCopy.limitText("5-hour", fiveHour, observedAt, now);
            if (!fiveResetTime.isEmpty()) fiveText += " · reset " + fiveResetTime;
            views.setTextViewText(R.id.notification_five_text, fiveText);
            views.setTextColor(R.id.notification_five_text, textColor);
            views.setProgressBar(R.id.notification_five_progress, 100,
                    fiveHour.remainingPercent(), false);
        }

        if (longWindow == null) {
            views.setViewVisibility(R.id.notification_long_row, View.GONE);
        } else {
            views.setViewVisibility(R.id.notification_long_row, View.VISIBLE);
            String longText = NowBarCopy.limitText(longLabel, longWindow, observedAt, now);
            if (!longResetTime.isEmpty()) longText += " · reset " + longResetTime;
            views.setTextViewText(R.id.notification_long_text, longText);
            views.setTextColor(R.id.notification_long_text, textColor);
            views.setProgressBar(R.id.notification_long_progress, 100,
                    longWindow.remainingPercent(), false);
        }
        return views;
    }

    static String formatResetTime(UsageWindow window, long observedAtMillis) {
        if (window == null) return "";
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        if (resetAt <= 0L) return "";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM, HH:mm",
                Locale.getDefault()).withZone(ZoneId.systemDefault());
        return formatter.format(Instant.ofEpochMilli(resetAt));
    }

    static String formatSubscription(SubscriptionInfo info) {
        if (info == null || !info.hasDisplayableData()) return "";
        String plan = info.displayPlanName();
        if (info.activeUntilMillis <= 0L) return plan;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm",
                Locale.getDefault()).withZone(ZoneId.systemDefault());
        return plan + " · до " + formatter.format(Instant.ofEpochMilli(info.activeUntilMillis));
    }
}
