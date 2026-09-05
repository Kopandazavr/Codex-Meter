package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Short-lived application-overlay surface for one or more idle role reminders. */
public final class IdleReminderOverlayService extends Service {
    private static final String ACTION_SHOW = "dev.bennett.codexmeter.action.SHOW_IDLE_OVERLAY";
    private static final String CHANNEL_ID = "codex_idle_overlay_service_v1";
    private static final int FOREGROUND_ID = 8641;
    private static volatile IdleReminderOverlayService running;

    private final Map<String, View> roleViews = new LinkedHashMap<>();
    private WindowManager windowManager;

    static boolean canDraw(Context context) {
        return context != null && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context));
    }

    static boolean show(Context context, IdleProcessState.IdleRole idle) {
        if (context == null || idle == null || !canDraw(context)) return false;
        Intent intent = new Intent(context, IdleReminderOverlayService.class)
                .setAction(ACTION_SHOW)
                .putExtra(IdleReminderManager.EXTRA_ROLE_KEY, idle.key)
                .putExtra(IdleReminderManager.EXTRA_FINISHED_AT, idle.lastFinishedMillis);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (RuntimeException exception) {
            DiagnosticLog.warn(context, "idle_process", "overlay_start_failed",
                    "error", exception.getClass().getSimpleName());
            return false;
        }
    }

    static void dismiss(Context context, String key) {
        IdleReminderOverlayService service = running;
        if (service == null || key == null) return;
        new Handler(Looper.getMainLooper()).post(() -> service.removeRole(key));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        ensureChannel();
        startForeground(FOREGROUND_ID, foregroundNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_SHOW.equals(intent.getAction()) || !canDraw(this)) {
            stopSelfIfEmpty();
            return START_NOT_STICKY;
        }
        String key = intent.getStringExtra(IdleReminderManager.EXTRA_ROLE_KEY);
        long finished = intent.getLongExtra(IdleReminderManager.EXTRA_FINISHED_AT, 0L);
        IdleProcessState.IdleRole idle = IdleProcessState.find(this, key);
        if (idle == null || idle.lastFinishedMillis != finished || !idle.reminderEnabled) {
            stopSelfIfEmpty();
            return START_NOT_STICKY;
        }
        showRole(idle);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (windowManager != null) {
            for (View view : roleViews.values()) {
                try {
                    windowManager.removeView(view);
                } catch (RuntimeException ignored) {
                }
            }
        }
        roleViews.clear();
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showRole(IdleProcessState.IdleRole idle) {
        View old = roleViews.get(idle.key);
        if (old != null) {
            updateButton(old, idle);
            return;
        }
        if (windowManager == null) return;
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        int size = dp(104);
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        updateButton(button, idle);

        ImageView icon = new ImageView(this);
        icon.setId(android.R.id.icon);
        icon.setImageResource(idle.reminderEnabled ? R.drawable.ic_bell_on : R.drawable.ic_bell_off);
        icon.setColorFilter(idle.reminderEnabled ? Color.BLACK : Color.WHITE);
        button.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView state = new TextView(this);
        state.setId(android.R.id.text1);
        state.setText(idle.reminderEnabled ? "ON" : "OFF");
        state.setTextSize(13f);
        state.setTextColor(idle.reminderEnabled ? Color.BLACK : Color.WHITE);
        state.setGravity(Gravity.CENTER);
        button.addView(state, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        button.setContentDescription(idle.displayLabel() + " idle reminders "
                + (idle.reminderEnabled ? "on" : "off"));
        button.setOnClickListener(view -> {
            strongHaptic();
            long now = System.currentTimeMillis();
            boolean enabled = IdleProcessState.toggleReminder(this, idle.key, now);
            IdleReminderManager.onReminderToggled(this, idle.key, enabled, now);
            IdleProcessState.IdleRole updated = IdleProcessState.find(this, idle.key);
            if (updated != null && roleViews.containsKey(idle.key)) updateButton(view, updated);
            DualUsageNotificationManager.repostDelayed(this, 120L);
        });
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                strongHaptic();
                removeRole(idle.key);
                return true;
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        params.y = dp(170 + roleViews.size() * 116);
        try {
            windowManager.addView(button, params);
            roleViews.put(idle.key, button);
        } catch (RuntimeException exception) {
            DiagnosticLog.warn(this, "idle_process", "overlay_add_failed",
                    "error", exception.getClass().getSimpleName());
        }
        stopSelfIfEmpty();
    }

    private void updateButton(View view, IdleProcessState.IdleRole idle) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(idle.reminderEnabled ? 0xFFFFC107 : 0xFF3A3A3C);
        background.setStroke(dp(2), idle.reminderEnabled ? 0xFFFFFFFF : 0xFF8E8E93);
        view.setBackground(background);
        ImageView icon = view.findViewById(android.R.id.icon);
        if (icon != null) {
            icon.setImageResource(idle.reminderEnabled ? R.drawable.ic_bell_on : R.drawable.ic_bell_off);
            icon.setColorFilter(idle.reminderEnabled ? Color.BLACK : Color.WHITE);
        }
        TextView state = view.findViewById(android.R.id.text1);
        if (state != null) {
            state.setText(idle.reminderEnabled ? "ON" : "OFF");
            state.setTextColor(idle.reminderEnabled ? Color.BLACK : Color.WHITE);
        }
    }

    private void removeRole(String key) {
        View view = roleViews.remove(key);
        if (view != null && windowManager != null) {
            try {
                windowManager.removeView(view);
            } catch (RuntimeException ignored) {
            }
        }
        stopSelfIfEmpty();
    }

    private void stopSelfIfEmpty() {
        if (roleViews.isEmpty()) stopSelf();
    }

    private void strongHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                if (manager != null) manager.getDefaultVibrator().vibrate(
                        VibrationEffect.createOneShot(160L, 255));
            } else {
                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(160L, 255));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private Notification foregroundNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 8641,
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Idle reminder")
                .setContentText("Tap the bell to toggle this role's reminders")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .build();
    }

    private void ensureChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Idle reminder overlay", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Temporary foreground service while an idle reminder overlay is visible");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
