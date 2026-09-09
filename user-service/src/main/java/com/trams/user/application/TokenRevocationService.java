package com.trams.user.application;

import com.trams.user.infrastructure.persistence.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revocations that must survive the caller's rollback.
 *
 * <p><strong>Why this class exists.</strong> Both revocations below happen on paths that
 * then throw: detecting a replayed refresh token, and refreshing for an account that has
 * since been disabled. If the revocation ran in the caller's transaction, the exception
 * would roll it back — the system would report the attack and then quietly undo its own
 * defence, leaving every stolen token in the family still valid. That failure is invisible
 * in a unit test that only asserts the exception.
 *
 * <p>{@link Propagation#REQUIRES_NEW} suspends the caller's transaction and commits this
 * one independently, so the revocation is durable before the exception propagates.
 *
 * <p>It is a separate bean because Spring's transaction proxying works on external calls:
 * a {@code REQUIRES_NEW} method invoked from a sibling method of the same class would
 * bypass the proxy and silently join the existing transaction.
 *
 * <p>Note the contrast with a password change, where revoking every session is
 * deliberately part of the <em>same</em> transaction as the new password: there, the two
 * must either both commit or both fail.
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final RefreshTokenRepository refreshTokens;

    public TokenRevocationService(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /**
     * Revokes every live token descended from one login.
     *
     * <p>Called when an already-consumed token is presented again. Because we cannot tell
     * the attacker's copy from the legitimate one, the only safe response is to invalidate
     * the whole family and force re-authentication.
     *
     * @return how many live tokens were revoked
     */
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
