package com.idraak.smartbite.v2.history;

import com.idraak.smartbite.v2.config.V2Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Configuration
public class HistoryConfig {

    /** Without a database the service exists but answers 503 FEATURE_UNAVAILABLE; no cleanup is scheduled. */
    @Bean
    public HistoryService historyService(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper mapper, V2Properties props, Clock clock,
                                         TaskScheduler scheduler) {
        JdbcTemplate template = jdbc.getIfAvailable();
        HistoryService service = new HistoryService(template == null ? null : new HistoryRepository(template, mapper),
                props.history(), clock);
        if (template != null) {
            // First run shortly after startup, then every cleanup-interval (24 h by default).
            scheduler.scheduleWithFixedDelay(service::cleanup, Instant.now().plus(Duration.ofMinutes(5)),
                    props.history().cleanupInterval());
        }
        return service;
    }
}
