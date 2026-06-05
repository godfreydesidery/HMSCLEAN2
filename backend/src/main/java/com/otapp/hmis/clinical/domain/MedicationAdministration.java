package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Closed-loop Medication Administration Record (MAR) entry — inc-07 07d, CR-07-MAR.
 *
 * <p><strong>NET-NEW — no legacy equivalent.</strong> The legacy system had NO MAR; the closest
 * artefact was the free-text {@link PatientPrescriptionChart} dosing note (dosage/output/remark,
 * built in 07b). This aggregate is <strong>additive over</strong> that path — it does NOT replace
 * it. MAR captures the structured who/what/when/how of an actual administration: the route
 * (controlled vocabulary via the {@code administration_routes} masterdata), the administration
 * instant, the dose given, and the observed patient response.
 *
 * <p>Maps the V53 {@code medication_administrations} table.
 *
 * <p>Owner-ruled posture (2026-06-05): standard PHI/audit — {@link AuditableEntity} + SHA-256
 * {@code AuditRecorder} on create, gated behind the {@code MEDICATION-ADMINISTER} privilege.
 * Because MAR ACs are net-new acceptance tests (not legacy golden-master parity), the field set
 * is the owner-specified minimum: {@code routeUid}, {@code administeredAt}, {@code doseGiven},
 * {@code patientResponse}.
 *
 * <p>Guard responsibility split (mirrors {@link PatientPrescriptionChart}):
 * <ul>
 *   <li>INPATIENT-SIDE: admission IN-PROCESS gate; route is a registered ACTIVE route (RouteLookup).</li>
 *   <li>CLINICAL-SIDE: linked prescription must be GIVEN; nurse uid required.</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "medication_administrations")
public class MedicationAdministration extends AuditableEntity {

    /**
     * Parent prescription (intra-module real FK). NOT NULL — every administration links back to
     * the prescription that was dispensed (must be GIVEN — guard enforced in the port).
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id", nullable = false, updatable = false)
    private Prescription prescription;

    /** Loose ref to the admission this administration was charted under (no FK — ADR-0008 §1). */
    @Column(name = "admission_uid", length = 26, updatable = false)
    private String admissionUid;

    /** Loose ref to the patient (resolved from the prescription — V36 loose-uid pattern). */
    @Column(name = "patient_uid", length = 26)
    private String patientUid;

    /** Loose ref to the administering nurse (required for the inpatient path; no FK). */
    @Column(name = "nurse_uid", length = 26)
    private String nurseUid;

    /**
     * Loose ref to the administration route (validated ACTIVE against the
     * {@code administration_routes} masterdata via RouteLookup before persist). NOT NULL —
     * a route is mandatory for a closed-loop MAR entry.
     */
    @Column(name = "route_uid", length = 26, nullable = false, updatable = false)
    private String routeUid;

    /** The instant the medication was administered (NOT NULL). */
    @Column(name = "administered_at", nullable = false, updatable = false)
    private Instant administeredAt;

    /** The dose actually given (free-text VARCHAR(200)). */
    @Column(name = "dose_given", length = 200)
    private String doseGiven;

    /** The observed patient response (free-text TEXT). */
    @Column(name = "patient_response", columnDefinition = "TEXT")
    private String patientResponse;

    /** Business day uid at time of record creation. */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    /**
     * Factory for the inpatient closed-loop MAR write path. The GIVEN-prescription, nurse-uid,
     * admission-IN-PROCESS and route-active guards are enforced by the callers (inpatient service
     * + clinical port) before this factory runs.
     *
     * @param prescription   mandatory parent prescription (must be GIVEN — guard upstream)
     * @param admissionUid   loose admission ref (the inpatient context)
     * @param patientUid     loose patient ref (resolved from the prescription)
     * @param nurseUid       loose administering-nurse ref (required)
     * @param routeUid       loose administration-route ref (must be a registered ACTIVE route)
     * @param administeredAt the administration instant
     * @param doseGiven      dose-given free-text (nullable)
     * @param patientResponse observed patient response free-text (nullable)
     * @param businessDayUid business day uid
     * @return a new MAR entry
     */
    public static MedicationAdministration create(
            Prescription prescription, String admissionUid, String patientUid, String nurseUid,
            String routeUid, Instant administeredAt, String doseGiven, String patientResponse,
            String businessDayUid) {
        MedicationAdministration mar = new MedicationAdministration();
        mar.prescription = prescription;
        mar.admissionUid = admissionUid;
        mar.patientUid = patientUid;
        mar.nurseUid = nurseUid;
        mar.routeUid = routeUid;
        mar.administeredAt = administeredAt;
        mar.doseGiven = doseGiven;
        mar.patientResponse = patientResponse;
        mar.businessDayUid = businessDayUid;
        return mar;
    }
}
