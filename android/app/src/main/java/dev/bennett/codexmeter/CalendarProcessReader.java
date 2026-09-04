package dev.bennett.codexmeter;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Reads active GPT watchdogs from Android's locally synced Calendar Provider. */
final class CalendarProcessReader {
    private static final long LOOKBACK_MS = TimeUnit.HOURS.toMillis(2);
    private static final long LOOKAHEAD_MS = TimeUnit.HOURS.toMillis(2);

    private CalendarProcessReader() {
    }

    static List<CalendarProcess> active(Context context, long nowMillis) {
        List<CalendarProcess> processes = new ArrayList<>();
        if (context == null || context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return processes;
        }

        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, Math.max(0L, nowMillis - LOOKBACK_MS));
        ContentUris.appendId(builder, nowMillis + LOOKAHEAD_MS);
        String[] projection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END
        };
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(builder.build(), projection, null, null,
                CalendarContract.Instances.END + " ASC")) {
            if (cursor == null) return processes;
            int eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID);
            int titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE);
            int descriptionIndex = cursor.getColumnIndexOrThrow(
                    CalendarContract.Instances.DESCRIPTION);
            int beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN);
            int endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END);
            while (cursor.moveToNext()) {
                long begin = cursor.getLong(beginIndex);
                long end = cursor.getLong(endIndex);
                if (begin > nowMillis || end <= nowMillis) continue;
                CalendarProcess process = CalendarProcess.fromEvent(
                        cursor.getLong(eventIdIndex),
                        cursor.getString(titleIndex),
                        cursor.getString(descriptionIndex),
                        begin,
                        end);
                if (process != null) processes.add(process);
            }
        } catch (SecurityException | RuntimeException exception) {
            DiagnosticLog.warn(context, "calendar_process", "read_failed",
                    "error", exception.getClass().getSimpleName());
            processes.clear();
        }
        processes.sort(Comparator.comparingLong(process -> process.endMillis));
        return processes;
    }
}
