package com.trams.user.application;

import com.trams.user.infrastructure.persistence.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Revocations that must survive the caller's rollback. */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final RefreshTokenRepository refreshTokens;

    public TokenRevocationService(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /** Revokes every live token descended from one login. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamilyImmediately(UUID familyId, String reason) {
        int revoked = refreshTokens.revokeFamily(familyId, Instant.now(), reason);

        log.warn("Revoked {} live token(s) in family {} ({})", revoked, familyId, reason);

        return revoked;
    }

    /** Ends every session for a user, committing independently of the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllSessionsImmediately(UUID userId, String reason) {
        int revoked = refreshTokens.revokeAllForUser(userId, Instant.now(), reason);

        if (revoked > 0) {
            log.info("Revoked {} refresh token(s) for user {} ({})", revoked, userId, reason);
        }

        return revoked;
    }
}
