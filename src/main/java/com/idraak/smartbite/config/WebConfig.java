package com.idraak.smartbite.config;

import com.idraak.smartbite.common.ratelimit.RateLimitSpec;
import com.idraak.smartbite.common.ratelimit.RateLimiter;
import com.idraak.smartbite.common.web.ClientIpResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;

/** v1 wiring: the shared AI clients, the per-IP limit and v1-only CORS. */
@Configuration
@EnableConfigurationProperties(V1Properties.class)
public class WebConfig implements WebMvcConfigurer {

    @Value("${gemini.api-key}")
    private String geminiApiKey;

    @Value("${gemini.base-url}")
    private String geminiBaseUrl;

    @Value("${groq.api-key:}")
    private String groqApiKey;

    @Value("${groq.base-url}")
    private String groqBaseUrl;

    private final V1Properties v1;

    public WebConfig(V1Properties v1) {
        this.v1 = v1;
    }

    @Bean
    public RestClient geminiRestClient() {
        return RestClient.builder()
                .requestFactory(v1RequestFactory())
                .baseUrl(geminiBaseUrl)
                .defaultHeader("Authorization", "Bearer " + geminiApiKey)
                .build();
    }

    @Bean
    public RestClient groqRestClient() {
        return RestClient.builder()
                .requestFactory(v1RequestFactory())
                .baseUrl(groqBaseUrl)
                .defaultHeader("Authorization", "Bearer " + groqApiKey)
                .build();
    }

    /** Explicit timeouts for the v1 AI clients (previously none, so a hung provider held the request). */
    private JdkClientHttpRequestFactory v1RequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(v1.aiConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(v1.aiReadTimeout());
        return factory;
    }

    @Bean
    public FilterRegistrationBean<V1RateLimitFilter> v1RateLimitFilter(Clock clock) {
        RateLimiter limiter = new RateLimiter(List.of(
                RateLimitSpec.perMinute(v1.rateLimitPerMinute()),
                RateLimitSpec.perHour(v1.rateLimitPerHour())), clock);
        FilterRegistrationBean<V1RateLimitFilter> registration =
                new FilterRegistrationBean<>(new V1RateLimitFilter(limiter, new ClientIpResolver(v1.trustedProxies())));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** CORS only for v1 (the browser test page); v2 is mobile-only and Actuator needs none. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
                .allowedOrigins(v1.corsAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
