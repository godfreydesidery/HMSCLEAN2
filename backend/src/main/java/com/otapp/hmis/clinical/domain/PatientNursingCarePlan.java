package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Nursing care plan record — four free-text columns.
 *
 * <p>Maps the V48 {@code patient_nursing_care_plans} table (inc-07 07b, AC-07B-NCP-01).
 * NO status, NO lifecycle, NO ACTIVE/RESOLVED state (PatientNursingCarePlan.java:38-41).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Field set: PatientNursingCarePlan.java:38-41</li>
 *   <li>Save guard order: PatientServiceImpl.java:2593-2643</li>
 *   <li>24h delete guard: PatientResource.java:3163-3179</li>
 * </ul>
 *
 * <p>inc-07 07b / AC-07B-NCP-01 / AC-07B-FLY-01.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_nursing_care_plans")
public class PatientNursingCarePlan extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Four free-text columns (PatientNursingCarePlan.java:38-41)
    // -------------------------------------------------------------------------

    @Column(name = "nursing_diagnosis", columnDefinition = "TEXT")
    private String nursingDiagnosis;

    @Column(name = "expected_outcome", columnDefinition = "TEXT")
    private String expectedOutcome;

    @Column(name = "implementation", columnDefinition = "TEXT")
    private String implementation;

    @Column(name = "evaluation", columnDefinition = "TEXT")
    private String evaluation;

    // -------------------------------------------------------------------------
    // Loose cross-module refs (NO physical FK — ADR-0008 §1)
    // -------------------------------------------------------------------------

    @Column(name = "admission_uid", length = 26)
    private String admissionUid;

    @Column(name = "patient_uid", length = 26, nullable = false)
    private String patientUid;

    @Column(name = "nurse_uid", length = 26)
    private String nurseUid;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Create a new nursing care plan entry for an inpatient admission.
     *
     * @param admissionUid     loose uid of the owning admission
     * @param patientUid       loose uid of the patient
     * @param nurseUid         loose uid of the nurse
     * @param nursingDiagnosis nursing diagnosis free-text (nullable)
     * @param expectedOutcome  expected outcome free-text (nullable)
     * @param implementation   implementation free-text (nullable)
     * @param evaluation       evaluation free-text (nullable)
     * @return new PatientNursingCarePlan (uid assigned on first persist)
     */
    public static PatientNursingCarePlan create(
            String admissionUid, String patientUid, String nurseUid,
            String nursingDiagnosis, String expectedOutcome,
            String implementation, String evaluation) {
        PatientNursingCarePlan p = new PatientNursingCarePlan();
        p.admissionUid    = admissionUid;
        p.patientUid      = patientUid;
        p.nurseUid        = nurseUid;
        p.nursingDiagnosis = nursingDiagnosis;
        p.expectedOutcome  = expectedOutcome;
        p.implementation   = implementation;
        p.evaluation       = evaluation;
        return p;
    }
}
