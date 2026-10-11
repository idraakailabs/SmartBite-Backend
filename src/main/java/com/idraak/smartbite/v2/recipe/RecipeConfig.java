package com.idraak.smartbite.v2.recipe;

import com.idraak.smartbite.v2.ai.ProviderChain;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.entitlement.EntitlementService;
import com.idraak.smartbite.v2.settings.AppSettings;
import com.idraak.smartbite.v2.store.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Configuration
public class RecipeConfig {

    private static final Logger log = LoggerFactory.getLogger(RecipeConfig.class);

    @Bean
    public RecipeService recipeService(@Qualifier("recipeProviderChain") ProviderChain chain, DocumentStore store,
                                       EntitlementService entitlements, AppSettings settings, V2Properties props,
                                       ObjectMapper mapper, Clock clock, TaskScheduler scheduler) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int deleted = store.deleteExpired(RecipeService.COLLECTION, "expire_at", clock.instant(), 400);
                log.info("recipe cache cleanup deleted={}", deleted);
            } catch (RuntimeException e) {
                log.warn("recipe cache cleanup failed ({})", e.getClass().getSimpleName());
            }
        }, Instant.now().plus(Duration.ofMinutes(2)), Duration.ofHours(12));
        return new RecipeService(chain, store, entitlements, settings, props, mapper, clock);
    }
}
