package com.trams.user.application;

import com.trams.security.TokenProperties;
import com.trams.user.domain.InvalidRefreshTokenException;
import com.trams.user.domain.RefreshToken;
import com.trams.user.infrastructure.persistence.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues, rotates and revokes refresh tokens. */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 32 bytes = 256 bits of entropy, well beyond guessing range. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final TokenProperties properties;
    private final TokenRevocationService revocations;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            TokenProperties properties,
            TokenRevocationService revocations) {
        this.repository = repository;
        this.properties = properties;
        this.revocations = revocations;
    }

    /** A freshly minted token. */
    public record IssuedRefreshToken(String token, Instant expiresAt) {}

    /** Client attributes recorded for audit purposes. */
    public record ClientContext(String ipAddress, String userAgent) {}

    /** Starts a new token family, i.e. a fresh login. */
    @Transactional
    public IssuedRefreshToken issue(UUID userId, ClientContext client) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.refreshTokenTtl());
        String plaintext = generateToken();

        RefreshToken token =
                RefreshToken.issueNewFamily(
                        userId, hash(plaintext), now, expiresAt, client.ipAddress(), client.userAgent());

        repository.save(token);
        log.debug("Issued a new refresh token family {} for user {}", token.getFamilyId(), userId);

        return new IssuedRefreshToken(plaintext, expiresAt);
    }

    /** The result of a successful rotation. */
    public record RotationResult(UUID userId, IssuedRefreshToken replacement) {}

    /** Consumes the presented token and issues its successor. */
    @Transactional
    public RotationResult rotate(String presentedToken, ClientContext client) {
        Instant now = Instant.now();

        RefreshToken existing =
                repository
                        .findByTokenHash(hash(presentedToken))
                        .orElseThrow(() -> new InvalidRefreshTokenException("unknown token"));

        if (existing.isRevoked()) {
            // Committed in its own transaction: this method throws immediately afterwards, and
            // a revocation performed in *this* transaction would be rolled back by that.
            int revoked = revocations.revokeFamilyImmediately(existing.getFamilyId(), "reuse-detected");

            log.warn(
                    "Refresh token reuse detected for user {} (family {}); revoked {} live token(s)",
                    existing.getUserId(),
                    existing.getFamilyId(),
                    revoked);

            throw new InvalidRefreshTokenException("token has already been used");
        }

        if (existing.isExpired(now)) {
            throw new InvalidRefreshTokenException("token has expired");
        }

        String replacementPlaintext = generateToken();
        Instant expiresAt = now.plus(properties.refreshTokenTtl());

        RefreshToken replacement =
                existing.rotate(
                        hash(replacementPlaintext),
                        now,
                        expiresAt,
                        client.ipAddress(),
                        client.userAgent());

        // `existing` is a managed entity, so its revocation is flushed with this save.
        repository.save(existing);
        repository.save(replacement);

        return new RotationResult(
                existing.getUserId(), new IssuedRefreshToken(replacementPlaintext, expiresAt));
    }

    /** Revokes a single token, i.e. logout. */
    @Transactional
    public void revoke(String presentedToken) {
        repository
                .findByTokenHash(hash(presentedToken))
                .ifPresent(token -> token.revoke(Instant.now(), "logout"));
    }

    /** Ends every session for a user, e.g. after a password change. */
    @Transactional
    public int revokeAllSessions(UUID userId, String reason) {
        int revoked = repository.revokeAllForUser(userId, Instant.now(), reason);

        if (revoked > 0) {
            log.info("Revoked {} refresh token(s) for user {} ({})", revoked, userId, reason);
        }

        return revoked;
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);

        // URL-safe and unpadded, so the value survives headers, JSON and query strings.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform; unreachable in practice.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
