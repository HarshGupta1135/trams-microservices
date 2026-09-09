package com.trams.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Edge configuration, bound from trams.gateway.*. */
@ConfigurationProperties(prefix = "trams.gateway")
@Validated
public record GatewayEdgeProperties(
        @NotBlank @Size(min = 32) String internalApiKey,
        @NotEmpty List<String> corsAllowedOrigins) {}
