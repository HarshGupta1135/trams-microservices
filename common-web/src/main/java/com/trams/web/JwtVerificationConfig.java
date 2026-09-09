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

/**
 * RS256 access-token verification, identical in every service.
 *
 * <p>Services hold only the public key. Verifying a caller therefore never confers the
 * ability to impersonate one - the reason for choosing asymmetric signing over a shared
 * HMAC secret, where every verifier would also be a potential forger.
 */
@Configuration(proxyBeanMethods = false)
public class JwtVerificationConfig {

    /** Only tokens explicitly marked as access tokens are accepted. */
    private static final String ACCESS_TOKEN_TYPE = "access";

    @Bean
    @ConditionalOnMissingBean
    public RSAPublicKey rsaPublicKey(TokenProperties properties) {
        return RsaKeyLoader.loadPublicKey(properties.publicKeyBase64());
    }

    /**
     * Builds the decoder.
     *
     * <p>The algorithm is pinned to RS256 rather than taken from the token. Honouring the
     * {@code alg} header is the classic JWT vulnerability: a decoder that trusts it can be
     * induced to verify an attacker-supplied HMAC signature using the public key as the
     * shared secret, or to accept {@code alg: none} outright.
     *
     * <p>Issuer, audience, expiry and token type are all validated. Skipping the audience
     * check is the subtler mistake - it would let a token issued for a different service
     * that happens to trust the same key be replayed here.
     */
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
