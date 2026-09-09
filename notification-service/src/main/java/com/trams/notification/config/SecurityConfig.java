package com.trams.notification.config;

import com.trams.web.ResourceServerDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security for the Notification Service.
 *
 * <p>Note the absence of any public API route. This service has nothing to offer an
 * unauthenticated caller: it does not authenticate anyone, and its only HTTP surface is a
 * user's own notification history. Work arrives over the message broker, not over HTTP.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ResourceServerDefaults.apply(http);

        return http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(ResourceServerDefaults.infrastructurePaths())
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .build();
    }
}
