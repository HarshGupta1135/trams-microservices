package com.trams.web;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import com.trams.security.RsaKeyLoader;
import com.trams.security.TokenProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** RS256 access-token verification, identical in every service. */
@Configuration(proxyBeanMethods = false)
public class JwtVerificationConfig {

    /** Only tokens explicitly marked as access tokens are accepted. */
    private static final String ACCESS_TOKEN_TYPE = "access";

    @Bean
    @ConditionalOnMissingBean
    public RSAPublicKey rsaPublicKey(TokenProperties properties) {
        return RsaKeyLoader.loadPublicKey(properties.publicKeyBase64());
    }

    /** Builds the decoder. */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(RSAPublicKey publicKey, TokenProperties properties) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(publicKey)
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
