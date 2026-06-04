package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Prescription aggregate — the clinical medication order (Prescription.java:38-144).
 *
 * <p>SEPARATE entity (NOT part of PharmacySaleOrderDetail). Maps the V27
 * {@code prescriptions} table. Status has EXACTLY TWO values: {@code NOT-GIVEN} → {@code GIVEN}
 * (Prescription.java:50).
 *
 * <p><strong>Free-text directives (NOT FK):</strong>
 * {@code dosage}, {@code frequency}, {@code route}, and {@code days} are VARCHAR free-text
 * (not FK to any Dosage/Frequency/Route master). {@code days} is numeric-as-string, parsed
 * in the unfinished-course alert (PatientResource.java:4556).
 *
 * <p><strong>Quantities (Prescription.java:62-69):</strong>
 * {@code qty} NUMERIC(19,6) NOT NULL; {@code issued} NUMERIC(19,6) DEFAULT 0;
 * {@code balance} NUMERIC(19,6) NOT NULL (starts = qty, set to 0 on dispense).
 * Legacy {@code double} fields are migrated to {@code BigDecimal} (pre-approved guardrail).
 *
 * <p><strong>Encounter binding (V27 num_nonnulls=1):</strong>
 * Exactly one of {@code consultation} / {@code nonConsultation} / {@code admissionUid}
 * must be non-null. Consultation and non-consultation are real intra-module FKs;
 * admissionUid is a loose uid (admissions module DEFERRED).
 *
 * <p><strong>Cross-module refs:</strong>
 * {@code medicine_uid} NOT NULL loose ref to masterdata medicines (no FK, verified via
 * {@code MedicineLookup} before persist). {@code patient_id} is the V27 column; V36 converts
 * it to a loose {@code patient_uid} (same ADR-0022 D2 pattern as V33/V34/V35).
 * {@code patient_bill_uid} NOT NULL loose ref to billing. {@code issue_pharmacy_uid} nullable,
 * set on dispense.
 *
 * <p><strong>Lifecycle audit triplets (Prescription.java:99-143):</strong>
 * {@code ordered_*} is written at save. {@code accepted_*}, {@code held_*},
 * {@code rejected_*}, {@code verified_*} are DECLARED but NEVER written (boilerplate columns
 * kept for schema fidelity). {@code approved_*} + {@code approved_at} is THE dispense audit —
 * the ONLY group populated, written at {@link #issue}.
 *
 * <p><strong>Settlement (CR-INC05-01):</strong>
 * {@code settled} is added by V36 (not in V27). Set at prescribe time from the billing
 * ChargeResult: true for INSURANCE/COVERED, false for CASH-OPD. Mirrors the LabTest pattern.
 *
 * <p><strong>DEFERRED — admission path:</strong>
 * The {@code admission_uid} column is mapped as a loose nullable String. No admission-scoped
 * endpoints are implemented in C10. Deferred to Inpatient/Nursing increment.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: Prescription.java:38-144</li>
 *   <li>Two-status lifecycle: Prescription.java:50</li>
 *   <li>Free-text directives: Prescription.java:56-63</li>
 *   <li>Qty/issued/balance: Prescription.java:62-69</li>
 *   <li>medicine mandatory: Prescription.java:72-75</li>
 *   <li>issuePharmacy set on dispense: Prescription.java:97-100</li>
 *   <li>approved_* dispense audit: Prescription.java:139-143</li>
 *   <li>issueMedicine (dispense): PatientResource.java:3217-3245</li>
 *   <li>Duplicate drug guard: PatientServiceImpl.java (same-medicine-same-encounter)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "prescriptions")
