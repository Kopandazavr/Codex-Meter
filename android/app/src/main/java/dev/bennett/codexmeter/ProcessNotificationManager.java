package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Renders active and idle calendar-backed process roles. */
final class ProcessNotificationManager {
    private static final String CHANNEL_ID = "codex_active_processes_v1";
    private static final int GROUPED_NOTIFICATION_ID = 8620;
    private static final int PROCESS_NOTIFICATION_BASE = 12000;
    private static final int PROCESS_NOTIFICATION_RANGE = 12000;
    private static final int IDLE_NOTIFICATION_BASE = 24000;
    private static final int REQUEST_CONTENT = 9780;
    private static final String PREFS = "codex_process_notification_state_v1";
    private static final String KEY_ACTIVE_IDS = "active_ids";

    private ProcessNotificationManager() {
    }

    static void sync(Context context, List<CalendarProcess> processes,
            List<IdleProcessState.IdleRole> idleRoles, String mode, long nowMillis) {
        if (context == null) return;
        String normalizedMode = ProcessNotificationMode.normalize(mode);
        if (ProcessNotificationMode.COMBINED.equals(normalizedMode)) {
            clearAll(context);
            return;
        }
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        ensureChannel(manager);
        if (ProcessNotificationMode.GROUPED.equals(normalizedMode)) {
            clearPerProcess(context, manager);
            if (isEmpty(processes) && isEmpty(idleRoles)) {
                manager.cancel(GROUPED_NOTIFICATION_ID);
                return;
            }
            manager.notify(GROUPED_NOTIFICATION_ID,
                    buildNotification(context, processes, idleRoles, "Processes", nowMillis));
            return;
        }
        manager.cancel(GROUPED_NOTIFICATION_ID);
        syncPerProcess(context, manager, processes, idleRoles, nowMillis);
    }

    static void clearAll(Context context) {
        if (context == null) return;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.cancel(GROUPED_NOTIFICATION_ID);
        clearPerProcess(context, manager);
    }

    static void addRows(Context context, RemoteViews parent, int containerId,
            List<CalendarProcess> processes, List<IdleProcessState.IdleRole> idleRoles,
            long nowMillis) {
        parent.removeAllViews(containerId);
        if (processes != null) {
            for (CalendarProcess process : processes) {
                parent.addView(containerId, buildActiveRow(context, process, nowMillis));
            }
        }
        if (idleRoles != null) {
            for (IdleProcessState.IdleRole idle : idleRoles) {
                parent.addView(containerId, buildIdleRow(context, idle, nowMillis));
            }
        }
    }

    private static void syncPerProcess(Context context, NotificationManager manager,
            List<CalendarProcess> processes, List<IdleProcessState.IdleRole> idleRoles,
            long nowMillis) {
        Set<String> nextIds = new HashSet<>();
        if (processes != null) {
            for (CalendarProcess process : processes) {
                int id = notificationId(process);
                nextIds.add(String.valueOf(id));
                manager.notify(id, buildNotification(context,
                        Collections.singletonList(process), Collections.emptyList(),
                        process.project.isEmpty() ? "Active process" : process.project,
                        nowMillis));
            }
        }
        if (idleRoles != null) {
            for (IdleProcessState.IdleRole idle : idleRoles) {
                int id = idleNotificationId(idle.key);
                nextIds.add(String.valueOf(id));
                manager.notify(id, buildNotification(context,
                        Collections.emptyList(), Collections.singletonList(idle),
                        idle.displayLabel(), nowMillis));
            }
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> previous = new HashSet<>(preferences.getStringSet(KEY_ACTIVE_IDS,
                Collections.emptySet()));
        for (String id : previous) {
            if (nextIds.contains(id)) continue;
            try {
                manager.cancel(Integer.parseInt(id));
            } catch (NumberFormatException ignored) {
            }
        }
        preferences.edit().putStringSet(KEY_ACTIVE_IDS, nextIds).apply();
    }

    private static void clearPerProcess(Context context, NotificationManager manager) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> activeIds = new HashSet<>(preferences.getStringSet(KEY_ACTIVE_IDS,
                Collections.emptySet()));
        for (String id : activeIds) {
            try {
                manager.cancel(Integer.parseInt(id));
            } catch (NumberFormatException ignored) {
            }
        }
        preferences.edit().remove(KEY_ACTIVE_IDS).apply();
    }

