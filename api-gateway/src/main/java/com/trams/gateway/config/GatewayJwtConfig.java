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

/** Reactive access-token verification. */
@Configuration(proxyBeanMethods = false)
// TokenProperties lives in common-security (com.trams.security), outside this application's
// @ConfigurationPropertiesScan base package.
@EnableConfigurationProperties(TokenProperties.class)
public class GatewayJwtConfig {

    private static final String ACCESS_TOKEN_TYPE = "access";

    @Bean
    public RSAPublicKey rsaPublicKey(TokenProperties properties) {
        return RsaKeyLoader.loadPublicKey(properties.publicKeyBase64());
    }

    /** The algorithm is pinned to RS256 rather than read from the token header. */
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
