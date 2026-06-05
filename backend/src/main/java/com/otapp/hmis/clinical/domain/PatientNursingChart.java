package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Nursing observation chart record — eight free-text observation columns.
 *
 * <p>Maps the V48 {@code patient_nursing_charts} table (inc-07 07b, AC-07B-NCA-01).
 * NO status column, NO FluidBalance/CareActivity sub-entities
 * (PatientNursingChart.java:38-45).
 *
 * <p>Context binding: admission-only in the 07b write path. All three context fields
 * (admissionUid/patientUid/nurseUid) are loose VARCHAR(26) with no physical FK
 * (ADR-0008 §1). The consultation/nonConsultation context ids exist only in the V48
 * schema comment; the 07b write path is admission-only (AC-07B-NCA-08: consultation →
 * 422 'Operation not available for outpatients'; nonConsultation → 422 'Operation not
 * available for outsiders').
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Field set: PatientNursingChart.java:38-45</li>
 *   <li>Save guard order: PatientServiceImpl.java:2593-2643</li>
 *   <li>24h delete guard: PatientResource.java:3135-3138</li>
 * </ul>
 *
 * <p>inc-07 07b / AC-07B-NCA-01 / AC-07B-FLY-01.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_nursing_charts")
public class PatientNursingChart extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Eight free-text observation columns (PatientNursingChart.java:38-45)
    // -------------------------------------------------------------------------

    @Column(name = "feeding", length = 500)
    private String feeding;

    @Column(name = "changing_position", length = 500)
    private String changingPosition;

    @Column(name = "bed_bathing", length = 500)
    private String bedBathing;

    @Column(name = "random_blood_sugar", length = 500)
    private String randomBloodSugar;

    @Column(name = "full_blood_sugar", length = 500)
    private String fullBloodSugar;

    @Column(name = "drainage_output", length = 500)
    private String drainageOutput;

    @Column(name = "fluid_intake", length = 500)
    private String fluidIntake;

    @Column(name = "urine_output", length = 500)
    private String urineOutput;

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
     * Create a new nursing chart entry for an inpatient admission.
     *
     * @param admissionUid   loose uid of the owning admission
     * @param patientUid     loose uid of the patient
     * @param nurseUid       loose uid of the nurse
     * @param feeding        feeding observation (free-text, nullable)
     * @param changingPosition  body-position change observation (free-text, nullable)
     * @param bedBathing     bed bathing observation (free-text, nullable)
     * @param randomBloodSugar random blood sugar reading (free-text, nullable)
     * @param fullBloodSugar full blood sugar reading (free-text, nullable)
     * @param drainageOutput drainage output (free-text, nullable)
     * @param fluidIntake    fluid intake (free-text, nullable)
     * @param urineOutput    urine output (free-text, nullable)
     * @return new PatientNursingChart (uid assigned on first persist)
     */
    public static PatientNursingChart create(
            String admissionUid, String patientUid, String nurseUid,
            String feeding, String changingPosition, String bedBathing,
            String randomBloodSugar, String fullBloodSugar,
            String drainageOutput, String fluidIntake, String urineOutput) {
        PatientNursingChart c = new PatientNursingChart();
        c.admissionUid     = admissionUid;
        c.patientUid       = patientUid;
        c.nurseUid         = nurseUid;
        c.feeding          = feeding;
        c.changingPosition = changingPosition;
        c.bedBathing       = bedBathing;
        c.randomBloodSugar = randomBloodSugar;
        c.fullBloodSugar   = fullBloodSugar;
        c.drainageOutput   = drainageOutput;
        c.fluidIntake      = fluidIntake;
        c.urineOutput      = urineOutput;
        return c;
    }
}
