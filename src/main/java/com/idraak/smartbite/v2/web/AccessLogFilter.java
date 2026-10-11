package com.idraak.smartbite.v2.web;

import com.idraak.smartbite.v2.auth.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * One structured line per v2 request: method, path, status, latency, hashed uid, app version. Never the body,
 * headers with tokens, coordinates or dish lists.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("smartbite.access");
    private static final Pattern APP_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+([-+][A-Za-z0-9.]+)*$");

    private final UidHasher hasher;

    public AccessLogFilter(UidHasher hasher) {
        this.hasher = hasher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v2/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object user = request.getAttribute(AuthenticatedUser.ATTRIBUTE);
            String uidHash = user instanceof AuthenticatedUser u ? hasher.hash(u.uid()) : "-";
            String appVersion = request.getHeader("X-App-Version");
            if (appVersion == null || appVersion.length() > 32 || !APP_VERSION.matcher(appVersion).matches()) appVersion = "-";
            log.info("v2 request method={} path={} status={} latency_ms={} uid_hash={} app_version={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - start) / 1_000_000, uidHash, appVersion);
        }
    }
}
