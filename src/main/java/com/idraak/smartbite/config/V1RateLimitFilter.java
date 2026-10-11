package com.idraak.smartbite.config;

import com.idraak.smartbite.common.ratelimit.RateLimitDecision;
import com.idraak.smartbite.common.ratelimit.RateLimiter;
import com.idraak.smartbite.common.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Per-IP limit for the unauthenticated v1 endpoint. Only abusive callers see a 429; its body follows v1's
 * existing {"error", "message"} error style.
 */
public class V1RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(V1RateLimitFilter.class);
    private static final String BODY = "{\"error\":\"Too many requests\",\"message\":\"Rate limit exceeded. Retry later.\"}";

    private final RateLimiter limiter;
    private final ClientIpResolver ipResolver;

    public V1RateLimitFilter(RateLimiter limiter, ClientIpResolver ipResolver) {
        this.limiter = limiter;
        this.ipResolver = ipResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitDecision decision = limiter.tryAcquire(ipResolver.resolve(request));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("v1 per-IP rate limit hit; retry_after_s={}", decision.retryAfterSeconds());
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}
