package com.idraak.smartbite.v2.entitlement;

import com.idraak.smartbite.v2.config.V2Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class EntitlementConfig {

    @Bean
    public BillingProvider billingProvider(V2Properties props) {
        V2Properties.Entitlement e = props.entitlement();
        if ("fake".equalsIgnoreCase(e.provider())) {
            if (!props.stores().allowInMemory()) {
                throw new IllegalStateException("ENTITLEMENT_PROVIDER=fake is only allowed in tests/local runs.");
            }
            return new FakeBilling();
        }
        if (e.secretApiKey() == null || e.secretApiKey().isBlank() || e.projectId() == null || e.projectId().isBlank()) {
            throw new IllegalStateException("REVENUECAT_SECRET_API_KEY and REVENUECAT_PROJECT_ID are required.");
        }
        return new RevenueCatClient(e.baseUrl(), e.secretApiKey(), e.projectId(), e.lookupKey(), e.connectTimeout(), e.readTimeout());
    }

    @Bean
    public EntitlementService entitlementService(BillingProvider billing, V2Properties props, Clock clock) {
        return new EntitlementService(billing, props.entitlement(), clock);
    }
}
