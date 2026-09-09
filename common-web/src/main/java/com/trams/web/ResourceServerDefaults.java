package com.trams.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * The resource-server hardening every backing service applies identically.
 *
 * <p>Extracted so the two services cannot drift: a security setting that is correct in one
 * service and quietly missing in the other is worse than no shared code, because the gap
 * is invisible in review. Each service still declares its own route authorisation, which
 * is the part that genuinely differs.
 */
public final class ResourceServerDefaults {

    private ResourceServerDefaults() {}

    /**
     * Applies stateless bearer-token security, security headers and RFC 9457 error
     * rendering. The caller adds its own {@code authorizeHttpRequests} rules and calls
     * {@code build()}.
     */
    public static void apply(HttpSecurity http) throws Exception {
        http
                // No cookies and no session means there is no ambient credential for a
                // cross-site request to ride on, so there is nothing for CSRF to protect.
                // A bearer token has to be attached deliberately by the client.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CORS is handled solely at the gateway - the only origin a browser ever
                // talks to. Configuring it here as well would create a second place to
                // get it wrong.
                .cors(cors -> cors.disable())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter())))
                .exceptionHandling(
                        handling ->
                                handling
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writeProblem(
                                                                response,
                                                                HttpStatus.UNAUTHORIZED,
                                                                "A valid bearer access token is required.",
                                                                "UNAUTHORIZED"))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        writeProblem(
                                                                response,
                                                                HttpStatus.FORBIDDEN,
                                                                "You do not have permission to perform this action.",
                                                                "FORBIDDEN")))
                .headers(
                        headers ->
                                headers
                                        .contentTypeOptions(Customizer.withDefaults())
                                        .frameOptions(frame -> frame.deny())
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                                        // These are JSON APIs: no response should ever
                                        // execute script, load a plugin, or be framed.
                                        .contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'none'; frame-ancestors 'none'")));
    }

    /**
     * Maps the token's {@code roles} claim onto Spring Security authorities.
     *
     * <p>The default converter reads OAuth scopes. This API models authority as roles, so
     * the claim name and the {@code ROLE_} prefix are set explicitly to match what
     * {@code hasRole('ADMIN')} expects — a mismatch here fails open-looking (every role
     * check denies) and is tedious to diagnose.
     */
    public static JwtAuthenticationConverter roleConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return converter;
    }

    private static void writeProblem(
            jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String detail, String code)
            throws java.io.IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(ProblemDetails.asJson(status, detail, code));
    }

    /** Probe and documentation paths that must stay reachable without a token. */
    public static String[] infrastructurePaths() {
        return new String[] {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
        };
    }
}
