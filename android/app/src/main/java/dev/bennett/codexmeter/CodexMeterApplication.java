package dev.bennett.codexmeter;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.os.Bundle;

/** Installs process/screen diagnostics and enforces the small app's canonical automatic defaults. */
public final class CodexMeterApplication extends Application
        implements Application.ActivityLifecycleCallbacks {
    @Override
    public void onCreate() {
        super.onCreate();
        normalizeAutomaticDefaults();
        DiagnosticLog.install(this);
        registerActivityLifecycleCallbacks(this);
        DiagnosticLog.info(this, "process", "application_started");
        DualUsageNotificationManager.repostDelayed(this, 350L);
    }

    private void normalizeAutomaticDefaults() {
        if (!AppPreferences.getRefreshOnLaunch(this)) {
            AppPreferences.setRefreshOnLaunch(this, true);
        }
        if (!AppPreferences.getAutomaticRefresh(this)) {
            AppPreferences.setAutomaticRefresh(this, true);
        }
        if (!UsagePacePreferences.isEnabled(this)) {
            UsagePacePreferences.setEnabled(this, true);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        DiagnosticLog.info(this, "process", "memory_trimmed", "level", level,
                "ui_hidden", level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        DiagnosticLog.warn(this, "process", "low_memory");
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        DiagnosticLog.info(this, "screen", "created",
                "activity", activity.getClass().getSimpleName(),
                "restored", state != null);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        DiagnosticLog.info(this, "screen", "resumed",
                "activity", activity.getClass().getSimpleName());
        HomeVersionLabel.apply(activity);
        // Settings can start the native monitor from cached usage without a network refresh.
        // Re-assert the compact shade presentation whenever the user returns to a screen.
        DualUsageNotificationManager.repostDelayed(this, 200L);
    }

    @Override
    public void onActivityStopped(Activity activity) {
        DiagnosticLog.info(this, "screen", "stopped",
                "activity", activity.getClass().getSimpleName(),
                "changing_configuration", activity.isChangingConfigurations());
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