public class Prescription extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Free-text directives (NOT FK — Prescription.java:56-63)
    // -------------------------------------------------------------------------

    /** Free-text dosage instructions (not FK to a Dosage master). */
    @Column(name = "dosage", length = 200)
    private String dosage;

    /** Free-text frequency (e.g. "OD", "BD" — not FK). */
    @Column(name = "frequency", length = 200)
    private String frequency;

    /** Free-text route of administration (not FK). */
    @Column(name = "route", length = 200)
    private String route;

    /**
     * Duration in days as a numeric string (Prescription.java:62; PatientResource.java:4556).
     * Parsed in the unfinished-course alert query. Stored as VARCHAR(40).
     */
    @Column(name = "days", length = 40)
    private String days;

    // -------------------------------------------------------------------------
    // Quantities (legacy double → BigDecimal, pre-approved)
    // -------------------------------------------------------------------------

    /** Prescribed quantity. NOT NULL, non-negative (ck_prescriptions_qty_nonneg). */
    @NotNull
    @Column(name = "qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal qty;

    /**
     * Issued quantity. Starts at 0; set to qty on dispense.
     * Non-negative (ck_prescriptions_qty_nonneg).
     */
    @Column(name = "issued", nullable = false, precision = 19, scale = 6)
    private BigDecimal issued = BigDecimal.ZERO;

    /**
     * Balance (qty - issued). Starts = qty; set to 0 on dispense.
     * Non-negative (ck_prescriptions_qty_nonneg).
     */
    @Column(name = "balance", nullable = false, precision = 19, scale = 6)
    private BigDecimal balance;

    // -------------------------------------------------------------------------
    // Status (hyphenated — requires converter)
    // -------------------------------------------------------------------------

    /**
     * Lifecycle status. EXACTLY two values: NOT-GIVEN (default) → GIVEN (terminal).
     * Mapped via {@link PrescriptionStatusConverter} (NOT @Enumerated) because the
     * DB values are hyphenated and not valid Java enum constant names.
     * (Prescription.java:50; V27 ck_prescriptions_status CHECK.)
     */
    @NotNull
    @Convert(converter = PrescriptionStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private PrescriptionStatus status = PrescriptionStatus.NOT_GIVEN;

    // -------------------------------------------------------------------------
    // Supplementary text fields
    // -------------------------------------------------------------------------

    /** Prescription reference note. */
    @Column(name = "reference", columnDefinition = "TEXT")
    private String reference;

    /** Patient instructions text. */
    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    /** Payment type denormalised at prescribe time (CASH / INSURANCE). */
    @Column(name = "payment_type", length = 20)
    private String paymentType;

    /** Insurance membership number (empty for CASH). */
    @Column(name = "membership_no", length = 100)
    private String membershipNo;

    // -------------------------------------------------------------------------
    // Mandatory loose cross-module ref — medicine (Prescription.java:72-75)
    // -------------------------------------------------------------------------

    /**
     * MANDATORY loose ref to the medicine in the masterdata module (no FK, ADR-0008).
     * Verified via {@code MedicineLookup} before persist.
     */
    @NotBlank
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    // -------------------------------------------------------------------------
    // Patient ref — V27 stores as patient_id BIGINT; V36 converts to patient_uid VARCHAR(26).
    // We map patient_uid (the loose uid column added by V36).
    // Note: V27 has patient_id FK, V36 drops it and adds patient_uid. The entity maps
    // patient_uid only (same pattern as LabTest after V33).
    // -------------------------------------------------------------------------

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2 Correction — added by V36).
     * Replaces the original V27 patient_id BIGINT FK dropped by V36.
     */
    @Column(name = "patient_uid", length = 26)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Mandatory loose billing ref
    // -------------------------------------------------------------------------

    /**
     * Loose ref to the PatientBill created at prescribe time (billing module, no FK).
     * NOT NULL — every prescription has exactly one bill.
     */
    @NotBlank
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    // -------------------------------------------------------------------------
    // Settlement flag (CR-INC05-01 — added by V36, mirrors LabTest.settled)
    // -------------------------------------------------------------------------

    /**
     * Clinical-local settlement projection (CR-INC05-01, V36).
     *
     * <p>Set at prescribe time:
     * <ul>
     *   <li>{@code true}  — INSURANCE/COVERED (no prepayment required)</li>
     *   <li>{@code false} — CASH-OPD / CASH-OUTSIDER (bill must be paid before pharmacy worklist)</li>
     * </ul>
     *
     * <p>Cash-PAID→settled=true propagation is DEFERRED (same pattern as LabTest/Consultation).
     */
    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    // -------------------------------------------------------------------------
    // Optional loose refs
    // -------------------------------------------------------------------------

    /** Optional loose ref to the ordering clinician user (nullable). */
    @Column(name = "clinician_user_uid", length = 26)
    private String clinicianUserUid;

    /** Optional loose ref to the insurance plan (nullable; null for CASH). */
    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    /**
     * Loose ref to the pharmacy that dispensed the medicine (set on dispense).
     * Nullable until issueMedicine is called (Prescription.java:97-100).
     */
    @Column(name = "issue_pharmacy_uid", length = 26)
    private String issuePharmacyUid;

    // -------------------------------------------------------------------------
    // Encounter binding (exactly one non-null — V27 CHECK num_nonnulls=1)
    // -------------------------------------------------------------------------

    /**
     * Intra-module real FK to the owning consultation (@ManyToOne, nullable).
     * NULL when bound to a non-consultation or admission.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", updatable = false)
    private Consultation consultation;

    /**
     * Intra-module real FK to the owning non-consultation (@ManyToOne, nullable).
     * NULL when bound to a consultation or admission.
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
    // Lifecycle audit triplets
    // ordered_* written; accepted/held/rejected/verified declared-but-unwritten (fidelity).
    // approved_* written at issueMedicine (Prescription.java:139-143).
    // -------------------------------------------------------------------------

    /** User who placed the order (audit). Written at save. */
    @Column(name = "ordered_by_user_uid", length = 26)
    private String orderedByUserUid;

    /** Business day uid when the order was placed (audit). Written at save. */
    @Column(name = "ordered_on_day_uid", length = 26)
    private String orderedOnDayUid;

    /** Timestamp when the order was placed (audit). Written at save. */
    @Column(name = "ordered_at")
    private Instant orderedAt;

    // DECLARED but NEVER written (Prescription.java boilerplate — kept for schema fidelity)

    /** Accepted-by user uid (declared-but-never-written per legacy). */
    @Column(name = "accepted_by_user_uid", length = 26)
    private String acceptedByUserUid;

    /** Accepted-on day uid (declared-but-never-written per legacy). */
    @Column(name = "accepted_on_day_uid", length = 26)
    private String acceptedOnDayUid;

    /** Accepted-at timestamp (declared-but-never-written per legacy). */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /** Held-by user uid (declared-but-never-written per legacy). */
    @Column(name = "held_by_user_uid", length = 26)
    private String heldByUserUid;

    /** Held-on day uid (declared-but-never-written per legacy). */
    @Column(name = "held_on_day_uid", length = 26)
    private String heldOnDayUid;

    /** Held-at timestamp (declared-but-never-written per legacy). */
    @Column(name = "held_at")
    private Instant heldAt;

    /** Rejected-by user uid (declared-but-never-written per legacy). */
    @Column(name = "rejected_by_user_uid", length = 26)
    private String rejectedByUserUid;

    /** Rejected-on day uid (declared-but-never-written per legacy). */
    @Column(name = "rejected_on_day_uid", length = 26)
    private String rejectedOnDayUid;

    /** Rejected-at timestamp (declared-but-never-written per legacy). */
    @Column(name = "rejected_at")
    private Instant rejectedAt;

    /** Reject comment (declared-but-never-written per legacy). */
    @Column(name = "reject_comment", columnDefinition = "TEXT")
    private String rejectComment;

    /** Verified-by user uid (declared-but-never-written per legacy). */
    @Column(name = "verified_by_user_uid", length = 26)
    private String verifiedByUserUid;

    /** Verified-on day uid (declared-but-never-written per legacy). */
    @Column(name = "verified_on_day_uid", length = 26)
    private String verifiedOnDayUid;

    /** Verified-at timestamp (declared-but-never-written per legacy). */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    // THE dispense audit — ONLY group populated (Prescription.java:139-143)

    /** User who approved (dispensed) the prescription (written at issueMedicine). */
    @Column(name = "approved_by_user_uid", length = 26)
    private String approvedByUserUid;

    /** Business day uid when the prescription was dispensed (written at issueMedicine). */
    @Column(name = "approved_on_day_uid", length = 26)
    private String approvedOnDayUid;

    /** Timestamp when the prescription was dispensed (written at issueMedicine). */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Created-by user uid (audit). */
    @Column(name = "created_by_user_uid", length = 26)
    private String createdByUserUid;

    /** Created-on day uid (audit). */
    @Column(name = "created_on_day_uid", length = 26)
    private String createdOnDayUid;

    /** Business day uid at time of record creation (loose ref, no FK). */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Factory methods (one per encounter type — same pattern as LabTest)
    // -------------------------------------------------------------------------

    /**
     * Create a new NOT-GIVEN prescription bound to a {@link Consultation} (outpatient path).
     *
     * <p>Stamps ordered_* audit triplet. balance = qty, issued = 0.
     *
     * <p>Legacy citations: Prescription.java:38-144; PatientServiceImpl.java (save_prescription
     * consultation path); PatientResource.java:3217 (prescription.status = NOT-GIVEN default).
     */
    public static Prescription forConsultation(
            Consultation consultation,
            String medicineUid,
            String patientBillUid,
            boolean settled,
            BigDecimal qty,
            String dosage,
            String frequency,
            String route,
            String days,
            String reference,
            String instructions,
            String paymentType,
            String membershipNo,
            String insurancePlanUid,
            String clinicianUserUid,
            String actorUserUid,
            String dayUid,
            Instant now) {
        Prescription p = new Prescription();
        p.consultation = consultation;
        p.patientUid = consultation.getPatientUid();
        p.medicineUid = medicineUid;
        p.patientBillUid = patientBillUid;
        p.settled = settled;
        p.qty = qty;
        p.issued = BigDecimal.ZERO;
        p.balance = qty;
        p.dosage = dosage;
        p.frequency = frequency;
        p.route = route;
        p.days = days;
        p.reference = reference;
        p.instructions = instructions;
        p.paymentType = paymentType;
        p.membershipNo = membershipNo != null ? membershipNo : "";
        p.insurancePlanUid = insurancePlanUid;
        p.clinicianUserUid = clinicianUserUid;
        p.status = PrescriptionStatus.NOT_GIVEN;
        p.businessDayUid = dayUid;
        p.createdByUserUid = actorUserUid;
        p.createdOnDayUid = dayUid;
        p.orderedByUserUid = actorUserUid;
        p.orderedOnDayUid = dayUid;
        p.orderedAt = now;
        return p;
    }

    /**
     * Create a new NOT-GIVEN prescription bound to a {@link NonConsultation} (OUTSIDER/walk-in path).
     *
     * <p>Stamps ordered_* audit triplet. balance = qty, issued = 0.
     *
     * <p>CR-INC05-05: the legacy existsByConsultationAndMedicine on an empty Optional for the
     * non-consultation path had an NPE bug. The corrected behaviour uses a dedicated
     * {@code existsByNonConsultationAndMedicineUid} check in the service layer.
     */
    public static Prescription forNonConsultation(
            NonConsultation nonConsultation,
            String patientUid,
            String medicineUid,
            String patientBillUid,
            boolean settled,
            BigDecimal qty,
            String dosage,
            String frequency,
            String route,
            String days,
            String reference,
            String instructions,
            String paymentType,
            String membershipNo,
            String insurancePlanUid,
            String clinicianUserUid,
            String actorUserUid,
            String dayUid,
            Instant now) {
        Prescription p = new Prescription();
        p.nonConsultation = nonConsultation;
        p.patientUid = patientUid;
        p.medicineUid = medicineUid;
        p.patientBillUid = patientBillUid;
        p.settled = settled;
        p.qty = qty;
        p.issued = BigDecimal.ZERO;
        p.balance = qty;
        p.dosage = dosage;
        p.frequency = frequency;
        p.route = route;
        p.days = days;
        p.reference = reference;
        p.instructions = instructions;
        p.paymentType = paymentType;
        p.membershipNo = membershipNo != null ? membershipNo : "";
        p.insurancePlanUid = insurancePlanUid;
        p.clinicianUserUid = clinicianUserUid;
        p.status = PrescriptionStatus.NOT_GIVEN;
        p.businessDayUid = dayUid;
        p.createdByUserUid = actorUserUid;
        p.createdOnDayUid = dayUid;
        p.orderedByUserUid = actorUserUid;
        p.orderedOnDayUid = dayUid;
        p.orderedAt = now;
        return p;
    }

    // -------------------------------------------------------------------------
    // Domain method — dispense (NOT-GIVEN → GIVEN)
    // -------------------------------------------------------------------------

    /**
     * Dispense the prescription: NOT-GIVEN → GIVEN.
     *
     * <p><strong>Exact legacy rule (PatientResource.java:3217-3245):</strong>
     * <ol>
     *   <li>Guard: status must be NOT-GIVEN ("not a pending prescription").</li>
     *   <li>Guard: {@code issuedQty} must be > 0 ("Invalid issue value").</li>
     *   <li>Guard: {@code issuedQty} must be <= balance ("Invalid issue value") — because the
     *       only valid value is exactly {@code qty}, this also covers issued > qty.</li>
     *   <li>Guard: issued must equal full prescribed qty — all-or-nothing.
     *       ("You can only issue the prescribed qty").</li>
     *   <li>STOCK check: DEFERRED — the "pharmacy has enough stock" validation belongs to the
     *       PHARMACY/INVENTORY context (not yet built). Documented here as a TODO.</li>
     *   <li>On success: issued = qty, balance = 0, issuePharmacyUid set,
     *       status = GIVEN, approved_* audit stamped.</li>
     * </ol>
     *
     * @param issuedQty        the quantity being issued (must equal qty for full dispense)
     * @param issuePharmacyUid loose uid of the dispensing pharmacy (set on this record)
     * @param actorUserUid     user performing the dispense (written to approved_*)
     * @param dayUid           current business day uid (written to approved_*)
     * @param now              current instant (written to approved_at)
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException on guard failures
     */
    public void issue(BigDecimal issuedQty, String issuePharmacyUid,
                      String actorUserUid, String dayUid, Instant now) {
        if (this.status != PrescriptionStatus.NOT_GIVEN) {
            throw new InvalidPatientOperationException(
                    "not a pending prescription");
        }
        if (issuedQty == null || issuedQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPatientOperationException(
                    "Invalid issue value");
        }
        if (issuedQty.compareTo(this.balance) > 0) {
            throw new InvalidPatientOperationException(
                    "Invalid issue value");
        }
        // All-or-nothing: issued must equal the full prescribed qty (PatientResource.java:3230)
        if (issuedQty.compareTo(this.qty) != 0) {
            throw new InvalidPatientOperationException(
                    "You can only issue the prescribed qty");
        }

        // TODO: STOCK check DEFERRED — verify pharmacy has sufficient stock and decrement
        // inventory. Belongs to the PHARMACY/INVENTORY module (not yet implemented).
        // When pharmacy module is built, add: pharmacyStockService.decrementStock(medicineUid,
        //   issuePharmacyUid, issuedQty); before the state mutations below.

        this.issued = this.qty;
        this.balance = BigDecimal.ZERO;
        this.issuePharmacyUid = issuePharmacyUid;
        this.status = PrescriptionStatus.GIVEN;
        // Dispense audit (Prescription.java:139-143)
        this.approvedByUserUid = actorUserUid;
        this.approvedOnDayUid = dayUid;
        this.approvedAt = now;
    }

    /**
     * Mark as settled (clinical-local settlement flag).
     *
     * <p>Called by the billing→clinical settlement event seam when the CASH bill is PAID.
     * For INSURANCE/COVERED prescriptions this is set at prescribe time.
     * For CASH-OPD: DEFERRED (same pattern as LabTest.markSettled()).
     */
    public void markSettled() {
        this.settled = true;
    }
}
