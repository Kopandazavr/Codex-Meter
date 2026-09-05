package dev.bennett.codexmeter;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/** Tiny permission trampoline used by the Now Bar settings page. */
public final class CalendarPermissionActivity extends AppCompatActivity {
    private static final int REQUEST_READ_CALENDAR = 8603;

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Calendar access is already allowed.", Toast.LENGTH_SHORT).show();
            DualUsageNotificationManager.repostDelayed(this, 100L);
            finish();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQUEST_READ_CALENDAR);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_READ_CALENDAR) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, granted
                        ? "Calendar access allowed."
                        : "Calendar access was not allowed.",
                granted ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        if (granted) DualUsageNotificationManager.repostDelayed(this, 100L);
        finish();
    }
}
