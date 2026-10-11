package com.idraak.smartbite.v2.auth;

import com.idraak.smartbite.common.ratelimit.RateLimitDecision;
import com.idraak.smartbite.common.ratelimit.RateLimiter;
import com.idraak.smartbite.common.web.ClientIpResolver;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Every /api/v2 request needs {@code Authorization: Bearer <Firebase ID token>} (ADR-0003). A per-IP limit runs
 * first so token floods are cheap to reject. Anonymous accounts get 403 ACCOUNT_REQUIRED.
 */
public class FirebaseAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthFilter.class);

    private final IdTokenVerifier verifier;
    private final RateLimiter preAuthLimiter;
    private final ClientIpResolver ipResolver;
    private final ObjectMapper mapper;

    public FirebaseAuthFilter(IdTokenVerifier verifier, RateLimiter preAuthLimiter, ClientIpResolver ipResolver, ObjectMapper mapper) {
        this.verifier = verifier;
        this.preAuthLimiter = preAuthLimiter;
        this.ipResolver = ipResolver;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v2/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitDecision ip = preAuthLimiter.tryAcquire(ipResolver.resolve(request));
        if (!ip.allowed()) {
            Problems.write(response, mapper, ApiException.rateLimited(ip.retryAfterSeconds()));
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            Problems.write(response, mapper, new ApiException(ErrorCode.UNAUTHENTICATED));
            return;
        }
        AuthenticatedUser user;
        try {
            user = verifier.verify(header.substring(7).trim(), false);
        } catch (TokenVerificationException e) {
            if (e.kind() == TokenVerificationException.Kind.UNAVAILABLE) {
                log.warn("token verification unavailable");
                Problems.write(response, mapper, ApiException.providerUnavailable(10, e));
            } else {
                Problems.write(response, mapper, new ApiException(ErrorCode.UNAUTHENTICATED));
            }
            return;
        }
        if ("anonymous".equals(user.signInProvider())) {
            Problems.write(response, mapper, new ApiException(ErrorCode.ACCOUNT_REQUIRED));
            return;
        }
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, user);
        chain.doFilter(request, response);
    }
}
