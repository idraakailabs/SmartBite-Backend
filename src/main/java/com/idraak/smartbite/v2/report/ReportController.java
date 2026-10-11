package com.idraak.smartbite.v2.report;

import com.idraak.smartbite.v2.auth.AuthenticatedUser;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.model.Enums.ReportReason;
import com.idraak.smartbite.v2.store.DocumentStore;
import com.idraak.smartbite.v2.store.StoreUnavailableException;
import com.idraak.smartbite.v2.suggestion.SuggestionController;
import com.idraak.smartbite.v2.validation.SafeText;
import com.idraak.smartbite.v2.web.V2RateLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /api/v2/reports} (D-019). Stores exactly uid, suggestion_name, reason, created_at and app_version;
 * nothing else from the request or the connection.
 */
@RestController
public class ReportController {

    public static final String COLLECTION = "suggestion_reports";

    public record ReportRequest(
            @NotBlank @SafeText @Size(max = 80) String suggestionName,
            @NotNull ReportReason reason,
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[0-9]+\\.[0-9]+\\.[0-9]+([-+][A-Za-z0-9.]+)*$") String appVersion
    ) {}

    public record ReportResponse(String reportId, Instant createdAt) {}

    private final DocumentStore store;
    private final V2RateLimits limits;
    private final Clock clock;

    public ReportController(DocumentStore store, V2RateLimits limits, Clock clock) {
        this.store = store;
        this.limits = limits;
        this.clock = clock;
    }

    @PostMapping(path = "/api/v2/reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportResponse> report(AuthenticatedUser user, @Valid @RequestBody ReportRequest request,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String key = SuggestionController.validKey(idempotencyKey);
        limits.check(V2RateLimits.Route.REPORTS, user.uid());
        String id = key == null ? "rpt_" + UUID.randomUUID().toString().replace("-", "") : "rpt_" + hash(user.uid() + "|" + key);
        Instant now = clock.instant();
        Map<String, Object> doc = new HashMap<>();
        doc.put("uid", user.uid());
        doc.put("suggestion_name", request.suggestionName().strip());
        doc.put("reason", request.reason().name().toLowerCase());
        doc.put("created_at", now);
        doc.put("app_version", request.appVersion());
        try {
            if (store.create(COLLECTION, id, doc)) {
                return ResponseEntity.status(HttpStatus.CREATED).body(new ReportResponse(id, now));
            }
            Instant original = store.get(COLLECTION, id).map(d -> d.get("created_at")).filter(Instant.class::isInstance)
                    .map(Instant.class::cast).orElse(now);
            return ResponseEntity.ok(new ReportResponse(id, original));
        } catch (StoreUnavailableException e) {
            throw ApiException.providerUnavailable(10, e);
        }
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
