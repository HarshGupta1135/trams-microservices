package com.trams.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The shared secret proving a request arrived through the API Gateway.
 *
 * <p>A minimum length is enforced at startup: a short key would be brute-forceable, and
 * silently accepting one would give a false sense of protection.
 */
@ConfigurationProperties(prefix = "trams.security.internal")
@Validated
public record InternalAuthProperties(@NotBlank @Size(min = 32) String apiKey) {}
