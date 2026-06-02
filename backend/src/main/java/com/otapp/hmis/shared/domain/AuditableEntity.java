package com.otapp.hmis.shared.domain;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Dual-identifier mapped superclass (ADR-0003, ADR-0005, ADR-0014 §1).
 *
 * <ul>
 *   <li>{@code id} — {@code BIGINT GENERATED ALWAYS AS IDENTITY}. Private, no public getter/setter
 *       ({@code @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)}). It MUST NOT appear in any
 *       DTO, response body, URL, log, or cross-module payload.
 *   <li>{@code uid} — a ULID stored as {@code CHAR(26)}, generated in {@link #assignUid()} via
 *       {@link UlidCreator#getMonotonicUlid()}. Getter-only ({@code @Setter(AccessLevel.NONE)});
 *       the ONLY public identifier.
 *   <li>{@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy} — Spring Data
 *       JPA auditing columns.
 *   <li>{@code version} — optimistic locking.
 * </ul>
 *
 * <p>Lombok generates the accessors (ADR-0014, DIRECTIVE 1): no hand-written boilerplate. The
 * {@code id} field is deliberately excluded from accessor generation so it is never reachable from
 * API/DTO code — the ArchUnit "no id exposure" gate stays green.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    /** Internal surrogate key. No public getter/setter — never serialised anywhere (ADR-0014 §1). */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", insertable = false, updatable = false)
    private Long id;

    /** The public, opaque, URL-safe identifier (ULID). Getter-only; set once in {@code @PrePersist}. */
    @Setter(AccessLevel.NONE)
    @Column(name = "uid", length = 26,
            nullable = false, unique = true, updatable = false)
    private String uid;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 80, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 80)
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void assignUid() {
        if (this.uid == null) {
            this.uid = UlidCreator.getMonotonicUlid().toString();
        }
    }
}
