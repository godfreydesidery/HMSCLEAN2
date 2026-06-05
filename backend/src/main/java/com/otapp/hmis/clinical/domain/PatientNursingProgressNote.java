package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Nursing progress note — single free-text {@code note} column.
 *
 * <p>Maps the V48 {@code patient_nursing_progress_notes} table (inc-07 07b, AC-07B-NPR-01).
 * NO kind/category enum (PatientNursingProgressNote.java:38).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Field set: PatientNursingProgressNote.java:38</li>
 *   <li>Save guard order: PatientServiceImpl.java:2647-2698</li>
 *   <li>24h delete guard: PatientResource.java:3145-3161</li>
 * </ul>
 *
 * <p>inc-07 07b / AC-07B-NPR-01 / AC-07B-FLY-01.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_nursing_progress_notes")
public class PatientNursingProgressNote extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Single free-text note column (PatientNursingProgressNote.java:38)
    // -------------------------------------------------------------------------

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

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
     * Create a new nursing progress note for an inpatient admission.
     *
     * @param admissionUid loose uid of the owning admission
     * @param patientUid   loose uid of the patient
     * @param nurseUid     loose uid of the nurse
     * @param note         progress note free-text (nullable)
     * @return new PatientNursingProgressNote (uid assigned on first persist)
     */
    public static PatientNursingProgressNote create(
            String admissionUid, String patientUid, String nurseUid, String note) {
        PatientNursingProgressNote n = new PatientNursingProgressNote();
        n.admissionUid = admissionUid;
        n.patientUid   = patientUid;
        n.nurseUid     = nurseUid;
        n.note         = note;
        return n;
    }
}
