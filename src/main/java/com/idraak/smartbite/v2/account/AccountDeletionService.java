package com.idraak.smartbite.v2.account;

import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.entitlement.BillingCustomerDeleter;
import com.idraak.smartbite.v2.entitlement.EntitlementService;
import com.idraak.smartbite.v2.entitlement.EntitlementUnavailableException;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.quota.QuotaService;
import com.idraak.smartbite.v2.report.ReportController;
import com.idraak.smartbite.v2.store.DocumentStore;
import com.idraak.smartbite.v2.store.StoreUnavailableException;
import com.idraak.smartbite.v2.suggestion.SuggestionService;
import com.idraak.smartbite.v2.web.UidHasher;
import com.idraak.smartbite.v2.web.V2RateLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server side of account deletion (README §11, TECH-012): deletes the quota counter, removes the uid from the
 * caller's reports (rows kept for moderation, D-021 item 7), evicts in-memory state and deletes the RevenueCat
 * customer. A RevenueCat failure leaves a {@code deletion_requests/{uid}} record that a scheduled job retries for
 * {@code retryWindow}; after that it is marked {@code abandoned} and logged as an error for manual action.
 * The Firebase Auth user and {@code users/{uid}} stay the app's job.
 */
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    static final String REQUESTS = "deletion_requests";

    public record Steps(String revenuecatCustomer, String quotaCounter, String reports) {}

    public record AccountDeletionResponse(String status, Steps steps, int reportsAnonymized) {}

    private final DocumentStore store;
    private final QuotaService quota;
    private final BillingCustomerDeleter billing;
    private final EntitlementService entitlements;
    private final V2RateLimits limits;
    private final SuggestionService suggestions;
    private final UidHasher hasher;
    private final V2Properties.Deletion cfg;
    private final Clock clock;

    public AccountDeletionService(DocumentStore store, QuotaService quota, BillingCustomerDeleter billing,
                                  EntitlementService entitlements, V2RateLimits limits, SuggestionService suggestions,
                                  UidHasher hasher, V2Properties.Deletion cfg, Clock clock) {
        this.store = store;
        this.quota = quota;
        this.billing = billing;
        this.entitlements = entitlements;
        this.limits = limits;
        this.suggestions = suggestions;
        this.hasher = hasher;
        this.cfg = cfg;
        this.clock = clock;
    }

    public AccountDeletionResponse delete(String uid) {
        try {
            Instant now = clock.instant();
            Map<String, Object> request = new HashMap<>();
            request.put("status", "pending");
            request.put("created_at", now);
            request.put("attempts", 0L);
            store.set(REQUESTS, uid, request);

            String quotaOutcome = quota.delete(uid) ? "deleted" : "not_found";
            int anonymized = anonymizeReports(uid);
            entitlements.evict(uid);
            limits.evict(uid);
            suggestions.evict(uid);

            String rc = deleteBillingCustomer(uid);
            Steps steps = new Steps(rc, quotaOutcome, anonymized > 0 ? "anonymized" : "none");
            if ("retry_scheduled".equals(rc)) {
                markAttempt(uid, "revenuecat_unavailable");
                log.warn("account deletion pending retry uid_hash={}", hasher.hash(uid));
                return new AccountDeletionResponse("pending_retry", steps, anonymized);
            }
            store.delete(REQUESTS, uid);
            log.info("account deletion completed uid_hash={} reports_anonymized={}", hasher.hash(uid), anonymized);
            return new AccountDeletionResponse("completed", steps, anonymized);
        } catch (StoreUnavailableException e) {
            throw ApiException.providerUnavailable(10, e);
        }
    }

    /** Scheduled: retries pending requests until the window ends. */
    public int retryPending() {
        int done = 0;
        try {
            for (String uid : store.findIds(REQUESTS, "status", "pending", 100)) {
                Map<String, Object> doc = store.get(REQUESTS, uid).orElse(null);
                if (doc == null) continue;
                Instant created = doc.get("created_at") instanceof Instant i ? i : clock.instant();
                if (Duration.between(created, clock.instant()).compareTo(cfg.retryWindow()) > 0) {
                    store.updateFields(REQUESTS, uid, Map.of("status", "abandoned", "updated_at", clock.instant()));
                    log.error("account deletion abandoned after retry window; manual RevenueCat deletion needed uid_hash={}", hasher.hash(uid));
                    continue;
                }
                quota.delete(uid);
                anonymizeReports(uid);
                if ("retry_scheduled".equals(deleteBillingCustomer(uid))) {
                    markAttempt(uid, "revenuecat_unavailable");
                } else {
                    store.delete(REQUESTS, uid);
                    done++;
                }
            }
        } catch (StoreUnavailableException | ApiException e) {
            log.warn("deletion retry run failed ({}); next run will retry", e.getClass().getSimpleName());
        }
        return done;
    }

    private String deleteBillingCustomer(String uid) {
        try {
            return billing.deleteCustomer(uid) == BillingCustomerDeleter.Outcome.DELETION_QUEUED ? "deletion_queued" : "not_found";
        } catch (EntitlementUnavailableException e) {
            return "retry_scheduled";
        }
    }

    private int anonymizeReports(String uid) {
        int total = 0;
        List<String> ids;
        do {
            ids = store.findIds(ReportController.COLLECTION, "uid", uid, 200);
            for (String id : ids) {
                store.updateFields(ReportController.COLLECTION, id, Map.of("uid", DocumentStore.DELETE_FIELD));
                total++;
            }
        } while (ids.size() == 200);
        return total;
    }

    private void markAttempt(String uid, String code) {
        store.transaction(REQUESTS, uid, current -> {
            Map<String, Object> d = new HashMap<>(current.orElse(Map.of("status", "pending", "created_at", clock.instant())));
            long attempts = d.get("attempts") instanceof Number n ? n.longValue() : 0;
            d.put("attempts", attempts + 1);
            d.put("last_error_code", code);
            d.put("updated_at", clock.instant());
            return DocumentStore.TxResult.write(d, null);
        });
    }
}
