package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable per-role lifecycle state derived from calendar-backed watchdog processes. */
final class IdleProcessState {
    private static final String PREFS = "codex_idle_process_state_v1";
    private static final String KEY_ROWS = "rows_json";
    private static final String KEY_CADENCE = "cadence_minutes";
    static final int DEFAULT_CADENCE_MINUTES = 5;

    private IdleProcessState() {
    }

    static final class IdleRole {
        final String key;
        final String project;
        final String role;
        final String topic;
        final long lastFinishedMillis;
        final long eventId;
        final boolean reminderEnabled;
        final long nextReminderAtMillis;

        IdleRole(String key, String project, String role, String topic,
                long lastFinishedMillis, long eventId, boolean reminderEnabled,
                long nextReminderAtMillis) {
            this.key = clean(key);
            this.project = clean(project);
            this.role = clean(role);
            this.topic = clean(topic);
            this.lastFinishedMillis = lastFinishedMillis;
            this.eventId = eventId;
            this.reminderEnabled = reminderEnabled;
            this.nextReminderAtMillis = nextReminderAtMillis;
        }

        String displayLabel() {
            if (!role.isEmpty()) return role;
            if (!project.isEmpty()) return project;
            return "Process";
        }
    }

    static List<IdleRole> synchronize(Context context, List<CalendarProcess> active,
            List<CalendarProcess> recentlyFinished, long nowMillis) {
        if (context == null) return Collections.emptyList();
        Map<String, MutableRole> rows = load(context);
        Set<String> activeKeys = new HashSet<>();

        if (active != null) {
            for (CalendarProcess process : active) {
                String key = roleKey(process);
                activeKeys.add(key);
                MutableRole row = rows.computeIfAbsent(key, MutableRole::new);
                if (process.endMillis >= row.pendingEndMillis) {
                    row.pendingEndMillis = process.endMillis;
                    row.pendingEventId = process.eventId;
                    row.project = clean(process.project);
                    row.role = clean(process.role);
                    row.topic = clean(process.topic);
                }
            }
        }

        if (recentlyFinished != null) {
            for (CalendarProcess process : recentlyFinished) {
                if (process.endMillis > nowMillis) continue;
                String key = roleKey(process);
                MutableRole row = rows.computeIfAbsent(key, MutableRole::new);
                promoteFinished(row, process.project, process.role, process.topic,
                        process.eventId, process.endMillis, context);
            }
        }

        for (MutableRole row : rows.values()) {
            if (!activeKeys.contains(row.key)
                    && row.pendingEndMillis > 0L
                    && row.pendingEndMillis <= nowMillis) {
                promoteFinished(row, row.project, row.role, row.topic,
                        row.pendingEventId, row.pendingEndMillis, context);
                row.pendingEndMillis = 0L;
                row.pendingEventId = 0L;
            }
        }

        save(context, rows);
        List<IdleRole> visible = new ArrayList<>();
        for (MutableRole row : rows.values()) {
            if (activeKeys.contains(row.key)
                    || row.lastFinishedMillis <= 0L
                    || row.dismissedThroughMillis >= row.lastFinishedMillis) {
                continue;
            }
            visible.add(row.freeze());
        }
        visible.sort(Comparator.comparingLong((IdleRole row) -> row.lastFinishedMillis).reversed());
        return visible;
    }

    static IdleRole find(Context context, String key) {
        MutableRole row = load(context).get(clean(key));
        return row == null || row.lastFinishedMillis <= 0L ? null : row.freeze();
    }

    static void dismiss(Context context, String key, long finishedMillis) {
        Map<String, MutableRole> rows = load(context);
        MutableRole row = rows.get(clean(key));
        if (row == null) return;
        row.dismissedThroughMillis = Math.max(row.dismissedThroughMillis, finishedMillis);
        save(context, rows);
    }

    static boolean toggleReminder(Context context, String key, long nowMillis) {
        Map<String, MutableRole> rows = load(context);
        MutableRole row = rows.get(clean(key));
        if (row == null) return false;
        row.reminderEnabled = !row.reminderEnabled;
        row.nextReminderAtMillis = row.reminderEnabled
                ? nowMillis + cadenceMillis(context) : 0L;
        save(context, rows);
        return row.reminderEnabled;
    }

    static void setNextReminderAt(Context context, String key, long whenMillis) {
        Map<String, MutableRole> rows = load(context);
        MutableRole row = rows.get(clean(key));
        if (row == null) return;
        row.nextReminderAtMillis = Math.max(0L, whenMillis);
        save(context, rows);
    }

