package com.otapp.hmis.clinical.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Consultation aggregate — the full clinical lifecycle owner (ADR-0022 D1, inc-05 CR-21).
 *
 * <p>This entity was previously a PENDING-only stub in {@code registration.domain.Consultation}
 * (inc-03). It is now the permanent home of the consultation aggregate for the {@code clinical}
 * module (ADR-0022, D1). The {@code clinical} module owns the {@code consultations} table and all
 * lifecycle state (PENDING → IN-PROCESS → SIGNED-OUT / CANCELED / TRANSFERED / HELD).
 *
 * <p><strong>Cross-module FK discipline (ADR-0022 D2, ADR-0022 Correction):</strong>
 * The JPA {@code @ManyToOne Patient patient} and {@code @ManyToOne Visit visit} associations from
 * the inc-03 stub are REMOVED. They are replaced by plain {@code String} uid columns:
 * <ul>
 *   <li>{@code patientUid} → {@code patient_uid VARCHAR(26) NOT NULL}</li>
 *   <li>{@code visitUid}   → {@code visit_uid VARCHAR(26) NULLABLE}</li>
 * </ul>
 * These are the module-boundary-safe access path (pattern: same as {@code clinicUid},
 * {@code clinicianUserUid}, etc. — ADR-0008 §1). The DB-level FK columns {@code patient_id} and
 * {@code visit_id} were DROPPED by V29 migration (ADR-0022 Correction) after the uid columns
 * were backfilled and NOT NULL was enforced. Hibernate has no awareness of those dropped columns.
 *
 * <p><strong>Settlement flag (ADR-0022 D2/D4, inc-05 §5):</strong>
 * {@code settled} is the clinical-local settlement projection. It is set at booking time
 * (true for INSURANCE/COVERED/follow-up NONE, false for CASH-OPD) and is the ONLY settlement
 * signal the clinical module ever reads. The {@code open_consultation} transition evaluates
 * {@link com.otapp.hmis.billing.api.SettlementPolicy#requireSettled} against this flag.
 * The clinical module NEVER calls back into billing to check bill status (ADR-0008 §6,
 * inc-05 §5). For CASH consultations, settled starts {@code false}; the cash-PAID→settled=true
 * propagation is IMPLEMENTED via the {@code ConsultationSettlementListener} / {@code SettlementDispatcher}
 * event seam (ADR-0022 D5, inc-05 §5 — WIRED, not deferred).
 *
 * <p><strong>Settlement seam (IMPLEMENTED — inc-05 §5, ADR-0022 Correction):</strong>
 * When a CASH patient pays their consultation bill, {@code billing.SettlementDispatcher.onBillPaid}
 * publishes a {@code BillSettledEvent}; {@code ConsultationSettlementListener} consumes it in the
 * SAME transaction (BEFORE_COMMIT) and flips this local {@code settled} flag to {@code true}.
 * All five entity types (Consultation, LabTest, Radiology, Procedure, Prescription) are wired.
 * INSURANCE/COVERED/NONE consultations have settled=true at booking (no payment required).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/Consultation.java:47-110</li>
 *   <li>Creation: PatientServiceImpl.java:425-679</li>
 *   <li>open_consultation: PatientResource.java:886</li>
 *   <li>cancel_consultation: PatientResource.java:618</li>
 *   <li>free_consultation: PatientResource.java:699, :764</li>
 *   <li>switch_to_consultation: PatientResource.java (followUp toggle)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "consultations")
