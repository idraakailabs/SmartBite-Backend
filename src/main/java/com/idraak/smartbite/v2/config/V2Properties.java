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
        Deletion deletion,
        Database database,
        History history
) {

    public record Auth(boolean fakeTokensEnabled, Duration reauthMaxAge, int preAuthPerIpPerMinute) {}

    /**
     * {@code mode} is {@code firestore} (Admin SDK) or {@code in-memory}. In-memory is refused at startup unless
     * {@code allowInMemory} is true (test and local development only).
     */
    /**
     * {@code mode} is {@code firestore} (Admin SDK), {@code postgres} (our own database, {@link Database}) or
     * {@code in-memory}. In-memory is refused at startup unless {@code allowInMemory} is true (test and local only).
     */
    public record Stores(String mode, boolean allowInMemory, String collectionPrefix,
                         String firebaseProjectId, String serviceAccountBase64, Duration operationTimeout) {
        public boolean firestore() {
            return "firestore".equalsIgnoreCase(mode);
        }

        public boolean postgres() {
            return "postgres".equalsIgnoreCase(mode);
        }

        /** Firestore or Postgres: a durable store, so a deployed instance may use it. */
        public boolean durable() {
            return firestore() || postgres();
        }
    }

    public record Limits(int maxBodyBytes, int maxHistoryBodyBytes,
                         int suggestionsPerMinute, int suggestionsPerHour,
                         int recipesPerMinute, int recipesPerHour,
                         int reportsPerHour, int reportsPerDay,
                         int quotaPerMinute, int deletePerHour,
                         int historyPerMinute, int profilePerMinute) {}

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

    /**
     * {@code billingDeletionEnabled} is false on staging: it shares the RevenueCat project with production, so
     * testing account deletion there must not delete a real customer.
     */
    public record Deletion(Duration retryInterval, Duration retryWindow, boolean billingDeletionEnabled) {}

    /**
     * Our own PostgreSQL (D-024). {@code url} is either Render's connection string
     * ({@code postgresql://user:password@host:port/db}) or a JDBC URL; empty means no database, and the endpoints
     * that need it answer 503 {@code FEATURE_UNAVAILABLE}.
     */
    public record Database(String url, String username, String password, int maxPoolSize,
                           Duration connectTimeout, Duration statementTimeout) {
        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }

    /**
     * Cloud meal history (D-024): kept for {@code retentionMonths}; an account that hasn't synced for
     * {@code inactiveMonths} loses its cloud history. Batch and page sizes bound each request.
     */
    public record History(int retentionMonths, int inactiveMonths, int maxBatch, int pageSize, Duration cleanupInterval,
                          int maxProfileBytes) {}
}
