package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Working (provisional) diagnosis for a patient encounter (inc-05 C6,
 * WorkingDiagnosis.java:28-65).
 *
 * <p>One row per (consultation, diagnosisType) or (admissionUid, diagnosisType) pair.
 * The consultation path enforces a DUPLICATE GUARD at the application layer
 * (PatientResource.java:1654-1678): if a WorkingDiagnosis already exists for the same
 * (consultation, diagnosisTypeUid), an {@link com.otapp.hmis.shared.error.InvalidPatientOperationException}
 * is thrown with the verbatim message {@code "Duplicate Diagnosis Types is not allowed"}.
 *
 * <p>The admission path intentionally has NO duplicate guard (CR-INC05-07 asymmetry —
 * 11-DECISIONS-RATIFIED.md §2). Admission diagnosis endpoints are DEFERRED to the
 * Inpatient/Nursing increment.
 *
 * <p><strong>Two SEPARATE tables (NOT one discriminated entity):</strong>
 * {@link WorkingDiagnosis} (provisional) and {@link FinalDiagnosis} (confirmed) are byte-for-byte
 * structural twins stored in separate tables ({@code working_diagnoses} / {@code final_diagnoses}).
 * The planning-doc suggested one polymorphic table — that was a drift correction (REJECTED).
 *
 * <p><strong>Encounter binding (exactly one non-null — V23 CHECK num_nonnulls = 1):</strong>
 * <ul>
 *   <li>{@code consultation} — real intra-module {@code @ManyToOne} to {@link Consultation}
 *       (same schema, same module; FK {@code consultation_id}). Nullable — null when bound
 *       to an admission.</li>
 *   <li>{@code admissionUid} — loose {@code VARCHAR(26)} reference only; no FK
 *       (admission module not yet built — DEFERRED). Nullable — null when bound to a
 *       consultation.</li>
 * </ul>
 *
 * <p><strong>Patient reference (ADR-0022 D2 Correction — V32):</strong>
 * {@code patient_uid} is a loose cross-module ref (VARCHAR(26), NOT NULL). The original
 * V23 {@code patient_id BIGINT FK → patients(id)} cross-module FK is dropped by V32.
 * At save time {@code patientUid} is copied from {@code consultation.getPatientUid()}.
 *
 * <p><strong>DiagnosisType reference (loose uid, updatable=false):</strong>
 * {@code diagnosisTypeUid} is a MANDATORY loose ref to {@code masterdata.diagnosis_types}.
 * No JPA association is mapped — the clinical module must not import masterdata entities
 * (ADR-0008 §1). Existence is verified via {@link com.otapp.hmis.masterdata.lookup.DiagnosisTypeLookup}
 * before persisting. The column is {@code updatable=false}: editing = delete + re-add.
 *
 * <p><strong>APPEND semantics:</strong>
 * Each call to {@code addWorkingDiagnosis} appends a new row (provided the dup-guard passes).
 * Multiple distinct diagnosis types per consultation are allowed.
 *
 * <p><strong>DELETE:</strong>
 * Hard-delete only (no soft-delete, no audit flag). Legacy does {@code deleteById}
 * without an existence check; this implementation adds a clean 404 on missing uid
 * (defensive improvement — documented as such, not a business-rule change).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: WorkingDiagnosis.java:28-65</li>
 *   <li>Save (consultation path): PatientResource.java:1654-1678</li>
 *   <li>Delete: PatientResource.java:1917-1929</li>
 *   <li>Duplicate guard: PatientResource.java:1662</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "working_diagnoses")
public class WorkingDiagnosis extends AuditableEntity {

    /**
     * Optional free-text description / notes for this working diagnosis (TEXT, nullable).
     * (WorkingDiagnosis.java — description field; no Bean Validation annotation in legacy.)
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * MANDATORY loose cross-module ref to the diagnosis type in the masterdata module.
     * (WorkingDiagnosis.java:48-53 — {@code @ManyToOne DiagnosisType} replaced with
     * loose uid per ADR-0008 §1, ADR-0022 D2.)
     *
     * <p>{@code updatable=false}: the diagnosis type is set at creation and cannot be changed
     * (editing = delete + re-add, legacy parity).
     */
    @NotBlank
    @Column(name = "diagnosis_type_uid", length = 26, nullable = false, updatable = false)
    private String diagnosisTypeUid;

    /**
     * Intra-module real FK to the owning consultation ({@code @ManyToOne}, nullable).
     * NULL when this diagnosis is bound to an admission encounter.
     * {@code updatable=false}: the encounter link is set at creation and never changed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", updatable = false)
    private Consultation consultation;

    /**
     * Loose cross-module ref to an admission (VARCHAR(26), nullable, no FK).
     * The admissions module is not yet built (DEFERRED, inc-05 C6).
     * NULL when this diagnosis is bound to a consultation.
     */
    @Column(name = "admission_uid", length = 26, updatable = false)
    private String admissionUid;

    /**
     * MANDATORY loose cross-module ref to the patient (VARCHAR(26), NOT NULL).
     * Replaces the original V23 {@code patient_id BIGINT FK → patients(id)} per ADR-0022 D2.
     * The V32 migration drops the old column and FK; this column is backfilled and set NOT NULL.
     *
     * <p>Set at creation from {@code consultation.getPatientUid()} (consultation path).
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    /**
     * Loose reference to the open business day at time of creation (no FK, ADR-0009).
     */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Create a WorkingDiagnosis bound to a {@link Consultation} (the primary path).
     *
     * <p>The {@code patientUid} is copied from the consultation — it is NOT supplied
     * independently by the caller (PatientResource.java:1672-1674 — patient is derived from
     * the consultation's patient).
     *
     * @param consultation      the owning consultation (intra-module @ManyToOne)
     * @param diagnosisTypeUid  the MANDATORY loose uid of the diagnosis type (masterdata)
     * @param description       optional free-text description (nullable)
     * @param businessDayUid    loose uid of the open business day
     * @return a new WorkingDiagnosis ready to persist
     */
    public static WorkingDiagnosis forConsultation(Consultation consultation,
                                                    String diagnosisTypeUid,
                                                    String description,
                                                    String businessDayUid) {
        WorkingDiagnosis wd = new WorkingDiagnosis();
        wd.consultation = consultation;
        wd.diagnosisTypeUid = diagnosisTypeUid;
        wd.description = description;
        wd.patientUid = consultation.getPatientUid();
        wd.businessDayUid = businessDayUid;
        return wd;
    }
}
