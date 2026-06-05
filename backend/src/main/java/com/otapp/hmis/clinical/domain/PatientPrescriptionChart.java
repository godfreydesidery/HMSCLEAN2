package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Inpatient drug-administration chart record (PatientPrescriptionChart.java:34-82).
 *
 * <p>Maps the V27 {@code patient_prescription_charts} table.
 *
 * <p><strong>DEFERRED — write path (C10 build-spec §5):</strong>
 * Creating a chart record requires an admission IN-PROCESS (PatientServiceImpl.java:2564-2577).
 * Admissions do not exist yet. The entity and repository are mapped so that
 * {@code ddl-auto=validate} passes and the table is available for the Inpatient/Nursing
 * increment. No create/update endpoints are provided in C10. Document-only TODO.
 *
 * <p>Fields (PatientPrescriptionChart.java:50-80):
 * <ul>
 *   <li>{@code dosage} — administered dosage (free-text VARCHAR(200)).</li>
 *   <li>{@code output} — observed output (free-text VARCHAR(200)).</li>
 *   <li>{@code remark} — remark (TEXT).</li>
 *   <li>{@code prescription} — mandatory real FK to the parent prescription.</li>
 *   <li>Encounter binding: exactly one of consultation / nonConsultation / admissionUid.</li>
 *   <li>{@code patientId} — cross-module patient FK (V27 bigint; loose uid after V36).</li>
 *   <li>{@code clinicianUserUid} / {@code nurseUid} — loose cross-module refs.</li>
 * </ul>
 *
 * <p>Application rules NOT enforced here (deferred):
 * <ul>
 *   <li>Linked prescription must be GIVEN (PatientServiceImpl.java:2544).</li>
 *   <li>Requires admission IN-PROCESS + nurse UID (PatientServiceImpl.java:2564-2577).</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: PatientPrescriptionChart.java:34-82</li>
 *   <li>prescription FK: PatientPrescriptionChart.java:57-60</li>
 *   <li>nurse loose ref: PatientPrescriptionChart.java:72-75</li>
 *   <li>GIVEN guard: PatientServiceImpl.java:2544</li>
 *   <li>admission + nurse guard: PatientServiceImpl.java:2564-2577</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_prescription_charts")
public class PatientPrescriptionChart extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Free-text administration fields
    // -------------------------------------------------------------------------

    /** Administered dosage (free-text VARCHAR(200)). */
    @Column(name = "dosage", length = 200)
    private String dosage;

    /** Observed output (free-text VARCHAR(200)). */
    @Column(name = "output", length = 200)
    private String output;

    /** Remark (TEXT). */
    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    // -------------------------------------------------------------------------
    // Mandatory FK to parent prescription
    // -------------------------------------------------------------------------

    /**
     * Parent prescription (intra-module real FK — PatientPrescriptionChart.java:57-60).
     * NOT NULL — every chart entry links back to its prescription.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id", nullable = false, updatable = false)
    private Prescription prescription;

    // -------------------------------------------------------------------------
    // Encounter binding (exactly one non-null — V27 CHECK num_nonnulls=1)
    // -------------------------------------------------------------------------

    /**
     * Intra-module real FK to the owning consultation (@ManyToOne, nullable).
     * NULL when bound to non-consultation or admission.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", updatable = false)
    private Consultation consultation;

    /**
     * Intra-module real FK to the owning non-consultation (@ManyToOne, nullable).
     * NULL when bound to consultation or admission.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "non_consultation_id", updatable = false)
    private NonConsultation nonConsultation;

    /**
     * Loose ref to an admission (VARCHAR(26), nullable, no FK).
     * Admissions module DEFERRED. NULL when bound to consultation or non-consultation.
     */
    @Column(name = "admission_uid", length = 26, updatable = false)
    private String admissionUid;

    // -------------------------------------------------------------------------
    // Patient ref (V27 patient_id BIGINT FK; V36 converts to loose patient_uid)
    // -------------------------------------------------------------------------

    /**
     * Loose cross-module ref to the patient (added by V36 — replaces patient_id BIGINT FK).
     * Same ADR-0022 D2 Correction pattern as prescriptions.patient_uid.
     */
    @Column(name = "patient_uid", length = 26)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Loose cross-module refs (no FK)
    // -------------------------------------------------------------------------

    /** Optional loose ref to the ordering clinician user (nullable). */
    @Column(name = "clinician_user_uid", length = 26)
    private String clinicianUserUid;

    /**
     * Loose ref to the nurse administering the drug (nullable).
     * Required by the inpatient chart path but stored as a loose uid (no FK — PatientPrescriptionChart.java:72-75).
     */
    @Column(name = "nurse_uid", length = 26)
    private String nurseUid;

    /** Business day uid at time of record creation. */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    /**
     * Factory for the inpatient dosing-note write path (inc-07 07b — the write path
     * deferred in C10 is now implemented in {@code PrescriptionChartPortImpl}).
     *
     * <p>Binds the chart to a prescription + a loose {@code admissionUid} + the administering
     * {@code nurseUid} (the admission-context path; consultation/nonConsultation stay null —
     * the V27 {@code num_nonnulls=1} CHECK is satisfied by {@code admission_uid}). The
     * GIVEN-prescription guard and the admission-IN-PROCESS/nurse guards are enforced by the
     * caller (PatientServiceImpl.java:2544, :2564-2577) before this factory is called.
     *
     * @param prescription mandatory parent prescription (must be GIVEN — guard upstream)
     * @param admissionUid loose admission ref (the inpatient dosing-note context)
     * @param patientUid   loose patient ref
     * @param nurseUid     loose administering-nurse ref (required for the inpatient path)
     * @param dosage       administered dosage free-text (nullable)
     * @param output       observed output free-text (nullable)
     * @param remark       remark free-text (nullable)
     * @return a new chart entry (status defaults handled by the entity; status is not modelled
     *         on this chart — legacy has no status column on patient_prescription_charts)
     */
    public static PatientPrescriptionChart createForAdmission(
            Prescription prescription, String admissionUid, String patientUid, String nurseUid,
            String dosage, String output, String remark) {
        PatientPrescriptionChart chart = new PatientPrescriptionChart();
        chart.prescription = prescription;
        chart.admissionUid = admissionUid;
        chart.patientUid = patientUid;
        chart.nurseUid = nurseUid;
        chart.dosage = dosage;
        chart.output = output;
        chart.remark = remark;
        return chart;
    }
}
