package com.trams.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Edge configuration, bound from {@code trams.gateway.*}.
 *
 * @param internalApiKey the secret attached to every proxied request, proving to the
 *     backing services that it arrived through the gateway. A minimum length is enforced
 *     at startup: silently accepting a short, guessable key would give a false sense of
 *     protection.
 * @param corsAllowedOrigins explicit browser-origin allow-list. Never widen this to
 *     {@code *} on an API that accepts credentials - it would let any site on the internet
 *     read authenticated responses on a user's behalf.
 */
@ConfigurationProperties(prefix = "trams.gateway")
@Validated
public record GatewayEdgeProperties(
        @NotBlank @Size(min = 32) String internalApiKey,
        @NotEmpty List<String> corsAllowedOrigins) {}
