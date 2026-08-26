package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class JwtClaims {
    public final String accountId;
    public final String email;
    public final String planType;
    public final long subscriptionActiveUntilMillis;

    private JwtClaims(String accountId, String email, String planType,
            long subscriptionActiveUntilMillis) {
        this.accountId = safe(accountId);
        this.email = safe(email);
        this.planType = safe(planType);
        this.subscriptionActiveUntilMillis = Math.max(0L, subscriptionActiveUntilMillis);
    }

    public static JwtClaims fromTokens(String accessToken, String idToken) {
        JwtClaims access = parse(accessToken);
        JwtClaims id = parse(idToken);
        return new JwtClaims(
                firstNonEmpty(access.accountId, id.accountId),
                firstNonEmpty(access.email, id.email),
                firstNonEmpty(access.planType, id.planType),
                access.subscriptionActiveUntilMillis > 0L
                        ? access.subscriptionActiveUntilMillis
                        : id.subscriptionActiveUntilMillis);
    }

    public static JwtClaims parse(String token) {
        JSONArray organizations;
        JSONObject firstOrganization;
        if (token == null) return empty();
        String[] parts = token.split("\\.");
        if (parts.length != 3) return empty();
        try {
            JSONObject root = new JSONObject(new String(
                    Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8));
            JSONObject auth = root.optJSONObject("https://api.openai.com/auth");

            String accountId = root.optString("chatgpt_account_id", "");
            if (accountId.isEmpty() && auth != null) {
                accountId = auth.optString("chatgpt_account_id", "");
            }
            if (accountId.isEmpty()
                    && (organizations = root.optJSONArray("organizations")) != null
                    && organizations.length() > 0
                    && (firstOrganization = organizations.optJSONObject(0)) != null) {
                accountId = firstOrganization.optString("id", "");
            }

            String planType = firstNonEmpty(
                    root.optString("chatgpt_plan_type", ""),
                    auth == null ? "" : auth.optString("chatgpt_plan_type", ""),
                    root.optString("plan_type", ""));
            Object activeUntil = firstPresent(
                    root, auth,
                    "chatgpt_subscription_active_until",
                    "subscription_active_until");
            long activeUntilMillis = parseTimestamp(activeUntil);
            return new JwtClaims(accountId, root.optString("email", ""),
                    planType, activeUntilMillis);
        } catch (Exception ignored) {
            return empty();
        }
    }

    private static Object firstPresent(JSONObject root, JSONObject auth, String... keys) {
        for (String key : keys) {
            if (root != null && root.has(key) && !root.isNull(key)) return root.opt(key);
            if (auth != null && auth.has(key) && !auth.isNull(key)) return auth.opt(key);
        }
        return null;
    }

    private static long parseTimestamp(Object value) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) {
            long raw = ((Number) value).longValue();
            return raw > 0L && raw < 100000000000L ? raw * 1000L : Math.max(0L, raw);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return 0L;
        try {
            long raw = Long.parseLong(text);
            return raw > 0L && raw < 100000000000L ? raw * 1000L : Math.max(0L, raw);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static JwtClaims empty() {
        return new JwtClaims("", "", "", 0L);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : safe(second);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        if (remainder != 0) {
            StringBuilder padded = new StringBuilder(value);
            while (remainder < 4) {
                padded.append('=');
                remainder++;
            }
            return padded.toString();
        }
        return value;
    }
}
