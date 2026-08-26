package dev.bennett.codexmeter;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/**
 * One-shot reset reminder controlled directly from the live usage notification.
 *
 * <p>This is intentionally independent from the app-wide usage-alert enable switch: tapping the
 * bell means "play a sound for this specific window reset". Firing the reminder never posts a
 * second notification; the existing live monitor is simply refreshed/reposted in place.</p>
 */
public final class NowBarResetReminder {
    static final String ACTION_TOGGLE =
            "dev.bennett.codexmeter.action.NOW_BAR_RESET_REMINDER_TOGGLE";
    static final String ACTION_FIRE =
            "dev.bennett.codexmeter.action.NOW_BAR_RESET_REMINDER_FIRE";
    static final String EXTRA_METRIC = "metric";
    static final String EXTRA_RESET_AT = "reset_at";
    static final String EXTRA_WINDOW_SECONDS = "window_seconds";

    private static final String PREFS = "codex_meter_now_bar_reset_reminder_v1";
    private static final String KEY_ARMED = "armed";
    private static final String KEY_METRIC = "metric";
    private static final String KEY_RESET_AT = "reset_at";
    private static final String KEY_WINDOW_SECONDS = "window_seconds";
    private static final String CHANNEL_NOTIFY = "codex_reset_notify";
    private static final String CHANNEL_ALARM = "codex_reset_alarm";
    private static final String CHANNEL_SILENT = "codex_reset_silent";
    private static final int REQUEST_TOGGLE = 8621;
    private static final int REQUEST_FIRE = 8622;
    private static final long DELIVERY_GRACE_MS = 3000L;

    private NowBarResetReminder() {
    }

    /** Builds the bell action for the usage window currently driving the live progress bar. */
    static Notification.Action buildAction(Context context, String metric, UsageWindow window,
            long observedAtMillis) {
        if (context == null || window == null) return null;
        String normalizedMetric = normalizeMetric(metric);
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        if (normalizedMetric == null || resetAt <= System.currentTimeMillis()) return null;
        long windowSeconds = window.windowSeconds;

        boolean armed = isArmedFor(context, normalizedMetric, resetAt, windowSeconds);
        if (armed) {
            long storedResetAt = state(context).getLong(KEY_RESET_AT, 0L);
            if (storedResetAt != resetAt) {
                armInternal(context, normalizedMetric, resetAt, windowSeconds);
            }
        }

        Intent toggle = new Intent(context, NowBarActionReceiver.class)
                .setAction(ACTION_TOGGLE)
                .putExtra(EXTRA_METRIC, normalizedMetric)
                .putExtra(EXTRA_RESET_AT, resetAt)
                .putExtra(EXTRA_WINDOW_SECONDS, windowSeconds);
        PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST_TOGGLE, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Icon icon = Icon.createWithResource(context,
                armed ? R.drawable.ic_bell_on : R.drawable.ic_bell_off);
        return new Notification.Action.Builder(icon,
                armed ? "Reset alert on" : "Notify on reset", pending).build();
    }

    static void toggleFromIntent(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String metric = normalizeMetric(intent.getStringExtra(EXTRA_METRIC));
        long resetAt = intent.getLongExtra(EXTRA_RESET_AT, 0L);
        long windowSeconds = Math.max(0L, intent.getLongExtra(EXTRA_WINDOW_SECONDS, 0L));
        if (metric == null || resetAt <= System.currentTimeMillis()) return;

        if (isArmedFor(context, metric, resetAt, windowSeconds)) {
            disarm(context);
        } else {
            armInternal(context, metric, resetAt, windowSeconds);
        }
        NowBarManager.repostActive(context);
    }

    static void fireFromIntent(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String metric = normalizeMetric(intent.getStringExtra(EXTRA_METRIC));
        long resetAt = intent.getLongExtra(EXTRA_RESET_AT, 0L);
        long windowSeconds = Math.max(0L, intent.getLongExtra(EXTRA_WINDOW_SECONDS, 0L));
        SharedPreferences preferences = state(context);
        if (!preferences.getBoolean(KEY_ARMED, false)) return;
        if (!sameMetric(preferences.getString(KEY_METRIC, null), metric)
                || preferences.getLong(KEY_RESET_AT, 0L) != resetAt
                || preferences.getLong(KEY_WINDOW_SECONDS, 0L) != windowSeconds) {
            return;
        }

        clearState(context);
        if (!SecureTokenStore.isSignedIn(context)) return;
        playResetSound(context, metric);
        // Keep exactly one Codex Meter card: repost the same live-monitor notification ID.
        NowBarManager.repostActive(context);
        RefreshScheduler.scheduleImmediate(context);
        WidgetRenderer.updateAll(context);
    }

