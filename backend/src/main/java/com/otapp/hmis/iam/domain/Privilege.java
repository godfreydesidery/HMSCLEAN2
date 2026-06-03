package com.otapp.hmis.iam.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A privilege is a CODE string (e.g. {@code ADMIN-ACCESS}, {@code GOODS_RECEIVED_NOTE-CREATE})
 * reproduced verbatim from the legacy {@code @PreAuthorize} gates (ADR-0006). It is the unit of
 * authority granted to a {@link Role}.
 *
 * <p>Increment-01 addition: {@code category} — {@code ACTIVE} for live gate codes (26);
 * {@code DEAD} for legacy commented-out codes (9) that are seeded for catalogue parity but must
 * never appear in a modern {@code @PreAuthorize} (enforced by {@code PrivilegeGateArchTest}).
 *
 * <p>Legacy source: {@code com.orbix.api.domain.Privilege} (the "name" field is the code here).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "privileges")
public class Privilege extends AuditableEntity {

    @Column(name = "code", length = 80, nullable = false, unique = true, updatable = false)
    private String code;

    /**
     * {@code ACTIVE} — used in at least one live {@code @PreAuthorize} gate (26 codes).
     * {@code DEAD}   — commented-out in legacy; seeded for parity; never gate-referenced (9 codes).
     */
    @Column(name = "category", length = 12, nullable = false)
    private String category = "ACTIVE";

    public Privilege(String code) {
        this.code = code;
        this.category = "ACTIVE";
    }

    public Privilege(String code, String category) {
        this.code = code;
        this.category = category;
    }
}
