package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Diagnosis catalog entry (legacy {@code com.orbix.api.domain.DiagnosisType},
 * DiagnosisType.java:37-55).
 *
 * <p>Entity name is {@code DiagnosisType} (NOT "Diagnosis") — the spec's naming is wrong;
 * legacy uses {@code DiagnosisType} throughout (03-extract-clinical §DiagnosisType).
 *
 * <p>There is NO {@code price}/uom, NO ICD code, NO ICD version, NO parent/child hierarchy,
 * NO category — all are net-new (CR-06). A plain code/name/description/active record only.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "diagnosis_types")
public class DiagnosisType extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    public DiagnosisType(String code, String name, String description, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
    }

    public void update(String code, String name, String description, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
    }
}
