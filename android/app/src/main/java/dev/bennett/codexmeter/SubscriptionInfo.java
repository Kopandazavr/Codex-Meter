package dev.bennett.codexmeter;

final class SubscriptionInfo {
    final String planType;
    final long activeUntilMillis;
    final boolean willRenew;
    final boolean hasWillRenew;
    final long fetchedAtMillis;

    SubscriptionInfo(String planType, long activeUntilMillis, boolean willRenew,
            boolean hasWillRenew, long fetchedAtMillis) {
        this.planType = safe(planType);
        this.activeUntilMillis = Math.max(0L, activeUntilMillis);
        this.willRenew = willRenew;
        this.hasWillRenew = hasWillRenew;
        this.fetchedAtMillis = Math.max(0L, fetchedAtMillis);
    }

    boolean hasDisplayableData() {
        return !planType.isEmpty() || activeUntilMillis > 0L;
    }

    String displayPlanName() {
        String normalized = planType.trim().toLowerCase(java.util.Locale.US);
        if (normalized.contains("plus")) return "ChatGPT Plus";
        if (normalized.contains("pro")) return "ChatGPT Pro";
        if (normalized.contains("team") || normalized.contains("business")) {
            return "ChatGPT Business";
        }
        if (normalized.contains("enterprise")) return "ChatGPT Enterprise";
        if (normalized.isEmpty()) return "ChatGPT";
        return "ChatGPT " + Character.toUpperCase(normalized.charAt(0))
                + normalized.substring(1);
    }

    static SubscriptionInfo fromJwt(AuthTokens tokens, long now) {
        if (tokens == null) return null;
        JwtClaims claims = JwtClaims.fromTokens(tokens.accessToken, tokens.idToken);
        SubscriptionInfo info = new SubscriptionInfo(claims.planType,
                claims.subscriptionActiveUntilMillis, false, false, now);
        return info.hasDisplayableData() ? info : null;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
