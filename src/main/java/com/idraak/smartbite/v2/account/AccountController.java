package com.idraak.smartbite.v2.account;

import com.idraak.smartbite.v2.auth.AuthenticatedUser;
import com.idraak.smartbite.v2.entitlement.EntitlementService;
import com.idraak.smartbite.v2.quota.QuotaService;
import com.idraak.smartbite.v2.quota.QuotaSnapshot;
import com.idraak.smartbite.v2.validation.IanaTimezone;
import com.idraak.smartbite.v2.web.V2RateLimits;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

@RestController
@RequestMapping("/api/v2/me")
public class AccountController {

    public record QuotaResponse(QuotaSnapshot quota) {}

    private final QuotaService quota;
    private final EntitlementService entitlements;
    private final V2RateLimits limits;

    public AccountController(QuotaService quota, EntitlementService entitlements, V2RateLimits limits) {
        this.quota = quota;
        this.entitlements = entitlements;
        this.limits = limits;
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
