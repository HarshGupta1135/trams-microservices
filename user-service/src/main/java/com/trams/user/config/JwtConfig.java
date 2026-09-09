package com.trams.user.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.trams.security.RsaKeyLoader;
import com.trams.security.TokenProperties;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Token <em>signing</em>, which exists only in this service.
 *
 * <p>Verification (the public key and {@code JwtDecoder}) comes from {@code common-web}
 * and is identical everywhere. The signing key is deliberately not shared: the User
 * Service is the sole issuer, so a compromise of the gateway or the Notification Service
 * yields the ability to read tokens, never to mint them.
 *
 * <p>If {@code trams.security.token.private-key-base64} is absent, startup fails here
 * rather than at the first login attempt.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    @Bean
    public RSAPrivateKey rsaPrivateKey(TokenProperties properties) {
        String privateKeyBase64 = properties.privateKeyBase64();

        if (privateKeyBase64 == null || privateKeyBase64.isBlank()) {
            throw new IllegalStateException(
                    "trams.security.token.private-key-base64 is required: the User Service issues tokens "
                            + "and cannot start without a signing key. Run scripts/generate-secrets.sh.");
        }

        return RsaKeyLoader.loadPrivateKey(privateKeyBase64);
    }

    /**
     * Signs access tokens.
     *
     * <p>The key is published as a JWK carrying a {@code kid}, which is what makes
     * rotation possible: a new key can be introduced while verifiers still accept tokens
     * signed by the previous one.
     */
    @Bean
    public JwtEncoder jwtEncoder(
            RSAPublicKey publicKey, RSAPrivateKey privateKey, TokenProperties properties) {

        RSAKey jwk =
                new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(properties.keyId()).build();

        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }
}
