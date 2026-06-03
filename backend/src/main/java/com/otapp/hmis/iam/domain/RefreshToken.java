package com.otapp.hmis.iam.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Rotating refresh token (ADR-0006). The opaque token value is SHA-256 hashed at rest; a row is
 * one-time-use and marked {@code revoked} on rotation. Keyed by the user's public {@code uid}.
 *
 * <p>Increment-01 additions (D-2 / CR-10 / build-spec §4):
 * <ul>
 *   <li>{@code revokedAt} — timestamp set when {@link #revoke()} is called; supports forensic
 *       audit of reuse-detection events. NULL iff {@code revoked=FALSE} (DB CHECK enforces).
 *   <li>{@code replacedByUid} — UID of the successor token issued during rotation; self-FK to
 *       {@code refresh_tokens(uid)}; NULL for tokens revoked via reuse-detection or the revoke
 *       endpoint (no successor).
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends AuditableEntity {

    @Column(name = "user_uid", length = 26, nullable = false, updatable = false)
    private String userUid;

    @Column(name = "token_hash", length = 64, nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    /** Timestamp of revocation; NULL when token is still live. DB CHECK: revoked↔revokedAt. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** UID of the successor token (set during normal rotation; NULL for forced/reuse revocations). */
    @Column(name = "replaced_by_uid", length = 26)
    private String replacedByUid;

    public RefreshToken(String userUid, String tokenHash, Instant expiresAt) {
        this.userUid = userUid;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    /**
     * Mark this token as revoked (consumed or forced). Sets {@code revokedAt = now()}.
     * Does NOT set {@code replacedByUid} — call {@link #recordRotation(String)} separately for
     * normal rotation so the successor UID is captured.
     */
    public void revoke() {
        this.revoked = true;
        this.revokedAt = Instant.now();
    }

    /**
     * Record the successor token UID on normal rotation. Should be called before or after
     * {@link #revoke()} in the same transaction.
     */
    public void recordRotation(String successorUid) {
        this.replacedByUid = successorUid;
    }

    /** @return {@code true} if this token can still be used (not revoked and not expired). */
    public boolean isUsable(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }

    /** @return {@code true} if this token's validity period has passed (regardless of revocation). */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
