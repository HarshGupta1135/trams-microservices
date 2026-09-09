package com.trams.user.infrastructure.persistence;

import com.trams.user.domain.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Lookup is by hash: the plaintext token exists only in the client's possession. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    /** Revokes every token in a rotation family. */
    @Modifying
    @Query(
            """
            UPDATE RefreshToken t
               SET t.revokedAt = :when, t.revokedReason = :reason
             WHERE t.familyId = :familyId
               AND t.revokedAt IS NULL
            """)
    int revokeFamily(
            @Param("familyId") UUID familyId,
            @Param("when") Instant when,
            @Param("reason") String reason);

    /** Revokes all of a user's live sessions, e.g. after a password change. */
    @Modifying
    @Query(
            """
            UPDATE RefreshToken t
               SET t.revokedAt = :when, t.revokedReason = :reason
             WHERE t.userId = :userId
               AND t.revokedAt IS NULL
            """)
    int revokeAllForUser(
            @Param("userId") UUID userId,
            @Param("when") Instant when,
            @Param("reason") String reason);

    /** Removes tokens that expired long ago. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
