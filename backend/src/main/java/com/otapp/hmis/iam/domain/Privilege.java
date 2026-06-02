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
 * authority granted to a {@link Role}. Accessor is Lombok-generated (DIRECTIVE 1).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "privileges")
public class Privilege extends AuditableEntity {

    @Column(name = "code", length = 80, nullable = false, unique = true, updatable = false)
    private String code;

    public Privilege(String code) {
        this.code = code;
    }
}
