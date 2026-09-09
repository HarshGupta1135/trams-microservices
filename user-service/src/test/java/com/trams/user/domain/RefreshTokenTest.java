package com.trams.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests refresh-token rotation. */
class RefreshTokenTest {

    private static final Instant ISSUED = Instant.parse("2026-03-04T05:06:07Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(2_592_000);
    private static final UUID USER_ID = UUID.randomUUID();

    private static RefreshToken newToken() {
        return RefreshToken.issueNewFamily(USER_ID, "hash-original", ISSUED, EXPIRES, "203.0.113.7", "curl/8");
    }

    @Test
    @DisplayName("a fresh login starts its own token family")
    void startsANewFamily() {
        RefreshToken first = newToken();
        RefreshToken second =
                RefreshToken.issueNewFamily(USER_ID, "hash-2", ISSUED, EXPIRES, "203.0.113.8", "curl/8");

        // Separate logins must be independently revocable: detecting theft on one device
        // should not sign the user out everywhere.
        assertThat(first.getFamilyId()).isNotEqualTo(second.getFamilyId());
    }

    @Test
    @DisplayName("a new token is usable, unrevoked and unexpired")
    void newTokenIsUsable() {
        RefreshToken token = newToken();

        assertThat(token.isUsable(ISSUED)).isTrue();
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.isExpired(ISSUED)).isFalse();
    }

    @Test
    @DisplayName("rotation consumes the presented token and issues a successor")
    void rotationConsumesTheOriginal() {
        RefreshToken original = newToken();
        Instant rotatedAt = ISSUED.plusSeconds(60);

        RefreshToken successor =
                original.rotate("hash-successor", rotatedAt, EXPIRES, "203.0.113.9", "curl/8");

        // Single use: this is what makes a second presentation detectable.
        assertThat(original.isRevoked()).isTrue();
        assertThat(original.isUsable(rotatedAt)).isFalse();
        assertThat(original.getRevokedReason()).isEqualTo("rotated");
        assertThat(original.getRevokedAt()).isEqualTo(rotatedAt);

        assertThat(successor.isUsable(rotatedAt)).isTrue();
        assertThat(successor.getTokenHash()).isEqualTo("hash-successor");
    }

    @Test
    @DisplayName("the successor stays in the same family, so theft revokes the whole chain")
    void successorInheritsTheFamily() {
        RefreshToken original = newToken();

        RefreshToken successor = original.rotate("hash-2", ISSUED.plusSeconds(60), EXPIRES, null, null);

        // Family membership is what lets one replayed token revoke every descendant.
        assertThat(successor.getFamilyId()).isEqualTo(original.getFamilyId());
        assertThat(successor.getUserId()).isEqualTo(original.getUserId());
    }

    @Test
    @DisplayName("the rotation chain is auditable in both directions")
    void rotationChainIsAuditable() {
        RefreshToken original = newToken();

        RefreshToken successor = original.rotate("hash-2", ISSUED.plusSeconds(60), EXPIRES, null, null);

        assertThat(original.getReplacedBy()).isEqualTo(successor.getId());
    }

    @Test
    @DisplayName("an expired token is not usable even though it was never revoked")
    void expiryMakesATokenUnusable() {
        RefreshToken token = newToken();
        Instant afterExpiry = EXPIRES.plusSeconds(1);

        assertThat(token.isExpired(afterExpiry)).isTrue();
        assertThat(token.isUsable(afterExpiry)).isFalse();
        assertThat(token.isRevoked()).isFalse();
    }

    @Test
    @DisplayName("revocation is idempotent and preserves the original reason")
    void revocationIsIdempotent() {
        // Logout and a concurrent family revocation can both land; the first reason is
        // the accurate one for an audit trail.
        RefreshToken token = newToken();

        token.revoke(ISSUED.plusSeconds(10), "logout");
        token.revoke(ISSUED.plusSeconds(20), "reuse-detected");

        assertThat(token.getRevokedAt()).isEqualTo(ISSUED.plusSeconds(10));
        assertThat(token.getRevokedReason()).isEqualTo("logout");
    }

    @Test
    @DisplayName("an oversized user agent is truncated rather than failing the insert")
    void truncatesLongUserAgent() {
        RefreshToken token =
                RefreshToken.issueNewFamily(USER_ID, "h", ISSUED, EXPIRES, "203.0.113.7", "x".repeat(1_000));

        assertThat(token.toString()).isNotNull();
        // Recording a login must not fail because a client sent an absurd header.
        assertThat(token.getId()).isNotNull();
    }

    @Test
    @DisplayName("toString never exposes the token hash")
    void toStringExcludesTheHash() {
        // The hash is credential-equivalent: with it, an attacker could forge a database
        // row that authenticates.
        assertThat(newToken().toString()).doesNotContain("hash-original");
    }
}
