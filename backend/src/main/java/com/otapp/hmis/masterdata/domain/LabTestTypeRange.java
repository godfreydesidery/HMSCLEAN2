package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Named-string label scoped to one {@link LabTestType}
 * (legacy {@code com.orbix.api.domain.LabTestTypeRange}, LabTestTypeRange.java:37-54).
 *
 * <p>This entity is NOTHING MORE than a named label. There are NO numeric bounds, NO sex/age
 * bands, NO flag classification, NO analyte reference. The spec's "LabTestAnalyte +
 * LabReferenceRange + RangeFlag" model does NOT exist in legacy (CR-05 — 03-extract-clinical §Q1).
 *
 * <p>At result-entry time the Angular form reads {@code labTestType.labTestTypeRanges} and
 * writes the chosen {@code range.name} as a free-text string onto {@code LabTest.range}
 * (lab-test.component.html:52-56). There is no FK back from a result to a range row.
 *
 * <p>The legacy {@code ON DELETE CASCADE} behaviour is reproduced via the DDL
 * {@code ON DELETE CASCADE} on the FK, consistent with legacy {@code orphanRemoval=true}
 * on the parent collection (LabTestType.java:60-64).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "lab_test_type_ranges")
public class LabTestTypeRange extends AuditableEntity {

    /**
     * The range label string (LabTestTypeRange.java:41-42, defaults to {@code ""}).
     * {@code @NotBlank} is from legacy; the DDL has {@code DEFAULT ''} but the application
     * requires a non-blank value to be meaningful.
     */
    @NotBlank
    @Column(name = "name", length = 200, nullable = false)
    private String name = "";

    /**
     * The owning lab-test type (LabTestTypeRange.java:44-47, optional=false).
     * Cascade delete is at the DDL level; JPA does not need {@code CascadeType.REMOVE} here
     * because the DB handles it.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lab_test_type_id", nullable = false, updatable = false)
    private LabTestType labTestType;

    /** Business constructor. */
    public LabTestTypeRange(String name, LabTestType labTestType) {
        this.name = name;
        this.labTestType = labTestType;
    }

    /** Mutates the name. {@code labTestType} FK is immutable after creation. */
    public void update(String name) {
        this.name = name;
    }
}
