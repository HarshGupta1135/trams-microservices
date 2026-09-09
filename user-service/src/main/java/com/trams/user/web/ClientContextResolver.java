package com.trams.user.web;

import com.trams.user.application.RefreshTokenService.ClientContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts the caller's network context for audit purposes.
 *
 * <p>{@code X-Forwarded-For} is honoured because this service is only reachable through
 * the API Gateway, which sets it. That trust is not unconditional: the header is only
 * meaningful because direct access to this service is blocked at the network level and by
 * the internal-key filter. A service exposed straight to the internet must never trust it,
 * since a client can set it freely and thereby forge its own source address — which would
 * poison audit records and defeat any IP-based control.
 *
 * <p>Only the first entry is taken: proxies append, so the left-most value is the original
 * client as recorded by the first trusted hop.
 */
final class ClientContextResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String USER_AGENT = "User-Agent";
    private static final int MAX_IP_LENGTH = 64;
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private ClientContextResolver() {}

    static ClientContext resolve(HttpServletRequest request) {
        return new ClientContext(clientIp(request), truncate(request.getHeader(USER_AGENT), MAX_USER_AGENT_LENGTH));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);

        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",", 2)[0].strip();
            if (!first.isEmpty()) {
                return truncate(first, MAX_IP_LENGTH);
            }
        }

        return truncate(request.getRemoteAddr(), MAX_IP_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }
}
