package com.trams.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * Rate-limit keys.
 *
 * <p>Two resolvers, because the two kinds of traffic need different keys:
 *
 * <ul>
 *   <li>{@code userKeyResolver} keys on the authenticated user id, so one noisy client
 *       cannot exhaust the budget of everyone sharing an office NAT or mobile carrier
 *       gateway.
 *   <li>{@code ipKeyResolver} keys on source address, and is used on the authentication
 *       routes - by definition there is no user id yet, and those are exactly the
 *       endpoints an attacker hits for credential stuffing.
 * </ul>
 *
 * <p>The limits themselves are backed by Redis rather than in-memory counters, so they
 * remain correct when the gateway runs more than one replica. An in-memory limiter across
 * N replicas silently permits N times the intended rate.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfig {

    private static final String ANONYMOUS_KEY_PREFIX = "ip:";
    private static final String USER_KEY_PREFIX = "user:";

    /**
     * Keys on the authenticated subject, falling back to source address when the request
     * carries no token.
     */
    @Bean
    // Marked primary because Spring Cloud Gateway's RequestRateLimiter factory injects a
    // single default KeyResolver. Two candidates without a primary is a startup failure.
    // The IP resolver is selected explicitly per route via "#{@ipKeyResolver}".
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange ->
                ReactiveSecurityContextHolder.getContext()
                        .map(context -> context.getAuthentication())
                        .filter(authentication -> authentication != null && authentication.isAuthenticated())
                        .map(authentication -> authentication.getPrincipal())
                        .filter(Jwt.class::isInstance)
                        .map(principal -> USER_KEY_PREFIX + ((Jwt) principal).getSubject())
                        .switchIfEmpty(Mono.defer(() -> Mono.just(clientAddressKey(exchange))));
    }

    /** Keys purely on source address; used for the unauthenticated auth routes. */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(clientAddressKey(exchange));
    }

    /**
     * The client's address.
     *
     * <p>Read from the remote address rather than {@code X-Forwarded-For}, because the
     * gateway is the outermost hop: any forwarding header on an inbound request was set by
     * the client and is therefore forgeable. Trusting it here would let an attacker rotate
     * a header value to bypass the limit entirely.
     */
    private static String clientAddressKey(org.springframework.web.server.ServerWebExchange exchange) {
        var remoteAddress = exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            // Unknown source: share one bucket rather than granting an exemption.
            return ANONYMOUS_KEY_PREFIX + "unknown";
        }

        return ANONYMOUS_KEY_PREFIX + remoteAddress.getAddress().getHostAddress();
    }
}