public class Consultation extends AuditableEntity {

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2 — replaces @ManyToOne Patient).
     * Backfilled from patient.uid via V29 migration (additive, loss-free).
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    /**
     * Loose cross-module ref to the visit (ADR-0022 D2 — replaces @ManyToOne Visit).
     * Nullable: same nullability as the legacy visit_id FK (Consultation.java:93-97).
     * Backfilled from visits.uid via V29 migration.
     */
    @Column(name = "visit_uid", length = 26, nullable = true, updatable = false)
    private String visitUid;

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
     * module (Consultation.java:70-73; ADR-0008 — no FK). NOT NULL: every consultation has
     * exactly one fee bill (including follow-up NONE bills).
     */
    @NotBlank
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Payment mode, denormalised from the patient at booking time (Consultation.java:53).
     * Uses {@link PaymentMode} from billing::api (the only billing type clinical may import —
     * ADR-0008, ADR-0022 D5). Stored via {@code @Enumerated(STRING)} as VARCHAR(20).
     * CASH / INSURANCE — vocabulary is identical to the registration PaymentType.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20, nullable = false, updatable = false)
    private PaymentMode paymentMode;

    /**
     * Follow-up flag (Consultation.java:57).
     * {@code true} ⇒ NONE bill — no charge; this flag is the parity equivalent of the legacy
     * {@code PatientServiceImpl.java:467-469} follow-up waiver (CR-20 HDE BLOCKER).
     */
    @Column(name = "follow_up", nullable = false)
    private boolean followUp = false;

    /**
     * Lifecycle status. The full 6-value legacy set is live in inc-05 (CR-21).
     * Mapped via {@link ConsultationStatusConverter} (not {@code @Enumerated}) because the
     * hyphenated legacy values (IN-PROCESS, SIGNED-OUT) are not valid enum constant names.
     */
    @NotNull
    @Convert(converter = ConsultationStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private ConsultationStatus status = ConsultationStatus.PENDING;

    /**
     * Loose cross-module ref to the open business day at time of booking (no FK).
     * Sourced from {@code BusinessDayService.currentUid()} (ADR-0009 §7).
     */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false, updatable = false)
    private String businessDayUid;

    /**
     * Insurance membership number, denormalised from patient at booking time (Consultation.java:54).
     * Empty string for CASH patients; set from patient.membershipNo for INSURANCE.
     * Column added by V20 (membership_no VARCHAR(100) DEFAULT '').
     */
    @Column(name = "membership_no", length = 100)
    private String membershipNo = "";

    /**
     * Loose cross-module ref to the insurance plan (no FK, ADR-0008).
     * NULL for CASH patients; set from patient.insurancePlanUid for INSURANCE.
     * Column added by V20 (insurance_plan_uid VARCHAR(26) NULL).
     */
    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    /**
     * Clinical-local settlement projection (ADR-0022 D2/D4, inc-05 §5).
     *
     * <p>Set at booking:
     * <ul>
     *   <li>{@code true}  — for INSURANCE/COVERED, follow-up NONE (auto-pass — no prepayment required)</li>
     *   <li>{@code false} — for CASH-OPD (must be paid before {@code open_consultation} can proceed)</li>
     * </ul>
     * Flipped to {@code true} when the cash bill is paid via the {@code ConsultationSettlementListener}
     * event seam (ADR-0022 D5, ADR-0022 Correction — IMPLEMENTED in inc-05 §5; not deferred).
     * The {@code open_consultation} transition evaluates
     * {@link com.otapp.hmis.billing.api.SettlementPolicy#requireSettled} against this flag ONLY.
     * The clinical module NEVER reads billing bill status (ADR-0008 §6).
     *
     * <p>Column added by V29 migration (settled BOOLEAN NOT NULL DEFAULT FALSE).
     */
    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING consultation booking (ADR-0022 D3 — booked via clinical::api).
     *
     * @param patientUid         loose uid of the patient (registration module)
     * @param visitUid           loose uid of the associated visit (nullable; registration module)
     * @param clinicUid          loose uid of the target clinic (masterdata module)
     * @param clinicianUserUid   loose uid of the assigned clinician user (iam module)
     * @param patientBillUid     loose uid of the consultation-fee PatientBill (billing module)
     * @param paymentMode        payment mode, copied from patient at booking time (billing::api)
     * @param followUp           true if this is a follow-up (NONE bill, no charge — CR-20)
     * @param settled            true if the booking pre-pass determines no prepayment required
     * @param membershipNo       insurance membership number (empty for CASH)
     * @param insurancePlanUid   loose uid of the insurance plan (null for CASH)
     * @param businessDayUid     loose uid of the current open business day
     */
    public Consultation(String patientUid, String visitUid,
                        String clinicUid, String clinicianUserUid,
                        String patientBillUid, PaymentMode paymentMode,
                        boolean followUp, boolean settled,
                        String membershipNo, String insurancePlanUid,
                        String businessDayUid) {
        this.patientUid = patientUid;
        this.visitUid = visitUid;
        this.clinicUid = clinicUid;
        this.clinicianUserUid = clinicianUserUid;
        this.patientBillUid = patientBillUid;
        this.paymentMode = paymentMode;
        this.followUp = followUp;
        this.settled = settled;
        this.membershipNo = membershipNo != null ? membershipNo : "";
        this.insurancePlanUid = insurancePlanUid;
        this.status = ConsultationStatus.PENDING;
        this.businessDayUid = businessDayUid;
    }

    // -------------------------------------------------------------------------
    // Domain methods — lifecycle
    // -------------------------------------------------------------------------

    /**
     * Open the consultation: PENDING → IN_PROCESS.
     *
     * <p>Guards must be verified by the service layer before calling this:
     * status == PENDING and settlement pre-checked.
     */
    public void open() {
        this.status = ConsultationStatus.IN_PROCESS;
    }

    /**
     * Cancel the consultation: PENDING → CANCELED.
     *
     * <p>Guard must be verified by the service layer before calling this: status == PENDING.
     */
    public void cancel() {
        this.status = ConsultationStatus.CANCELED;
    }

    /**
     * Free (sign out) the consultation: IN_PROCESS or TRANSFERED → SIGNED_OUT.
     *
     * <p>Guard must be verified by the service layer before calling this.
     */
    public void free() {
        this.status = ConsultationStatus.SIGNED_OUT;
    }

    /**
     * Mark this consultation's local settlement flag as settled.
     *
     * <p>Called by the billing→clinical settlement seam when the consultation's cash bill is PAID
     * (ADR-0022 D2, inc-05 §5). Until the seam chunk lands, this is called at booking pre-pass
     * for INSURANCE/COVERED/NONE. For CASH-OPD this is the deferred cash-PAID propagation path.
     */
    public void markSettled() {
        this.settled = true;
    }

    /**
     * Switch from follow-up to normal consultation.
     *
     * <p>Sets {@code followUp = false}. For CASH patients, also resets {@code settled = false}
     * so the payment gate is re-evaluated (parity: legacy bill transitions from NONE → UNPAID,
     * requiring cash payment before open). For INSURANCE patients, {@code settled} stays
     * {@code true} (covered patients don't need to re-pay — parity: COVERED status stays).
     *
     * <p>Legacy citation: PatientResource.java (switch_to_consultation logic).
     */
    public void switchToNormal() {
        this.followUp = false;
        if (this.paymentMode == PaymentMode.CASH) {
            // CASH follow-up→normal: payment is now required. Reset the gate.
            // Parity: legacy bill goes from NONE → UNPAID (equivalent local-flag reset).
            this.settled = false;
        }
        // INSURANCE stays settled=true — covered patients auto-pass.
    }

    /**
     * Mark this consultation TRANSFERED: IN_PROCESS → TRANSFERED.
     *
     * <p>Called by {@link com.otapp.hmis.clinical.application.ConsultationTransferService#raise}
     * as the first step of raising a clinic-to-clinic transfer.
     * Guard (status == IN_PROCESS) is verified by the service layer before calling this.
     *
     * <p>Legacy citation: PatientServiceImpl.java:2808 (set status TRANSFERED before saving
     * the ConsultationTransfer row).
     */
    public void markTransferred() {
        this.status = ConsultationStatus.TRANSFERED;
    }

    /**
     * Revert a TRANSFERED consultation back to IN_PROCESS.
     *
     * <p>Called by {@link com.otapp.hmis.clinical.application.ConsultationTransferService#cancelByConsultation}
     * when a pending transfer is canceled. The consultation returns to the active doctor-open
     * state rather than PENDING (PatientServiceImpl.java:2824-2826 — the status is explicitly
     * set to IN_PROCESS on cancel, NOT reverted to PENDING).
     */
    public void revertToInProcess() {
        this.status = ConsultationStatus.IN_PROCESS;
    }

    /**
     * Reassign the clinician (e.g. doctor handover in queue — inc-05 extension point).
     * Guarded by application service (affiliation check).
     */
    public void reassignClinician(String newClinicianUserUid) {
        this.clinicianUserUid = newClinicianUserUid;
    }

    /**
     * Mark this consultation HELD (any status → HELD).
     *
     * <p>Called by the deceased-note save transition ({@code save_deceased_note} — inc-05 C12).
     * The consultation is held unconditionally regardless of its current status — the legacy
     * sets status = HELD on the consultation when a death note is recorded, with no guard on
     * the prior status (PatientResource.java deceased-note save path).
     *
     * <p>Legacy citation: PatientResource.java (save_deceased_note → setStatus("HELD")).
     */
    public void markHeld() {
        this.status = ConsultationStatus.HELD;
    }

    /**
     * Unconditionally sign out the consultation: any status → SIGNED_OUT.
     *
     * <p>Called by the referral-plan transitions ({@code save_referral_plan} and
     * {@code get_referral_summary} — inc-05 C12). Unlike {@link #free()} which guards on
     * IN_PROCESS|TRANSFERED, this method sets SIGNED_OUT unconditionally. The referral
     * approve re-confirms SIGNED_OUT regardless of the current status (legacy behaviour —
     * the approve endpoint always re-sets the consultation to SIGNED_OUT).
     *
     * <p>Legacy citation: PatientResource.java (save_referral_plan / get_referral_summary
     * → setStatus("SIGNED-OUT") unconditionally).
     */
    public void signOut() {
        this.status = ConsultationStatus.SIGNED_OUT;
    }
}
