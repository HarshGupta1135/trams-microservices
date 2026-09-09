package com.trams.gateway.filter;

import com.trams.gateway.config.GatewayEdgeProperties;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Normalises request headers at the very edge, before anything else sees the request. */
@Component
public class HeaderHygieneFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(HeaderHygieneFilter.class);

    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String INTERNAL_KEY = "X-Internal-Key";

    private static final String REQUEST_ID = "X-Request-Id";

    /** Headers a client must never be able to set. */
    private static final List<String> CLIENT_FORBIDDEN_HEADERS =
            List.of(
                    INTERNAL_KEY,
                    "X-Auth-User-Id",
                    "X-Auth-User-Email",
                    "X-Auth-Roles",
                    "X-Forwarded-For",
                    "X-Forwarded-Host",
                    "X-Forwarded-Proto",
                    "X-Forwarded-Port",
                    "X-Real-Ip");

    /**
     * Conservative allow-list for a value that will be written into logs and reflected in a
     * response header.
     */
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final GatewayEdgeProperties properties;

    public HeaderHygieneFilter(GatewayEdgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = resolveCorrelationId(exchange.getRequest());

        ServerHttpRequest sanitised =
                exchange.getRequest()
                        .mutate()
                        .headers(
                                headers -> {
                                    CLIENT_FORBIDDEN_HEADERS.forEach(headers::remove);

                                    headers.set(INTERNAL_KEY, properties.internalApiKey());
                                    headers.set(CORRELATION_ID, correlationId);
                                })
                        .build();

        // Echoed back so a client can quote it in a bug report and an operator can find
        // the exact request across both services' logs.
        exchange.getResponse().getHeaders().set(CORRELATION_ID, correlationId);

        return chain.filter(exchange.mutate().request(sanitised).build());
    }

    private static String resolveCorrelationId(ServerHttpRequest request) {
        for (String header : List.of(CORRELATION_ID, REQUEST_ID)) {
            String candidate = request.getHeaders().getFirst(header);

            if (candidate != null) {
                String trimmed = candidate.strip();
                if (SAFE_CORRELATION_ID.matcher(trimmed).matches()) {
                    return trimmed;
                }
                log.debug("Discarded a malformed {} header from a client", header);
            }
        }

        return UUID.randomUUID().toString();
    }

    /**
     * Runs before the security chain and the routing filters, so the sanitised request is what
     * every later component sees.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
