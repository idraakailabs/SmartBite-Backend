package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.ai.ProviderChain;
import com.idraak.smartbite.v2.area.AreaGrounding;
import com.idraak.smartbite.v2.area.NoPlacesAreaGrounding;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.entitlement.EntitlementService;
import com.idraak.smartbite.v2.quota.QuotaService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration
public class SuggestionConfig {

    @Bean
    @ConditionalOnExpression("'${smartbite.v2.places.provider:disabled}' == 'disabled'")
    public AreaGrounding noPlacesAreaGrounding() {
        return new NoPlacesAreaGrounding();
    }

    @Bean
    public SuggestionService suggestionService(@Qualifier("suggestionProviderChain") ProviderChain chain, ObjectMapper mapper,
                                               AreaGrounding grounding, QuotaService quota, EntitlementService entitlements,
                                               V2Properties props, Clock clock) {
        return new SuggestionService(new SuggestionGenerator(chain, mapper), grounding, quota, entitlements, props, clock);
    }
}
