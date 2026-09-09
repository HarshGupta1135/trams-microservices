package com.trams.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Access-token settings, bound from trams.security.token.*. */
@ConfigurationProperties(prefix = "trams.security.token")
@Validated
public record TokenProperties(
        @NotBlank String publicKeyBase64,
        String privateKeyBase64,
        @NotBlank String issuer,
        @NotBlank String audience,
        @DefaultValue("trams-signing-key-1") String keyId,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue("5s") Duration clockSkew) {}
