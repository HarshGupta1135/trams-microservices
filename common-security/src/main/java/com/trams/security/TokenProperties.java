package com.trams.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Access-token settings, bound from {@code trams.security.token.*}.
 *
 * <p>Shared by every service so that the issuer, audience and key material can never
 * drift between the component that mints tokens and the components that verify them - a
 * mismatch that would present as intermittent 401s.
 *
 * @param publicKeyBase64 base64-encoded PEM; supplied to every service
 * @param privateKeyBase64 base64-encoded PKCS#8 PEM. Supplied ONLY to the User Service,
 *     which is why it is optional here: a service that cannot sign should not be able to,
 *     and its absence is the enforcement.
 * @param issuer the {@code iss} claim, verified on every request
 * @param audience the {@code aud} claim, so a token minted for another API that trusts the
 *     same key cannot be replayed here
 * @param keyId the {@code kid} header, which allows key rotation without downtime
 * @param clockSkew tolerance for clock drift between services when checking expiry
 */
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
