package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;

final class SubscriptionStore {
    private static final String PREFS = "codex_meter_subscription_v1";
    private static final String KEY_PLAN = "plan_type";
    private static final String KEY_ACTIVE_UNTIL = "active_until";
    private static final String KEY_WILL_RENEW = "will_renew";
    private static final String KEY_HAS_WILL_RENEW = "has_will_renew";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final String KEY_LAST_ATTEMPT = "last_attempt";

    private SubscriptionStore() {
    }

    static SubscriptionInfo load(Context context) {
        SharedPreferences prefs = prefs(context);
        SubscriptionInfo info = new SubscriptionInfo(
                prefs.getString(KEY_PLAN, ""),
                prefs.getLong(KEY_ACTIVE_UNTIL, 0L),
                prefs.getBoolean(KEY_WILL_RENEW, false),
                prefs.getBoolean(KEY_HAS_WILL_RENEW, false),
                prefs.getLong(KEY_FETCHED_AT, 0L));
        return info.hasDisplayableData() ? info : null;
    }

    static void save(Context context, SubscriptionInfo info) {
        if (context == null || info == null || !info.hasDisplayableData()) return;
        prefs(context).edit()
                .putString(KEY_PLAN, info.planType)
                .putLong(KEY_ACTIVE_UNTIL, info.activeUntilMillis)
                .putBoolean(KEY_WILL_RENEW, info.willRenew)
                .putBoolean(KEY_HAS_WILL_RENEW, info.hasWillRenew)
                .putLong(KEY_FETCHED_AT, info.fetchedAtMillis)
                .apply();
    }

    static void seedFromJwt(Context context, AuthTokens tokens, long now) {
        SubscriptionInfo jwt = SubscriptionInfo.fromJwt(tokens, now);
        if (jwt == null) return;
        SubscriptionInfo cached = load(context);
        if (cached == null) {
            save(context, jwt);
            return;
        }
        String plan = cached.planType.isEmpty() ? jwt.planType : cached.planType;
        long until = cached.activeUntilMillis > 0L
                ? cached.activeUntilMillis : jwt.activeUntilMillis;
        save(context, new SubscriptionInfo(plan, until, cached.willRenew,
                cached.hasWillRenew, Math.max(cached.fetchedAtMillis, jwt.fetchedAtMillis)));
    }

    static long lastAttemptMillis(Context context) {
        return prefs(context).getLong(KEY_LAST_ATTEMPT, 0L);
    }

    static void markAttempt(Context context, long now) {
        prefs(context).edit().putLong(KEY_LAST_ATTEMPT, now).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
