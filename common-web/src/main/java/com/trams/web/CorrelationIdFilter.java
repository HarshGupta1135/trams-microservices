package com.trams.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adopts (or mints) the correlation id for the current request and publishes it to SLF4J's MDC,
 * so every log line and every event emitted while handling the.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String LEGACY_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = resolve(request);

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled; a leaked MDC entry would mislabel a later request.
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(HttpServletRequest request) {
        String candidate = firstValid(request.getHeader(HEADER), request.getHeader(LEGACY_HEADER));
        return candidate != null ? candidate : UUID.randomUUID().toString();
    }

    private static String firstValid(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                String trimmed = candidate.strip();
                if (SAFE_ID.matcher(trimmed).matches()) {
                    return trimmed;
                }
            }
        }
        return null;
    }
}
