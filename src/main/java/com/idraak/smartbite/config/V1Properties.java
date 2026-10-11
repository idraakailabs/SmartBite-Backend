package com.idraak.smartbite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** v1 hardening settings (D-021 item 4). None of them change the v1 request or response contract. */
@ConfigurationProperties(prefix = "smartbite.v1")
public record V1Properties(
        List<String> corsAllowedOrigins,
        int rateLimitPerMinute,
        int rateLimitPerHour,
        int trustedProxies,
        Duration aiConnectTimeout,
        Duration aiReadTimeout
) {
    public V1Properties {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isEmpty()) corsAllowedOrigins = List.of("*");
        if (rateLimitPerMinute <= 0) rateLimitPerMinute = 60;
        if (rateLimitPerHour <= 0) rateLimitPerHour = 600;
        if (trustedProxies < 0) trustedProxies = 1;
        if (aiConnectTimeout == null) aiConnectTimeout = Duration.ofSeconds(3);
        if (aiReadTimeout == null) aiReadTimeout = Duration.ofSeconds(30);
    }
}
