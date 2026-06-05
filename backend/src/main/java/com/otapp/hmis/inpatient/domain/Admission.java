package com.otapp.hmis.inpatient.domain;

import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Admission aggregate root — the inpatient stay lifecycle owner (inc-07 07a).
 *
 * <p><strong>Identity:</strong> a ULID {@code uid} (inherited from {@link AuditableEntity});
 * NO admission number / sequence column (confirmed by legacy: no admission-number field in
 * Admission.java — PatientServiceImpl.java:1713-1748 does not set any {@code no}).
 *
 * <p><strong>Cross-module FK discipline (ADR-0008 §1):</strong>
 * All cross-module references are loose VARCHAR(26) uid columns with NO physical FK:
 * <ul>
 *   <li>{@code patientUid}        → registration module</li>
 *   <li>{@code wardBedUid}        → masterdata module</li>
 *   <li>{@code insurancePlanUid}  → masterdata module (nullable — null for CASH patients)</li>
 * </ul>
 *
 * <p><strong>Payment mode:</strong> stored as {@link PaymentMode} (billing::api enum), matching
 * the pattern already established by {@link com.otapp.hmis.clinical.domain.Consultation}.
 * Denormalised from the patient at admission time (PatientServiceImpl.java:1715).
 *
 * <p><strong>Status lifecycle:</strong>
 * PENDING → IN_PROCESS (payment-gated via {@link #activate()}) → SIGNED_OUT (disposition, 07a-3).
 * PENDING → STOPPED → SIGNED_OUT is the alternate non-payment-received path.
 * HELD is a mid-admission suspension state.
 * Transitions guarded by the service layer; this entity only exposes intention-revealing methods.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/Admission.java:45 (free-text status)</li>
 *   <li>Creation: PatientServiceImpl.java:1713-1748</li>
 *   <li>Activation: PatientBillResource.java:352-365</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "admissions")
public class Admission extends AuditableEntity {

    /**
     * Loose cross-module ref to the patient (ADR-0008 §1).
     * Set at doAdmission and never changed (patientUid is immutable on the Admission record).
     * PatientServiceImpl.java:1714.
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    /**
     * Loose cross-module ref to the ward bed claimed at admission time (ADR-0008 §1).
     * Set at doAdmission; the physical bed state (EMPTY/WAITING/OCCUPIED) is owned by
     * masterdata via {@link com.otapp.hmis.masterdata.lookup.WardBedClaim}.
     * PatientServiceImpl.java:1718.
     */
    @NotBlank
    @Column(name = "ward_bed_uid", length = 26, nullable = false, updatable = false)
    private String wardBedUid;

    /**
     * Payment mode, denormalised from the patient at admission time.
     * CASH or INSURANCE. PatientServiceImpl.java:1715.
     * Uses {@link PaymentMode} from billing::api — same pattern as Consultation.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20, nullable = false, updatable = false)
    private PaymentMode paymentType;

    /**
     * Loose cross-module ref to the insurance plan (nullable — null for CASH patients).
     * Denormalised from the patient at admission time (PatientServiceImpl.java:1716).
     */
    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    /**
     * Insurance membership number (empty for CASH patients).
     * Denormalised from the patient at admission time (PatientServiceImpl.java:1717).
     */
    @Column(name = "membership_no", length = 100)
    private String membershipNo = "";

    /**
     * Lifecycle status. Five-value legacy vocabulary (PENDING / IN-PROCESS / STOPPED / HELD / SIGNED-OUT).
     * Mapped via {@link AdmissionStatusConverter} because two values are hyphenated.
     * Default: PENDING at creation (PatientServiceImpl.java:1719).
     */
    @NotNull
    @Convert(converter = AdmissionStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private AdmissionStatus status = AdmissionStatus.PENDING;

    /**
     * The instant the patient was admitted (doAdmission timestamp).
     * NOT NULL — set at construction (PatientServiceImpl.java:1746).
     */
    @NotNull
    @Column(name = "admitted_at", nullable = false, updatable = false)
    private Instant admittedAt;

    /**
     * The instant the patient was discharged / signed out (nullable — null while still admitted).
     * Set by the discharge/referral/deceased disposition flows (07a-3).
     * PatientServiceImpl.java — disposition summary save.
     */
    @Column(name = "discharged_at")
    private Instant dischargedAt;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING admission (doAdmission — PatientServiceImpl.java:1713-1748).
     *
     * @param patientUid       loose uid of the patient
     * @param wardBedUid       loose uid of the claimed ward bed
     * @param paymentType      CASH or INSURANCE (copied from patient at admission time)
     * @param insurancePlanUid loose uid of the insurance plan (null for CASH)
     * @param membershipNo     insurance membership number (empty for CASH)
     * @param admittedAt       the instant of admission
     */
    public Admission(String patientUid, String wardBedUid,
                     PaymentMode paymentType,
                     String insurancePlanUid, String membershipNo,
                     Instant admittedAt) {
        this.patientUid = patientUid;
        this.wardBedUid = wardBedUid;
        this.paymentType = paymentType;
        this.insurancePlanUid = insurancePlanUid;
        this.membershipNo = membershipNo != null ? membershipNo : "";
        this.status = AdmissionStatus.PENDING;
        this.admittedAt = admittedAt;
    }

    // -------------------------------------------------------------------------
    // Domain methods — lifecycle transitions
    // -------------------------------------------------------------------------

    /**
     * Activate this admission: PENDING → IN_PROCESS.
     *
     * <p>Called by the payment settlement listener when the ward-bed bill is paid
     * ({@link com.otapp.hmis.inpatient.application.AdmissionSettlementListener}).
     * Guard (status == PENDING) is verified by the caller before invoking this method.
     *
     * <p>Legacy citation: PatientBillResource.java:356 — {@code adm.setStatus("IN-PROCESS")}.
     */
    public void activate() {
        this.status = AdmissionStatus.IN_PROCESS;
    }

    /**
     * Sign out this admission (discharge / referral / deceased disposition — 07a-3).
     *
     * <p>Sets status SIGNED_OUT and stamps the discharged-at instant. Called by the disposition
     * service in 07a-3. Guard on current status is the responsibility of the caller.
     *
     * @param dischargedAt the instant of discharge
     */
    public void signOut(Instant dischargedAt) {
        this.status = AdmissionStatus.SIGNED_OUT;
        this.dischargedAt = dischargedAt;
    }
}
