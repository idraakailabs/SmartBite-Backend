package com.idraak.smartbite.v2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * All v2 settings. Defaults live in {@code application.yaml}; every secret comes from an environment variable
 * (see docs/ops/ENVIRONMENT.md). Nothing here has a real secret as a default.
 */
@ConfigurationProperties(prefix = "smartbite.v2")
public record V2Properties(
        Auth auth,
        Stores stores,
        Limits limits,
        Logging logging,
        Quota quota,
        Entitlement entitlement,
        Ai ai,
        Places places,
        Recipes recipes,
        Deletion deletion
) {

    public record Auth(boolean fakeTokensEnabled, Duration reauthMaxAge, int preAuthPerIpPerMinute) {}

    /**
     * {@code mode} is {@code firestore} (Admin SDK) or {@code in-memory}. In-memory is refused at startup unless
     * {@code allowInMemory} is true (test and local development only).
     */
    public record Stores(String mode, boolean allowInMemory, String collectionPrefix,
                         String firebaseProjectId, String serviceAccountBase64, Duration operationTimeout) {
        public boolean firestore() {
            return "firestore".equalsIgnoreCase(mode);
        }
    }

    public record Limits(int maxBodyBytes,
                         int suggestionsPerMinute, int suggestionsPerHour,
                         int recipesPerMinute, int recipesPerHour,
                         int reportsPerHour, int reportsPerDay,
                         int quotaPerMinute, int deletePerHour) {}

    public record Logging(String uidHashKey) {}

    public record Quota(int freeDailySuggestions, Duration reservationTtl, Duration timezoneRepinAfter,
                        boolean remoteConfigSyncEnabled, Duration remoteConfigRefresh,
                        Duration idempotentReplayTtl) {}

    /** {@code provider} is {@code revenuecat} or {@code fake} (fake: everyone is free; test/local only). */
    public record Entitlement(String provider, String baseUrl, String secretApiKey, String projectId,
                              String lookupKey, Duration ttlPremium, Duration ttlFree, Duration staleIfError,
                              Duration refreshMinInterval, Duration connectTimeout, Duration readTimeout) {}

    public record Ai(Duration suggestionDeadline, Duration recipeDeadline, Duration connectTimeout,
                     Duration readTimeout, Duration recipeReadTimeout, Duration minRemainingForAttempt,
                     String groqBaseUrl, String groqApiKey, String geminiBaseUrl, String geminiApiKey,
                     String promptVersion, String recipePromptVersion) {}

    /** {@code provider} is {@code google}, {@code fake} or {@code disabled}. */
    public record Places(String provider, String apiKey, String baseUrl,
                         int nearbyGlobalDailyCap, int textGlobalDailyCap,
                         int nearbyPerUidDaily, int textPerUidDaily,
                         Duration cacheTtl, boolean verifyAlternatives,
                         Duration connectTimeout, Duration readTimeout, Duration stageBudget,
                         List<String> includedTypes) {}

    public record Recipes(boolean stepsPremium, int freePreviewSteps, Duration cacheTtl) {}

    public record Deletion(Duration retryInterval, Duration retryWindow) {}
}
