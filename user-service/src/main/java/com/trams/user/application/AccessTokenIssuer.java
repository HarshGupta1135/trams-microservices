package com.trams.user.application;

import com.trams.security.TokenProperties;
import com.trams.user.domain.Role;
import com.trams.user.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Mints short-lived, stateless RS256 access tokens. */
@Service
public class AccessTokenIssuer {

    /**
     * Marks the token's purpose, so a token minted for another use cannot be replayed as an
     * access token.
     */
    private static final String TOKEN_TYPE = "access";

    private final JwtEncoder jwtEncoder;
    private final TokenProperties properties;

    public AccessTokenIssuer(JwtEncoder jwtEncoder, TokenProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public record IssuedAccessToken(String token, Instant expiresAt, long expiresInSeconds) {}

    public IssuedAccessToken issue(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .audience(List.of(properties.audience()))
                        .subject(user.getId().toString())
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        // A unique token id, so a specific token can be traced in logs
                        // and denylisted if that ever becomes necessary.
                        .id(UUID.randomUUID().toString())
                        .claim("email", user.getEmail())
                        .claim("roles", roles)
                        .claim("typ", TOKEN_TYPE)
                        .build();

        // The key id lets a verifier pick the right public key during a rotation window.
        JwsHeader header =
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.keyId()).build();

        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));

        return new IssuedAccessToken(
                jwt.getTokenValue(), expiresAt, properties.accessTokenTtl().toSeconds());
    }
}
