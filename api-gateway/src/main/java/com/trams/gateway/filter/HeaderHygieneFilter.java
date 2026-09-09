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

/**
 * Normalises request headers at the very edge, before anything else sees the request.
 *
 * <p>This filter is the trust boundary of the whole system, and it does three things that
 * every downstream security decision depends on:
 *
 * <ol>
 *   <li><strong>Strips spoofable headers.</strong> Anything a backing service treats as
 *       privileged — the internal service key, identity assertions, forwarding headers —
 *       is removed from the inbound request before the gateway sets its own value. Without
 *       this, a client could send {@code X-Internal-Key} itself and, if a service were
 *       ever reachable directly, be admitted; or forge {@code X-Forwarded-For} to poison
 *       audit records and evade IP-based rate limiting.
 *   <li><strong>Attaches the internal key</strong>, which is how a backing service knows
 *       the request arrived through the gateway.
 *   <li><strong>Establishes the correlation id</strong>, generating one when the client
 *       supplied none, so a single identifier follows the request through both services
 *       and into the events they publish.
 * </ol>
 *
 * <p>Note that headers are removed and then <em>set</em>, never merely added. HTTP headers
 * are multi-valued, so adding a value while a client-supplied one is still present would
 * leave the downstream service to choose between them.
 *
 * <p><strong>Why a {@link WebFilter} rather than a Gateway {@code GlobalFilter}.</strong>
 * A {@code GlobalFilter} only runs for requests that match a route. Responses the gateway
 * produces itself — a 401 from the security chain, an actuator probe, a circuit-breaker
 * fallback — would then carry no correlation id, so the one class of failure a caller most
 * needs to report would be the one with nothing to quote. A {@code WebFilter} sits ahead
 * of both the security chain and the routing filters, so every response gets one.
 */
@Component
public class HeaderHygieneFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(HeaderHygieneFilter.class);

    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String INTERNAL_KEY = "X-Internal-Key";

    private static final String REQUEST_ID = "X-Request-Id";

    /**
     * Headers a client must never be able to set. {@code X-Forwarded-*} is included
     * because the gateway's own forwarding filter appends the real client address;
     * leaving a client-supplied value in place would let the caller prepend a fabricated
     * one.
     */
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
     * Conservative allow-list for a value that will be written into logs and reflected in
     * a response header. Accepting arbitrary input would permit header injection and log
     * forging — an attacker embedding newlines to fabricate log entries.
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
     * Runs before the security chain and the routing filters, so the sanitised request is
     * what every later component sees.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
