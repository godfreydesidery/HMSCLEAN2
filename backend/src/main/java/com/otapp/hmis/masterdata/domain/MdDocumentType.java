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
 * Document-type prefix registry (build-spec §1.5, §4, CR-09, CR-10).
 *
 * <p>Legacy has no {@code document_type} entity — every prefix is a hardcoded string
 * literal in the relevant {@code *ServiceImpl} (05-extract-stakeholders-system §3).
 * HMSCLEAN2 introduces this config table so document-number services can resolve the
 * correct prefix without scattered literals.
 *
 * <p>{@code kind} is the machine-readable document category name (e.g.
 * {@code GOODS_RECEIVED_NOTE}). {@code prefix} is the short string emitted on every
 * document number of that kind (e.g. {@code GRN}).
 *
 * <p>CR-10 fix: {@code STORE_TO_PHARMACY_TO} → {@code "SPTO"} and
 * {@code PHARMACY_TO_PHARMACY_TO} → {@code "PPTO"} (legacy emitted {@code "SPT"} for
 * both — a collision defect). No row may carry prefix {@code "SPT"}.
 *
 * <p>Lombok generates accessors (DIRECTIVE 1). Read-only after seed; no update method
 * needed for inc-02 (admin UI management deferred to a future increment).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "md_document_types")
public class MdDocumentType extends AuditableEntity {

    @NotBlank
    @Size(max = 60)
    @Column(name = "kind", length = 60, nullable = false, unique = true)
    private String kind;

    @NotBlank
    @Size(max = 12)
    @Column(name = "prefix", length = 12, nullable = false)
    private String prefix;

    public MdDocumentType(String kind, String prefix) {
        this.kind = kind;
        this.prefix = prefix;
    }
}
