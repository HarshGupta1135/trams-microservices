package com.trams.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects any request that did not arrive through the API Gateway. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "trams.security.internal.enabled", matchIfMissing = true)
public class InternalKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Key";

    private static final Logger log = LoggerFactory.getLogger(InternalKeyFilter.class);

    private static final String[] EXEMPT_PREFIXES = {
        "/actuator/health", "/actuator/info", "/actuator/prometheus",
        "/v3/api-docs", "/swagger-ui", "/swagger-ui.html"
    };

    private final byte[] expectedKeyDigest;

    public InternalKeyFilter(InternalAuthProperties properties) {
        this.expectedKeyDigest = digest(properties.apiKey());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }

        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String presented = request.getHeader(HEADER);

        if (presented == null || !matches(presented)) {
            log.warn(
                    "Rejected a request to {} that did not originate from the API Gateway (source: {})",
                    request.getRequestURI(),
                    request.getRemoteAddr());

            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter()
                    .write(
                            ProblemDetails.asJson(
                                    HttpStatus.UNAUTHORIZED,
                                    "This service is only reachable through the API Gateway.",
                                    "GATEWAY_ONLY"));
            return;
        }

        chain.doFilter(request, response);
    }

    /** Constant-time comparison. */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(expectedKeyDigest, digest(presented));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
