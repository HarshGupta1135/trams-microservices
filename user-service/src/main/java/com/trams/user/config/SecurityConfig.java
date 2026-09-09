package com.trams.user.config;

import com.trams.web.ResourceServerDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security for the User Service: a stateless resource server.
 *
 * <p>Transport hardening, token verification and error rendering come from
 * {@link ResourceServerDefaults}, so only this service's route rules are declared here.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
// Enables @PreAuthorize on controller methods, so an authorisation rule sits next to the
// endpoint it protects rather than only in a central path-matching table.
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The only routes reachable without a token.
     *
     * <p>Enumerated individually rather than as {@code /api/v1/auth/**}: a wildcard would
     * silently expose any future endpoint added under that prefix, whereas this list
     * fails closed until someone deliberately adds to it.
     */
    private static final String[] PUBLIC_AUTH_PATHS = {
        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ResourceServerDefaults.apply(http);

        return http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(ResourceServerDefaults.infrastructurePaths())
                                        .permitAll()
                                        .requestMatchers(PUBLIC_AUTH_PATHS)
                                        .permitAll()
                                        // Default-deny: any route added later requires
                                        // authentication unless explicitly listed above.
                                        .anyRequest()
                                        .authenticated())
                .build();
    }
}
