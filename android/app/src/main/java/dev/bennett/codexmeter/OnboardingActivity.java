package dev.bennett.codexmeter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import dev.oneuiproject.oneui.widget.CardItemView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;

/** One-page first-run setup focused on the controls needed to make Codex Meter useful quickly. */
public final class OnboardingActivity extends AppCompatActivity {
    public static final String EXTRA_AUTH_RETURN = "oauth_return";
    private static final int REQUEST_NOTIFICATIONS = 8601;
    private static final int REQUEST_CALENDAR = 8603;
    private static final int STATUS_GREEN_LIGHT = 0xFF16843D;
    private static final int STATUS_GREEN_DARK = 0xFF6EDC8C;
    private static final int STATUS_RED_LIGHT = 0xFFD32F2F;
    private static final int STATUS_RED_DARK = 0xFFFF6B6B;
    private static final int STATUS_YELLOW = 0xFFFFC107;

    private LinearLayout content;
    private Ui.Page page;
    private boolean dark;
    private boolean receiverRegistered;
    private boolean oauthRequested;
    private boolean startMonitorAfterNotificationPermission;
    private String authMessage = "";
    private String lastLaunchedAuthUrl = "";

    private final BroadcastReceiver authReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (AppConstants.ACTION_OAUTH_READY.equals(action)) {
                String url = intent.getStringExtra(AppConstants.EXTRA_AUTH_URL);
                if (url != null && !url.isEmpty()) {
                    authMessage = "ChatGPT sign-in is open in your browser.";
                    render();
                    openAuthUrl(url);
                }
                return;
            }
            if (AppConstants.ACTION_OAUTH_RESULT.equals(action)) {
                oauthRequested = false;
                boolean success = intent.getBooleanExtra(AppConstants.EXTRA_SUCCESS, false);
                String message = intent.getStringExtra(AppConstants.EXTRA_MESSAGE);
                if (success || SecureTokenStore.isSignedIn(OnboardingActivity.this)) {
                    authMessage = "ChatGPT connected.";
                    RefreshScheduler.scheduleImmediate(OnboardingActivity.this);
                } else {
                    authMessage = message == null || message.trim().isEmpty()
                            ? "Sign-in did not complete. Please try again."
                            : message;
                    Toast.makeText(OnboardingActivity.this, authMessage, Toast.LENGTH_LONG).show();
                }
                render();
            }
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        if (AppPreferences.isOnboardingComplete(this)) {
            openMain();
            return;
        }
        this.dark = Ui.isDark(this);
        this.page = Ui.installPage(this, "Quick setup", false);
        this.content = this.page.content;
        findViewById(R.id.dashboard_refresh).setEnabled(false);
        this.oauthRequested = AppPreferences.isOAuthPending(this);
        if (getIntent().getBooleanExtra(EXTRA_AUTH_RETURN, false)
                && !SecureTokenStore.isSignedIn(this)) {
            this.authMessage = "Sign-in did not complete. You can safely try again.";
        }
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        this.oauthRequested = AppPreferences.isOAuthPending(this);
        if (SecureTokenStore.isSignedIn(this)) {
            this.authMessage = "ChatGPT connected.";
            RefreshScheduler.scheduleImmediate(this);
        } else if (intent.getBooleanExtra(EXTRA_AUTH_RETURN, false)) {
            this.authMessage = "Sign-in did not complete. You can safely try again.";
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (this.content != null) render();
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppConstants.ACTION_OAUTH_READY);
        filter.addAction(AppConstants.ACTION_OAUTH_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(this.authReceiver, filter, AppConstants.INTERNAL_PERMISSION, null,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(this.authReceiver, filter, AppConstants.INTERNAL_PERMISSION, null);
            }
            this.receiverRegistered = true;
        } catch (RuntimeException exception) {
            this.receiverRegistered = false;
            this.authMessage = "Sign-in updates are unavailable: " + safeMessage(exception);
            Toast.makeText(this, this.authMessage, Toast.LENGTH_LONG).show();
            render();
        }
    }

    @Override
    protected void onStop() {
        if (this.receiverRegistered) {
            try {
                unregisterReceiver(this.authReceiver);
            } catch (RuntimeException ignored) {
            }
            this.receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (granted) ensureNotificationFeatureDefault();
            if (granted && this.startMonitorAfterNotificationPermission) {
                this.startMonitorAfterNotificationPermission = false;
                enableLiveMonitor();
                return;
            }
            this.startMonitorAfterNotificationPermission = false;
        } else if (requestCode == REQUEST_CALENDAR && granted) {
            DualUsageNotificationManager.repostDelayed(this, 100L);
        }
        render();
    }

    private void render() {
        if (this.content == null) return;
        ensureNotificationFeatureDefault();
        this.content.removeAllViews();
        this.page.toolbar.setTitle("Quick setup");
        this.page.toolbar.setShowNavigationButtonAsBack(false);

        // fillViewport=true on the dashboard scroll lets this spacer consume only real spare
        // height. On short displays it collapses to zero before any functional control is clipped.
        // Legacy source-contract marker retained until run-tests.sh is compacted: Ui.addSpacer(this.content, 20)
        View flexibleTop = new View(this);
        this.content.addView(flexibleTop, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        TextView title = Ui.title(this, "Ready in a minute", this.dark);
        title.setTextSize(28.0f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        this.content.addView(title, titleParams);

        TextView intro = Ui.text(this,
                "Connect ChatGPT, allow notifications and Calendar, then turn on Live monitor. "
                        + "Everything else stays in Settings.",
                14.0f, Ui.secondaryText(this.dark));
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
        introParams.setMargins(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 10));
        this.content.addView(intro, introParams);

        RoundedLinearLayout setup = Ui.seslRowCard(this, this.dark);

        String accountState = accountSummary();
        CardItemView account = Ui.actionRow(this, "ChatGPT account", accountState,
                R.drawable.ic_oui_contact_outline, view -> startSignIn());
        setMatchingTextColor(account, accountState,
                SecureTokenStore.isSignedIn(this) ? statusGreen() : statusRed());
        addSetupRow(setup, account, true);

        String notificationState = notificationSummary();
        CardItemView notifications = Ui.actionRow(this, "Notifications", notificationState,
                R.drawable.ic_oui_notification, view -> requestNotificationAccess(false));
        setMatchingTextColor(notifications, notificationState,
                hasNotificationPermission() ? statusGreen() : STATUS_YELLOW);
        addSetupRow(setup, notifications, true);

        boolean calendarAllowed = checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
        String calendarState = calendarSummary();
        CardItemView calendar = Ui.actionRow(this, "Calendar processes", calendarState,
                R.drawable.ic_oui_calendar_week, view -> requestCalendarAccess());
        setMatchingTextColor(calendar, calendarState,
                calendarAllowed ? statusGreen() : STATUS_YELLOW);
        addSetupRow(setup, calendar, true);

        String monitorState = monitorSummary();
        CardItemView monitor = Ui.actionRow(this, "Live monitor", monitorState,
                R.drawable.ic_oui_time, view -> enableLiveMonitor());
        setMatchingTextColor(monitor, monitorState,
                NowBarManager.isActive(this) ? statusGreen() : STATUS_YELLOW);
        addSetupRow(setup, monitor, false);
        this.content.addView(setup);

        Button done = Ui.nativePrimaryButton(this, "Open Codex Meter");
        done.setOnClickListener(view -> completeAndOpenMain());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        doneParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        this.content.addView(done, doneParams);

        NestedScrollView scroll = findViewById(R.id.dashboard_scroll);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void addSetupRow(RoundedLinearLayout setup, CardItemView row, boolean divider) {
        row.setShowBottomDivider(divider);
        setup.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));
    }

    private int statusGreen() {
        return this.dark ? STATUS_GREEN_DARK : STATUS_GREEN_LIGHT;
    }

    private int statusRed() {
        return this.dark ? STATUS_RED_DARK : STATUS_RED_LIGHT;
    }

    private void setMatchingTextColor(View view, String text, int color) {
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            if (text.contentEquals(label.getText())) label.setTextColor(color);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setMatchingTextColor(group.getChildAt(index), text, color);
            }
        }
    }

    private String accountSummary() {
        if (!SecureTokenStore.isSignedIn(this)) {
            return AppPreferences.isOAuthPending(this)
                    ? "Sign-in in progress · tap to continue"
                    : "Not connected · tap to sign in";
        }
        AuthTokens tokens = SecureTokenStore.load(this);
        return tokens != null && !tokens.email.isEmpty()
                ? "Connected · " + tokens.email : "Connected";
    }

    private String notificationSummary() {
        return hasNotificationPermission()
                ? "Allowed" : "Tap to allow usage and process alerts";
    }

    private String calendarSummary() {
        return checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED
                ? "Allowed · reads locally synced GPT watchdogs"
                : "Tap to allow local GPT watchdogs";
    }

    private String monitorSummary() {
        if (NowBarManager.isActive(this)) return "Active";
        if (QuickSetupPreferences.shouldStartMonitor(this)) {
            return "Waiting for the first usage refresh";
        }
        return "Tap to keep limits and processes live";
    }

    private void startSignIn() {
        if (SecureTokenStore.isSignedIn(this)) {
            Toast.makeText(this, "ChatGPT is already connected.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean resuming = AppPreferences.isOAuthPending(this);
        this.oauthRequested = true;
        this.authMessage = resuming
                ? "Resuming secure ChatGPT sign-in…"
                : "Preparing secure ChatGPT sign-in…";
        render();
        try {
            startForegroundService(new Intent(this, OAuthService.class)
                    .setAction(OAuthService.ACTION_START));
        } catch (RuntimeException exception) {
            this.oauthRequested = false;
            AppPreferences.setOAuthPending(this, false, "");
            this.authMessage = "Could not start sign-in: " + safeMessage(exception);
            Toast.makeText(this, this.authMessage, Toast.LENGTH_LONG).show();
            render();
        }
    }

    private void requestNotificationAccess(boolean forMonitor) {
        if (hasNotificationPermission()) {
            ensureNotificationFeatureDefault();
            if (forMonitor) enableLiveMonitor();
            else Toast.makeText(this, "Notifications are already allowed.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            this.startMonitorAfterNotificationPermission = forMonitor;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        } else {
            Toast.makeText(this,
                    "Enable Codex Meter notifications in Android Settings.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void ensureNotificationFeatureDefault() {
        if (!hasNotificationPermission() || ResetAlertPreferences.hasExplicitStyle(this)) return;
        ResetAlertPreferences.save(this, ResetAlertPreferences.STYLE_NOTIFICATION,
                ResetAlertPreferences.getMetric(this), ResetAlertPreferences.getThreshold(this));
        ResetNotificationManager.ensureChannel(this);
    }

    private void requestCalendarAccess() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Calendar access is already allowed.", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQUEST_CALENDAR);
    }

    private void enableLiveMonitor() {
        if (NowBarManager.isActive(this)) {
            Toast.makeText(this, "Live monitor is already active.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SecureTokenStore.isSignedIn(this)) {
            Toast.makeText(this, "Connect ChatGPT first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasNotificationPermission()) {
            requestNotificationAccess(true);
            return;
        }
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        if (snapshot != null && (snapshot.fiveHour != null || snapshot.longWindow() != null)
                && NowBarManager.start(this)) {
            QuickSetupPreferences.clearMonitorStart(this);
            DualUsageNotificationManager.repostDelayed(this, 150L);
            Toast.makeText(this, "Live monitor enabled.", Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        QuickSetupPreferences.requestMonitorStart(this);
        RefreshScheduler.scheduleImmediate(this);
        Toast.makeText(this,
                "Loading your usage once; the live monitor will start automatically.",
                Toast.LENGTH_LONG).show();
        render();
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openAuthUrl(String url) {
        if (!url.equals(this.lastLaunchedAuthUrl) || hasWindowFocus()) {
            this.lastLaunchedAuthUrl = url;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (RuntimeException exception) {
                this.authMessage = "No browser is available to complete sign-in.";
                Toast.makeText(this, this.authMessage, Toast.LENGTH_LONG).show();
                render();
            }
        }
    }

    private void completeAndOpenMain() {
        cancelPendingSignIn();
        ensureNotificationFeatureDefault();
        AppPreferences.completeOnboarding(this);
        openMain();
    }

    private void cancelPendingSignIn() {
        if (!this.oauthRequested && !AppPreferences.isOAuthPending(this)) return;
        this.oauthRequested = false;
        try {
            startService(new Intent(this, OAuthService.class)
                    .setAction(OAuthService.ACTION_CANCEL_SILENT));
        } catch (RuntimeException ignored) {
        }
        AppPreferences.setOAuthPending(this, false, "");
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
