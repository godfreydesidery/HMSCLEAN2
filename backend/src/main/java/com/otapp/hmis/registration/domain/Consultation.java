package com.otapp.hmis.registration.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * PENDING consultation booking stub — the minimal inc-03 aggregate (build-spec §3, CR-21).
 *
 * <p>This entity is a temporary home for the Consultation aggregate.  The full clinical
 * consultation (open/free/transfer, clinical notes, diagnosis, the consultation-bill
 * PAID/COVERED open-gate via {@code SettlementPolicy}, doctor worklist) belongs to the
 * {@code clinical} module (inc-05, deferred).  The inc-05 spec carries the
 * ownership-transfer plan (ADR-0008-R1, CR-21).
 *
 * <p>Cross-module refs ({@code clinicUid}, {@code clinicianUserUid}, {@code patientBillUid},
 * {@code businessDayUid}) are plain {@code String} columns with NO FK (ADR-0008).
 *
 * <p>{@code status} is {@link ConsultationStatus#PENDING} only in inc-03 (CR-18).
 *
 * <p>{@code followUp} true means the consultation is a follow-up with no charge; the
 * consultation-fee {@code PatientBill} carries status {@code NONE} in that case
 * (PatientServiceImpl.java:467-469; build-spec §3.2, CR-20 — mechanism OPEN, resolved
 * in C6 via {@code ChargeRequest.followUp} flag extension to billing).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/Consultation.java:47-110</li>
 *   <li>Creation (send-to-doctor): PatientServiceImpl.java:425-679</li>
 *   <li>Follow-up NONE bill: PatientServiceImpl.java:467-469</li>
 *   <li>1:1 non-null bill: Consultation.java:70-73</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "consultations")
public class Consultation extends AuditableEntity {

    /**
     * The patient for whom this consultation is booked (Consultation.java:62-65).
     * ManyToOne: a patient may have multiple consultations.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    /**
     * The visit associated with this consultation (Consultation.java:93-97).
     * Nullable: linked at booking time but technically optional in the stub.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "visit_id", nullable = true, updatable = false)
    private Visit visit;

    /**
     * Loose cross-module ref to the clinic in the masterdata module (no FK, ADR-0008).
     * (Consultation.java:77-80 — legacy @ManyToOne Clinic; replaced with loose uid ref.)
     */
    @NotBlank
    @Column(name = "clinic_uid", length = 26, nullable = false, updatable = false)
    private String clinicUid;

    /**
     * Loose cross-module ref to the clinician user in the iam module (no FK, ADR-0008).
     * (Consultation.java:85-88 — legacy @ManyToOne Clinician; replaced with loose uid ref.)
     */
    @NotBlank
    @Column(name = "clinician_user_uid", length = 26, nullable = false)
    private String clinicianUserUid;

    /**
     * Loose cross-module ref to the consultation-fee {@code PatientBill} in the billing
     * module (Consultation.java:70-73; ADR-0008 — no FK).  NOT NULL: every consultation has
     * exactly one fee bill (including follow-up NONE bills).
     */
    @NotBlank
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Payment method, denormalised from {@link Patient#getPaymentType()} at booking time
     * (Consultation.java:53).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20, nullable = false, updatable = false)
    private PaymentType paymentType;

    /**
     * Follow-up flag (Consultation.java:57).
     * {@code true} ⇒ NONE bill — no charge; this flag is the parity equivalent of
     * the legacy {@code PatientServiceImpl.java:467-469} follow-up waiver (CR-20 HDE BLOCKER).
     */
    @Column(name = "follow_up", nullable = false)
    private boolean followUp = false;

    /**
     * Lifecycle status.  Only {@link ConsultationStatus#PENDING} in inc-03 (CR-18, CR-21).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ConsultationStatus status = ConsultationStatus.PENDING;

    /**
     * Loose cross-module ref to the open business day at time of booking (no FK).
     * Sourced from {@code BusinessDayService.currentUid()} (ADR-0009 §7).
     */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false, updatable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING consultation booking.
     *
     * @param patient            the patient (intra-module FK)
     * @param visit              the associated visit (nullable at this stage)
     * @param clinicUid          loose uid of the target clinic (masterdata module)
     * @param clinicianUserUid   loose uid of the assigned clinician user (iam module)
     * @param patientBillUid     loose uid of the consultation-fee PatientBill (billing module)
     * @param paymentType        payment type, copied from patient at booking time
     * @param followUp           true if this is a follow-up (NONE bill, no charge — CR-20)
     * @param businessDayUid     loose uid of the current open business day
     */
    public Consultation(Patient patient, Visit visit,
                        String clinicUid, String clinicianUserUid,
                        String patientBillUid, PaymentType paymentType,
                        boolean followUp, String businessDayUid) {
        this.patient = patient;
        this.visit = visit;
        this.clinicUid = clinicUid;
        this.clinicianUserUid = clinicianUserUid;
        this.patientBillUid = patientBillUid;
        this.paymentType = paymentType;
        this.followUp = followUp;
        this.status = ConsultationStatus.PENDING;
        this.businessDayUid = businessDayUid;
    }

    // -------------------------------------------------------------------------
    // Domain methods
    // -------------------------------------------------------------------------

    /**
     * Reassign the clinician (e.g. doctor handover in queue — inc-05 extension point).
     * Guarded by application service (affiliation check).
     */
    public void reassignClinician(String newClinicianUserUid) {
        this.clinicianUserUid = newClinicianUserUid;
    }
}