    /** Re-arms the stored one-shot reminder after reboot or package replacement. */
    public static void restore(Context context) {
        if (context == null) return;
        SharedPreferences preferences = state(context);
        if (!preferences.getBoolean(KEY_ARMED, false)) return;
        String metric = normalizeMetric(preferences.getString(KEY_METRIC, null));
        long resetAt = preferences.getLong(KEY_RESET_AT, 0L);
        long windowSeconds = Math.max(0L, preferences.getLong(KEY_WINDOW_SECONDS, 0L));
        if (metric == null || resetAt <= 0L || !SecureTokenStore.isSignedIn(context)) {
            disarm(context);
            return;
        }
        if (resetAt <= System.currentTimeMillis()) {
            clearState(context);
            playResetSound(context, metric);
            NowBarManager.repostActive(context);
            RefreshScheduler.scheduleImmediate(context);
            WidgetRenderer.updateAll(context);
            return;
        }
        schedule(context, metric, resetAt, windowSeconds);
    }

    static boolean isArmedFor(Context context, String metric, long resetAt, long windowSeconds) {
        if (context == null || metric == null || resetAt <= 0L) return false;
        SharedPreferences preferences = state(context);
        if (!preferences.getBoolean(KEY_ARMED, false)
                || !sameMetric(preferences.getString(KEY_METRIC, null), metric)) {
            return false;
        }
        long storedResetAt = preferences.getLong(KEY_RESET_AT, 0L);
        long storedWindowSeconds = preferences.getLong(KEY_WINDOW_SECONDS, 0L);
        if (storedResetAt <= 0L) return false;
        if (storedWindowSeconds > 0L && windowSeconds > 0L) {
            return UsageWindow.sameResetWindow(storedResetAt, storedWindowSeconds,
                    resetAt, windowSeconds);
        }
        return Math.abs(storedResetAt - resetAt) < 60_000L;
    }

    private static void armInternal(Context context, String metric, long resetAt,
            long windowSeconds) {
        cancelAlarm(context);
        state(context).edit()
                .putBoolean(KEY_ARMED, true)
                .putString(KEY_METRIC, metric)
                .putLong(KEY_RESET_AT, resetAt)
                .putLong(KEY_WINDOW_SECONDS, Math.max(0L, windowSeconds))
                .apply();
        // Ensure the user-configurable Codex Usage Alerts channel exists. We only read its
        // configured sound when the bell fires; no secondary notification is posted.
        try {
            ResetNotificationManager.ensureChannel(context);
        } catch (RuntimeException ignored) {
        }
        schedule(context, metric, resetAt, windowSeconds);
    }

    private static void disarm(Context context) {
        cancelAlarm(context);
        clearState(context);
    }

    private static void clearState(Context context) {
        state(context).edit().clear().apply();
    }

    private static void schedule(Context context, String metric, long resetAt,
            long windowSeconds) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null || resetAt <= System.currentTimeMillis()) return;
        PendingIntent pending = fireIntent(context, metric, resetAt, windowSeconds);
        long when = resetAt + DELIVERY_GRACE_MS;
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
            }
        } catch (SecurityException exception) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
        }
    }

    private static void cancelAlarm(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST_FIRE,
                new Intent(context, NowBarActionReceiver.class).setAction(ACTION_FIRE),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) {
            alarms.cancel(pending);
            pending.cancel();
        }
    }

    private static PendingIntent fireIntent(Context context, String metric, long resetAt,
            long windowSeconds) {
        Intent fire = new Intent(context, NowBarActionReceiver.class)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_METRIC, metric)
                .putExtra(EXTRA_RESET_AT, resetAt)
                .putExtra(EXTRA_WINDOW_SECONDS, Math.max(0L, windowSeconds));
        return PendingIntent.getBroadcast(context, REQUEST_FIRE, fire,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void playResetSound(Context context, String metric) {
        try {
            Uri sound = configuredUsageAlertSound(context);
            if (sound == null) {
                sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            if (sound == null) {
                DiagnosticLog.warn(context, "now_bar", "reset_sound_unavailable",
                        "metric", metric == null ? "" : metric);
                return;
            }
            Ringtone ringtone = RingtoneManager.getRingtone(context.getApplicationContext(), sound);
            if (ringtone == null) {
                DiagnosticLog.warn(context, "now_bar", "reset_sound_unavailable",
                        "metric", metric == null ? "" : metric);
                return;
            }
            ringtone.play();
            DiagnosticLog.info(context, "now_bar", "reset_sound_played",
                    "metric", metric == null ? "" : metric);
        } catch (RuntimeException exception) {
            DiagnosticLog.error(context, "now_bar", "reset_sound_failed", exception,
                    "metric", metric == null ? "" : metric);
        }
    }

    private static Uri configuredUsageAlertSound(Context context) {
        try {
            ResetNotificationManager.ensureChannel(context);
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return null;
            String style = ResetAlertPreferences.getStyle(context);
            String channelId = ResetAlertPreferences.STYLE_ALARM.equals(style)
                    ? CHANNEL_ALARM
                    : ResetAlertPreferences.STYLE_SILENT.equals(style)
                    ? CHANNEL_SILENT : CHANNEL_NOTIFY;
            NotificationChannel channel = manager.getNotificationChannel(channelId);
            return channel == null ? null : channel.getSound();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizeMetric(String metric) {
        if ("five_hour".equals(metric) || "weekly".equals(metric) || "monthly".equals(metric)) {
            return metric;
        }
        return null;
    }

    private static boolean sameMetric(String left, String right) {
        String normalizedLeft = normalizeMetric(left);
        String normalizedRight = normalizeMetric(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
