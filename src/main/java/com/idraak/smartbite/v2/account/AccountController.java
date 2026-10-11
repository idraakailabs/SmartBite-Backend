package com.idraak.smartbite.v2.account;

import com.idraak.smartbite.v2.auth.AuthenticatedUser;
import com.idraak.smartbite.v2.auth.IdTokenVerifier;
import com.idraak.smartbite.v2.auth.TokenVerificationException;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.entitlement.EntitlementService;
import com.idraak.smartbite.v2.quota.QuotaService;
import com.idraak.smartbite.v2.quota.QuotaSnapshot;
import com.idraak.smartbite.v2.validation.IanaTimezone;
import com.idraak.smartbite.v2.web.V2RateLimits;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/v2/me")
public class AccountController {

    public record QuotaResponse(QuotaSnapshot quota) {}

    private final QuotaService quota;
    private final EntitlementService entitlements;
    private final V2RateLimits limits;
    private final AccountDeletionService deletion;
    private final IdTokenVerifier verifier;
    private final V2Properties props;
    private final Clock clock;

    public AccountController(QuotaService quota, EntitlementService entitlements, V2RateLimits limits,
                             AccountDeletionService deletion, IdTokenVerifier verifier, V2Properties props, Clock clock) {
        this.quota = quota;
        this.entitlements = entitlements;
        this.limits = limits;
        this.deletion = deletion;
        this.verifier = verifier;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Account deletion, server side. Needs a token issued within reauth-max-age (5 min) that isn't revoked:
     * the app re-authenticates right before calling this, and before deleting the Firebase Auth user.
     */
    @DeleteMapping
    public ResponseEntity<AccountDeletionService.AccountDeletionResponse> deleteMe(AuthenticatedUser user) {
        limits.check(V2RateLimits.Route.DELETE_ME, user.uid());
        AuthenticatedUser fresh;
        try {
            fresh = verifier.verify(user.rawToken(), true);
        } catch (TokenVerificationException e) {
            if (e.kind() == TokenVerificationException.Kind.UNAVAILABLE) throw ApiException.providerUnavailable(10, e);
            throw new ApiException(ErrorCode.REAUTHENTICATION_REQUIRED);
        }
        if (fresh.authTime() == null
                || Duration.between(fresh.authTime(), clock.instant()).compareTo(props.auth().reauthMaxAge()) > 0) {
            throw new ApiException(ErrorCode.REAUTHENTICATION_REQUIRED);
        }
        AccountDeletionService.AccountDeletionResponse result = deletion.delete(fresh.uid());
        return ResponseEntity.status("completed".equals(result.status()) ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }

    @GetMapping("/quota")
    public QuotaResponse quota(AuthenticatedUser user,
                               @RequestParam("timezone") @IanaTimezone String timezone,
                               @RequestHeader(value = "X-Entitlement-Refresh", required = false) Boolean refresh) {
        limits.check(V2RateLimits.Route.QUOTA, user.uid());
        ZoneId zone = ZoneId.of(timezone);
        boolean premium = entitlements.isPremium(user.uid(), Boolean.TRUE.equals(refresh));
        return new QuotaResponse(premium ? quota.premiumSnapshot(zone) : quota.peek(user.uid(), zone));
    }
}
