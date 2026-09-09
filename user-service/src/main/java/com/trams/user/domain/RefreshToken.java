package com.trams.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored refresh token, represented only by the SHA-256 hash of the opaque value handed
 * to the client.
 *
 * <p><strong>Rotation with reuse detection.</strong> Each refresh consumes the presented
 * token and issues a replacement in the same {@code familyId}. A token is therefore valid
 * exactly once. If an already-consumed token is presented again, the only explanations
 * are a stolen token being replayed or a client bug — either way the correct response is
 * to revoke the entire family, forcing re-authentication. Without this, an attacker who
 * copied a refresh token could keep minting access tokens indefinitely alongside the
 * legitimate user, undetected.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** SHA-256 hex digest; the plaintext token is never stored. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    /** Groups every token descended from a single login. */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;

    /** The token issued in place of this one, for auditing a rotation chain. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    protected RefreshToken() {}

    private RefreshToken(
            UUID userId,
            String tokenHash,
            UUID familyId,
            Instant issuedAt,
            Instant expiresAt,
            String clientIp,
            String userAgent) {

        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
        this.userAgent = truncate(userAgent, 255);
    }

    /** Issues the first token of a new family, i.e. a fresh login. */
    public static RefreshToken issueNewFamily(
            UUID userId, String tokenHash, Instant issuedAt, Instant expiresAt, String clientIp, String userAgent) {

        return new RefreshToken(userId, tokenHash, UUID.randomUUID(), issuedAt, expiresAt, clientIp, userAgent);
    }

    /** Issues the successor to this token, staying within the same family. */
    public RefreshToken rotate(
            String newTokenHash, Instant issuedAt, Instant expiresAt, String clientIp, String userAgent) {

        RefreshToken successor =
                new RefreshToken(userId, newTokenHash, familyId, issuedAt, expiresAt, clientIp, userAgent);

        this.revokedAt = issuedAt;
        this.revokedReason = "rotated";
        this.replacedBy = successor.id;

        return successor;
    }

    public void revoke(Instant when, String reason) {
        if (this.revokedAt == null) {
            this.revokedAt = when;
            this.revokedReason = truncate(reason, 64);
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /** Usable exactly once, before expiry, and only while not revoked. */
    public boolean isUsable(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    /** Excludes the token hash: it is a credential-equivalent secret. */
    @Override
    public String toString() {
        return "RefreshToken[id=%s, userId=%s, family=%s, revoked=%s]"
                .formatted(id, userId, familyId, revokedAt != null);
    }
}
