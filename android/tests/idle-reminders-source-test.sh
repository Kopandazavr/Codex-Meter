#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/dev/bennett/codexmeter"

# Finished watchdogs become durable per-role idle state instead of disappearing.
grep -q 'recentlyFinished' "$SRC/CalendarProcessReader.java"
grep -q 'IdleProcessState.synchronize' "$SRC/DualUsageNotificationManager.java"
grep -q 'dismissedThroughMillis' "$SRC/IdleProcessState.java"
grep -q 'row.reminderEnabled = !row.reminderEnabled' "$SRC/IdleProcessState.java"

# Cadence stays intentionally bounded to the approved 5/10 minute choices.
grep -q 'idle_reminder_cadence_entries' "$ROOT/app/src/main/res/values/settings_arrays.xml"
grep -q '<item>5</item>' "$ROOT/app/src/main/res/values/settings_arrays.xml"
grep -q '<item>10</item>' "$ROOT/app/src/main/res/values/settings_arrays.xml"
grep -q 'DEFAULT_CADENCE_MINUTES = 5' "$SRC/IdleProcessState.java"

# Reminder alarms are keyed per role and stop when the same role becomes active.
grep -q 'ACTION_FIRE' "$SRC/IdleReminderManager.java"
grep -q 'IdleProcessState.isRoleActive' "$SRC/IdleReminderManager.java"
grep -q 'cancelAlarm(context, key)' "$SRC/IdleReminderManager.java"

# Idle rows expose dismiss + bell actions, while overlay taps have explicit strong haptics.
grep -q 'notification_process_dismiss' "$ROOT/app/src/main/res/layout/notification_process_row.xml"
grep -q 'notification_process_reminder' "$ROOT/app/src/main/res/layout/notification_process_row.xml"
grep -q 'FLAG_WATCH_OUTSIDE_TOUCH' "$SRC/IdleReminderOverlayService.java"
grep -q 'VibrationEffect.createOneShot(160L, 255)' "$SRC/IdleReminderOverlayService.java"
grep -q 'IdleReminderOverlayService' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'SYSTEM_ALERT_WINDOW' "$ROOT/app/src/main/AndroidManifest.xml"

# Reboot/package replacement restores local reminder scheduling.
grep -q 'IdleReminderManager.restore(context)' "$SRC/BootReceiver.java"

echo 'Idle reminder lifecycle/source regression contract passed.'