    static int cadenceMinutes(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CADENCE, DEFAULT_CADENCE_MINUTES);
        return value == 10 ? 10 : DEFAULT_CADENCE_MINUTES;
    }

    static void setCadenceMinutes(Context context, int minutes, long nowMillis) {
        int normalized = minutes == 10 ? 10 : DEFAULT_CADENCE_MINUTES;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_CADENCE, normalized).apply();
        Map<String, MutableRole> rows = load(context);
        long next = nowMillis + normalized * 60_000L;
        for (MutableRole row : rows.values()) {
            if (row.reminderEnabled && row.lastFinishedMillis > 0L) {
                row.nextReminderAtMillis = next;
            }
        }
        save(context, rows);
    }

    static long cadenceMillis(Context context) {
        return cadenceMinutes(context) * 60_000L;
    }

    static String roleKey(CalendarProcess process) {
        String role = clean(process == null ? null : process.role);
        if (!role.isEmpty()) return "role:" + role;
        String project = clean(process == null ? null : process.project);
        if (!project.isEmpty()) return "project:" + project;
        return "process:" + (process == null ? 0L : process.eventId);
    }

    static boolean isRoleActive(List<CalendarProcess> active, String key) {
        if (active == null) return false;
        for (CalendarProcess process : active) {
            if (clean(key).equals(roleKey(process))) return true;
        }
        return false;
    }

    private static void promoteFinished(MutableRole row, String project, String role,
            String topic, long eventId, long finishedMillis, Context context) {
        if (finishedMillis <= row.lastFinishedMillis) return;
        row.project = clean(project);
        row.role = clean(role);
        row.topic = clean(topic);
        row.eventId = eventId;
        row.lastFinishedMillis = finishedMillis;
        if (row.reminderEnabled) {
            row.nextReminderAtMillis = finishedMillis + cadenceMillis(context);
        } else {
            row.nextReminderAtMillis = 0L;
        }
    }

    private static Map<String, MutableRole> load(Context context) {
        Map<String, MutableRole> rows = new HashMap<>();
        if (context == null) return rows;
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ROWS, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                MutableRole row = MutableRole.fromJson(json);
                if (!row.key.isEmpty()) rows.put(row.key, row);
            }
        } catch (JSONException exception) {
            DiagnosticLog.warn(context, "idle_process", "state_parse_failed",
                    "error", exception.getClass().getSimpleName());
        }
        return rows;
    }

    private static void save(Context context, Map<String, MutableRole> rows) {
        if (context == null) return;
        JSONArray array = new JSONArray();
        for (MutableRole row : rows.values()) array.put(row.toJson());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ROWS, array.toString()).apply();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class MutableRole {
        final String key;
        String project = "";
        String role = "";
        String topic = "";
        long lastFinishedMillis;
        long eventId;
        long dismissedThroughMillis;
        boolean reminderEnabled;
        long nextReminderAtMillis;
        long pendingEndMillis;
        long pendingEventId;

        MutableRole(String key) {
            this.key = clean(key);
        }

        IdleRole freeze() {
            return new IdleRole(key, project, role, topic, lastFinishedMillis, eventId,
                    reminderEnabled, nextReminderAtMillis);
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("key", key);
                json.put("project", project);
                json.put("role", role);
                json.put("topic", topic);
                json.put("last_finished", lastFinishedMillis);
                json.put("event_id", eventId);
                json.put("dismissed_through", dismissedThroughMillis);
                json.put("reminder", reminderEnabled);
                json.put("next_reminder", nextReminderAtMillis);
                json.put("pending_end", pendingEndMillis);
                json.put("pending_event", pendingEventId);
            } catch (JSONException ignored) {
            }
            return json;
        }

        static MutableRole fromJson(JSONObject json) {
            MutableRole row = new MutableRole(json.optString("key", ""));
            row.project = clean(json.optString("project", ""));
            row.role = clean(json.optString("role", ""));
            row.topic = clean(json.optString("topic", ""));
            row.lastFinishedMillis = json.optLong("last_finished", 0L);
            row.eventId = json.optLong("event_id", 0L);
            row.dismissedThroughMillis = json.optLong("dismissed_through", 0L);
            row.reminderEnabled = json.optBoolean("reminder", false);
            row.nextReminderAtMillis = json.optLong("next_reminder", 0L);
            row.pendingEndMillis = json.optLong("pending_end", 0L);
            row.pendingEventId = json.optLong("pending_event", 0L);
            return row;
        }
    }
}
