package com.trams.gateway.config;

import com.trams.security.RsaKeyLoader;
import com.trams.security.TokenProperties;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Reactive access-token verification.
 *
 * <p>The gateway holds only the public key. It can therefore reject an invalid token at
 * the edge - before any request reaches a backing service - without being able to mint one
 * itself. A compromise here exposes traffic, never the ability to forge an identity.
 *
 * <p>This is the reactive counterpart of {@code common-web}'s servlet configuration; the
 * validation rules are identical by design, since a token accepted at the edge must also
 * be accepted downstream.
 */
@Configuration(proxyBeanMethods = false)
// TokenProperties lives in common-security (com.trams.security), outside this
// application's @ConfigurationPropertiesScan base package. The backing services pick
// it up through common-web's auto-configuration; the gateway cannot use that module
// (it is servlet-based), so the binding is registered explicitly here.
@EnableConfigurationProperties(TokenProperties.class)
public class GatewayJwtConfig {

    private static final String ACCESS_TOKEN_TYPE = "access";

    @Bean
    public RSAPublicKey rsaPublicKey(TokenProperties properties) {
        return RsaKeyLoader.loadPublicKey(properties.publicKeyBase64());
    }

    /**
     * The algorithm is pinned to RS256 rather than read from the token header. Trusting
     * the header is the classic JWT flaw: a decoder that honours it can be induced to
     * verify an attacker-supplied HMAC signature using the public key as the shared
     * secret, or to accept {@code alg: none} outright.
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(RSAPublicKey publicKey, TokenProperties properties) {
        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder.withPublicKey(publicKey)
                        .signatureAlgorithm(SignatureAlgorithm.RS256)
                        .build();

        OAuth2TokenValidator<Jwt> audience =
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD,
                        claim -> claim != null && claim.contains(properties.audience()));

        OAuth2TokenValidator<Jwt> tokenType =
                new JwtClaimValidator<String>("typ", ACCESS_TOKEN_TYPE::equals);

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(properties.issuer()), audience, tokenType));

        return decoder;
    }
}
