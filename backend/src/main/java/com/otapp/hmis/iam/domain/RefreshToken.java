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
 * Accessors are Lombok-generated (DIRECTIVE 1); state transitions go through {@link #revoke()}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends AuditableEntity {

    @Column(name = "user_uid", length = 26,
            nullable = false, updatable = false)
    private String userUid;

    @Column(name = "token_hash", length = 64,
            nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    public RefreshToken(String userUid, String tokenHash, Instant expiresAt) {
        this.userUid = userUid;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    public void revoke() {
        this.revoked = true;
    }

    public boolean isUsable(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }
}
