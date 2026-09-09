package com.trams.gateway.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Mono;

/**
 * Edge authentication and authorisation.
 *
 * <p>Rejecting an unauthenticated request here means it never touches a backing service,
 * never opens a database connection and never consumes a thread downstream — the main
 * reason to authenticate at the edge at all. The services nonetheless verify the token
 * again themselves: this check is an optimisation and a first line of defence, not the
 * authoritative one.
 *
 * <p>Route rules are ordered most-specific first. Anything not explicitly listed requires
 * authentication, so a new route added later fails closed.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Reachable with no token: the login surface, probes, docs and the fallback route. */
    private static final String[] PUBLIC_PATHS = {
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/fallback/**",
        "/docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/v3/api-docs/**",
        "/api-docs/**",
        "/webjars/**"
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, GatewayEdgeProperties properties) {

        return http
                // Stateless bearer-token API: no cookie or session for a cross-site
                // request to ride on, so there is nothing for CSRF to protect.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .authorizeExchange(
                        exchange ->
                                exchange
                                        .pathMatchers(PUBLIC_PATHS)
                                        .permitAll()
                                        // CORS preflight carries no credentials and must
                                        // not be challenged, or every browser call fails.
                                        .pathMatchers(HttpMethod.OPTIONS)
                                        .permitAll()
                                        // A user's own profile and notifications.
                                        .pathMatchers("/api/v1/users/me/**", "/api/v1/users/me")
                                        .authenticated()
                                        .pathMatchers("/api/v1/notifications/**")
                                        .authenticated()
                                        // Everything else under /users is administrative:
                                        // listing users, or acting on someone by id. The
                                        // services re-check this with @PreAuthorize.
                                        .pathMatchers("/api/v1/users/**")
                                        .hasRole("ADMIN")
                                        .anyExchange()
                                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.jwt(
                                        jwt ->
                                                jwt.jwtAuthenticationConverter(
                                                        new ReactiveJwtAuthenticationConverterAdapter(
                                                                roleConverter()))))
                .exceptionHandling(
                        handling ->
                                handling
                                        .authenticationEntryPoint(
                                                (exchange, denied) ->
                                                        writeProblem(
                                                                exchange,
                                                                HttpStatus.UNAUTHORIZED,
                                                                "A valid bearer access token is required.",
                                                                "UNAUTHORIZED"))
                                        .accessDeniedHandler(
                                                (exchange, denied) ->
                                                        writeProblem(
                                                                exchange,
                                                                HttpStatus.FORBIDDEN,
                                                                "You do not have permission to perform this action.",
                                                                "FORBIDDEN")))
                .headers(
                        headers ->
                                headers
                                        .contentTypeOptions(Customizer.withDefaults())
                                        .frameOptions(frame -> frame.mode(
                                                org.springframework.security.web.server.header
                                                        .XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                org.springframework.security.web.server.header
                                                                        .ReferrerPolicyServerHttpHeadersWriter
                                                                        .ReferrerPolicy.NO_REFERRER))
                                        .hsts(hsts -> hsts.includeSubdomains(true)))
                .build();
    }

    /**
     * Maps the token's {@code roles} claim onto Spring Security authorities, identically
     * to the backing services — a mismatch would make edge and service authorisation
     * disagree.
     */
    private static JwtAuthenticationConverter roleConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return converter;
    }

    /**
     * CORS for browser clients.
     *
     * <p>Origins come from an explicit allow-list and credentials are permitted, which is
     * the combination that makes a wildcard origin unacceptable: with
     * {@code allowCredentials(true)}, {@code *} would let any website read authenticated
     * responses on a logged-in user's behalf. The browser itself refuses that pairing.
     */
    private static CorsConfigurationSource corsConfigurationSource(GatewayEdgeProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(properties.corsAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Correlation-Id", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }

    /** Writes an RFC 9457 body so edge failures match the services' error format. */
    private static Mono<Void> writeProblem(
            org.springframework.web.server.ServerWebExchange exchange,
            HttpStatus status,
            String detail,
            String code) {

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String body =
                "{\"type\":\"https://docs.trams.local/errors/%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"}"
                        .formatted(
                                code.toLowerCase().replace('_', '-'),
                                status.getReasonPhrase(),
                                status.value(),
                                detail,
                                code);

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

}
