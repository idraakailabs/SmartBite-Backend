package com.idraak.smartbite.v2.account;

import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.entitlement.BillingProvider;
import com.idraak.smartbite.v2.entitlement.EntitlementService;
import com.idraak.smartbite.v2.quota.QuotaService;
import com.idraak.smartbite.v2.store.DocumentStore;
import com.idraak.smartbite.v2.suggestion.SuggestionService;
import com.idraak.smartbite.v2.web.UidHasher;
import com.idraak.smartbite.v2.web.V2RateLimits;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;

@Configuration
public class AccountDeletionConfig {

    @Bean
    public AccountDeletionService accountDeletionService(DocumentStore store, QuotaService quota, BillingProvider billing,
                                                         EntitlementService entitlements, V2RateLimits limits,
                                                         SuggestionService suggestions, UidHasher hasher, V2Properties props,
                                                         Clock clock, TaskScheduler scheduler) {
        AccountDeletionService service = new AccountDeletionService(store, quota, billing, entitlements, limits, suggestions,
                hasher, props.deletion(), clock);
        scheduler.scheduleWithFixedDelay(service::retryPending, Instant.now().plus(props.deletion().retryInterval()),
                props.deletion().retryInterval());
        return service;
    }
}
