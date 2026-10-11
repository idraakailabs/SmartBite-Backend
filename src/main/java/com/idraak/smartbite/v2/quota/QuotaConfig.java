package com.idraak.smartbite.v2.quota;

import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.settings.AppSettings;
import com.idraak.smartbite.v2.store.DocumentStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class QuotaConfig {

    @Bean
    public QuotaService quotaService(DocumentStore store, AppSettings settings, V2Properties props, Clock clock) {
        return new QuotaService(store, settings, props.quota(), clock);
    }
}
