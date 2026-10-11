package com.idraak.smartbite.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller's IP behind a known number of trusted proxies. Render's proxy appends the client address to
 * {@code X-Forwarded-For}, so the entry {@code trustedProxies} positions from the right is the one the platform saw;
 * entries further left are client-controlled and ignored. Assumption to confirm on staging: one proxy hop.
 */
public class ClientIpResolver {

    private final int trustedProxies;

    public ClientIpResolver(int trustedProxies) {
        this.trustedProxies = Math.max(0, trustedProxies);
    }

    public String resolve(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (trustedProxies == 0 || xff == null || xff.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] parts = xff.split(",");
        int index = parts.length - trustedProxies;
        if (index < 0) index = 0;
        String candidate = parts[index].trim();
        return candidate.isEmpty() ? request.getRemoteAddr() : candidate;
    }
}
