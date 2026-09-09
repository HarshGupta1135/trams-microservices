package com.trams.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/** Rate-limit keys. */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfig {

    private static final String ANONYMOUS_KEY_PREFIX = "ip:";
    private static final String USER_KEY_PREFIX = "user:";

    /**
     * Keys on the authenticated subject, falling back to source address when the request
     * carries no token.
     */
    @Bean
    // Marked primary because Spring Cloud Gateway's RequestRateLimiter factory injects a single
    // default KeyResolver.
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

    /** The client's address. */
    private static String clientAddressKey(org.springframework.web.server.ServerWebExchange exchange) {
        var remoteAddress = exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            // Unknown source: share one bucket rather than granting an exemption.
            return ANONYMOUS_KEY_PREFIX + "unknown";
        }

        return ANONYMOUS_KEY_PREFIX + remoteAddress.getAddress().getHostAddress();
    }
}