    private static Notification buildNotification(Context context, List<CalendarProcess> processes,
            List<IdleProcessState.IdleRole> idleRoles, String title, long nowMillis) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, REQUEST_CONTENT, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notification_processes);
        int textColor = textColor(context);
        int activeCount = processes == null ? 0 : processes.size();
        int idleCount = idleRoles == null ? 0 : idleRoles.size();
        views.setTextViewText(R.id.notification_processes_title, title);
        views.setTextColor(R.id.notification_processes_title, textColor);
        views.setTextViewText(R.id.notification_processes_count,
                countLabel(activeCount, idleCount));
        views.setTextColor(R.id.notification_processes_count, textColor);
        addRows(context, views, R.id.notification_processes_container,
                processes, idleRoles, nowMillis);

        String content = activeCount > 0
                ? activeCount + (activeCount == 1 ? " active" : " active")
                : idleCount + (idleCount == 1 ? " idle" : " idle");
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(Color.rgb(3, 129, 254))
                .setShowWhen(false)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(views)
                .setCustomBigContentView(views)
                .build();
    }

    private static RemoteViews buildActiveRow(Context context, CalendarProcess process,
            long nowMillis) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.notification_process_row);
        int textColor = textColor(context);
        row.setTextViewText(R.id.notification_process_title, process.displayLabel());
        row.setTextColor(R.id.notification_process_title, textColor);
        row.setTextViewText(R.id.notification_process_remaining,
                formatRemaining(process.endMillis - nowMillis));
        row.setTextColor(R.id.notification_process_remaining, textColor);
        row.setProgressBar(R.id.notification_process_progress, 100,
                process.remainingPercent(nowMillis), false);
        row.setViewVisibility(R.id.notification_process_progress, View.VISIBLE);
        row.setViewVisibility(R.id.notification_process_dismiss, View.GONE);
        row.setViewVisibility(R.id.notification_process_reminder, View.GONE);
        return row;
    }

    private static RemoteViews buildIdleRow(Context context, IdleProcessState.IdleRole idle,
            long nowMillis) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.notification_process_row);
        int textColor = textColor(context);
        row.setTextViewText(R.id.notification_process_title, idle.displayLabel());
        row.setTextColor(R.id.notification_process_title, textColor);
        row.setTextViewText(R.id.notification_process_remaining,
                "idle " + formatIdle(nowMillis - idle.lastFinishedMillis));
        row.setTextColor(R.id.notification_process_remaining, textColor);
        row.setViewVisibility(R.id.notification_process_progress, View.GONE);
        row.setViewVisibility(R.id.notification_process_dismiss, View.VISIBLE);
        row.setViewVisibility(R.id.notification_process_reminder, View.VISIBLE);
        row.setImageViewResource(R.id.notification_process_reminder,
                idle.reminderEnabled ? R.drawable.ic_bell_on : R.drawable.ic_bell_off);
        row.setInt(R.id.notification_process_dismiss, "setColorFilter", textColor);
        row.setInt(R.id.notification_process_reminder, "setColorFilter",
                idle.reminderEnabled ? 0xFFFFC107 : textColor);
        row.setOnClickPendingIntent(R.id.notification_process_dismiss,
                IdleReminderManager.dismissRowIntent(context, idle));
        row.setOnClickPendingIntent(R.id.notification_process_reminder,
                IdleReminderManager.toggleIntent(context, idle));
        return row;
    }

    private static void ensureChannel(NotificationManager manager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Active processes", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Long-running calendar watchdog processes and their idle state");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private static int notificationId(CalendarProcess process) {
        int hash = process.identity().hashCode() & 0x7fffffff;
        return PROCESS_NOTIFICATION_BASE + (hash % PROCESS_NOTIFICATION_RANGE);
    }

    private static int idleNotificationId(String key) {
        int hash = key == null ? 0 : key.hashCode() & 0x7fffffff;
        return IDLE_NOTIFICATION_BASE + (hash % PROCESS_NOTIFICATION_RANGE);
    }

    private static int textColor(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                ? Color.WHITE : Color.rgb(32, 33, 36);
    }

    private static String countLabel(int active, int idle) {
        if (active > 0 && idle > 0) return active + " active · " + idle + " idle";
        if (active > 0) return active + " active";
        return idle + " idle";
    }

    private static String formatRemaining(long remainingMillis) {
        if (remainingMillis <= 0L) return "done";
        long minutes = Math.max(1L, (remainingMillis + 59_999L) / 60_000L);
        if (minutes < 60L) return minutes + "m left";
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return rest == 0L ? hours + "h left" : hours + "h " + rest + "m";
    }

    private static String formatIdle(long idleMillis) {
        long minutes = Math.max(1L, idleMillis / 60_000L);
        if (minutes < 60L) return minutes + "m";
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        if (hours < 24L) return rest == 0L ? hours + "h" : hours + "h " + rest + "m";
        long days = hours / 24L;
        long hourRest = hours % 24L;
        return hourRest == 0L ? days + "d" : days + "d " + hourRest + "h";
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }
}
