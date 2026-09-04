package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;

/** Small first-run handoff state that survives the asynchronous initial usage refresh. */
final class QuickSetupPreferences {
    private static final String PREFS = "codex_meter_quick_setup_v1";
    private static final String KEY_START_MONITOR_WHEN_READY = "start_monitor_when_ready";

    private QuickSetupPreferences() {
    }

    static void requestMonitorStart(Context context) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_START_MONITOR_WHEN_READY, true).apply();
    }

    static boolean shouldStartMonitor(Context context) {
        return context != null && prefs(context).getBoolean(KEY_START_MONITOR_WHEN_READY, false);
    }

    static void clearMonitorStart(Context context) {
        if (context == null) return;
        prefs(context).edit().remove(KEY_START_MONITOR_WHEN_READY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
