package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * System currency configuration entry (build-spec §1.5, CR-07-currency).
 *
 * <p>Legacy has no Currency concept (05-extract-stakeholders-system §2 — verified by scan:
 * zero currency fields anywhere in {@code com.orbix.api}). This is a net-new system config
 * table introduced by the modernization to make the implicit single-currency assumption
 * explicit and configurable.
 *
 * <p>At most one row may have {@code defaultCurrency = true} — enforced by the partial unique
 * index {@code uq_md_currencies_default} on column {@code is_default} (V12 DDL).
 *
 * <p>Field is named {@code defaultCurrency} (not {@code isDefault}) to avoid the Lombok/MapStruct
 * boolean-accessor stripping issue: Lombok generates {@code isDefault()} from a field named
 * {@code isDefault}, which MapStruct resolves to property {@code default} — a reserved word.
 * Renaming to {@code defaultCurrency} gives accessor {@code isDefaultCurrency()} and MapStruct
 * property {@code defaultCurrency}, which maps cleanly.
 *
 * <p>Lombok generates accessors (DIRECTIVE 1). No public setters — mutation via
 * {@link #update(String, String, boolean)}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "md_currencies")
public class MdCurrency extends AuditableEntity {

    @NotBlank
    @Size(min = 3, max = 3)
    @Column(name = "code", length = 3, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 120, nullable = false)
    private String name;

    /** Mapped to column {@code is_default}; field named {@code defaultCurrency} to avoid
     *  the Lombok {@code isXxx} → MapStruct {@code xxx} property-name ambiguity. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultCurrency = false;

    public MdCurrency(String code, String name, boolean defaultCurrency) {
        this.code = code;
        this.name = name;
        this.defaultCurrency = defaultCurrency;
    }

    public void update(String code, String name, boolean defaultCurrency) {
        this.code = code;
        this.name = name;
        this.defaultCurrency = defaultCurrency;
    }
}
