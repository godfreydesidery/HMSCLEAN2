package com.otapp.hmis.shared.audit;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only audit record (ADR-0007).
 *
 * <p>Insert-only by application design; this entity exposes no setters after construction and the
 * repository offers no update/delete surface. Each row carries a SHA-256 {@code checksum} of its
 * canonical fields for tamper evidence. Unlike domain entities this does NOT extend
 * {@code AuditableEntity} (it has no optimistic-lock/version semantics and must never be mutated),
 * but it follows the same {@code BIGINT id} + {@code CHAR(26) uid} dual-key shape. Accessors are
 * Lombok-generated (DIRECTIVE 1); {@code id} is excluded so it is never exposed.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Getter(AccessLevel.NONE)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", insertable = false, updatable = false)
    private Long id;

    @Column(name = "uid", length = 26,
            nullable = false, unique = true, updatable = false)
    private String uid;

    @Column(name = "entity_type", length = 120, nullable = false, updatable = false)
    private String entityType;

    @Column(name = "entity_uid", length = 26, updatable = false)
    private String entityUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 10, nullable = false, updatable = false)
    private AuditAction action;

    @Column(name = "actor_username", length = 80, nullable = false, updatable = false)
    private String actorUsername;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "checksum", length = 64,
            nullable = false, updatable = false)
    private String checksum;

    AuditLog(String entityType, String entityUid, AuditAction action, String actorUsername,
             Instant occurredAt, String checksum) {
        this.entityType = entityType;
        this.entityUid = entityUid;
        this.action = action;
        this.actorUsername = actorUsername;
        this.occurredAt = occurredAt;
        this.checksum = checksum;
    }

    @PrePersist
    void assignUid() {
        if (this.uid == null) {
            this.uid = UlidCreator.getMonotonicUlid().toString();
        }
    }
}
