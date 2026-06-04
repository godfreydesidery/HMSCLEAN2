package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Clinical procedure order aggregate (Procedure.java:40-147, V26/V35).
 *
 * <p>A SEPARATE entity — NOT polymorphic. Each order corresponds to one procedureType
 * and belongs to exactly one encounter (consultation, non-consultation, or admission).
 *
 * <p><strong>Result column (Procedure.java:44-45):</strong>
 * {@code note} TEXT(10000) is the procedure result narrative. The "result" IS the note.
 * Unlike LabTest (which has separate result/level/range/unit) or Radiology (result + report),
 * Procedure has only a single {@code note} column for its outcome.
 *
 * <p><strong>Status (Procedure.java:54, V26 CHECK):</strong>
 * {@link ProcedureStatus} — PENDING / ACCEPTED / REJECTED / VERIFIED.
 * NO APPROVED, NO COLLECTED. The planning-doc M14 "approve" step is FABRICATED.
 *
 * <p><strong>The distinctive add_note bill gate (PatientResource.java:3408-3414):</strong>
 * The add_note transition (ACCEPTED → VERIFIED) is the ONLY transition in the entire order
 * family (LabTest, Radiology, Procedure) that explicitly re-checks the local settled flag
 * before proceeding. Unlike LabTest.verify() or Radiology.verify() which do NOT re-check
 * settlement, Procedure.addNote() REQUIRES {@code settled == true} or throws
 * {@link PayBeforeServiceException}. This is reproduced verbatim per exact-process mandate.
 * (PatientResource.java:3408-3414 — the legacy in-method bill-status gate.)
 *
 * <p><strong>Temporal fields (Procedure.java:47-51):</strong>
 * {@code proc_time TIME} (legacy {@code @Column time_}) and {@code proc_date DATE} (legacy
 * {@code @Column date_}) — mapped to Java {@code LocalTime} and {@code LocalDate}.
 * {@code hours} and {@code minutes} are NUMERIC(19,6) (legacy {@code double} — migrated to
 * BigDecimal per the pre-approved double→BigDecimal guardrail).
 *
 * <p><strong>Vestigial held_* columns (Procedure.java:128-132):</strong>
 * The {@code held_*} audit triplet is declared on the entity for data fidelity. There is
 * NO hold endpoint and NO hold domain method. The columns are kept but never written
 * via any lifecycle transition in this system.
 *
 * <p><strong>Status: REJECTED is unreachable at runtime</strong>:
 * There is no reject_procedure endpoint in the legacy system. The entity accepts
 * PENDING|REJECTED → ACCEPTED for guard completeness, but REJECTED is never set.
 *
 * <p><strong>Encounter binding (V26 CHECK num_nonnulls=1):</strong>
 * Exactly one of {@code consultation} / {@code nonConsultation} / {@code admissionUid}
 * must be non-null. The admission path is DEFERRED.
 *
 * <p><strong>Cross-module refs (ADR-0022 D2 Correction, V35):</strong>
 * {@code patient_uid} is a loose VARCHAR(26) — replaces the V26 {@code patient_id BIGINT FK}
 * dropped by V35. {@code procedureTypeUid} is a loose ref to masterdata.
 * {@code patientBillUid} is a loose ref to billing. All other *Uid fields are loose refs.
 *
 * <p><strong>Settlement flag (CR-INC05-01, V35):</strong>
 * {@code settled} is the clinical-local settlement projection. Set at order time from the
 * billing ChargeResult. Re-checked at add_note time (the distinctive gate).
 *
 * <p><strong>DEFERRED — admission path:</strong>
 * The {@code admissionUid} column exists and is mapped as a loose nullable String. No
 * admission-scoped endpoints are implemented in C9. Full admission procedure support is
 * deferred to the Inpatient/Nursing increment.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape:             Procedure.java:40-147</li>
 *   <li>Note column (10000):      Procedure.java:44-45</li>
 *   <li>proc_time (time_):        Procedure.java:47-48</li>
 *   <li>proc_date (date_):        Procedure.java:50-51</li>
 *   <li>hours/minutes (double):   Procedure.java:52-53</li>
 *   <li>Status:                   Procedure.java:54</li>
 *   <li>Theatre loose ref:        Procedure.java:59-62</li>
 *   <li>ProcedureType mandatory:  Procedure.java:85-88</li>
 *   <li>Vestigial held_*:         Procedure.java:128-132</li>
 *   <li>add_note bill gate:       PatientResource.java:3408-3414</li>
 *   <li>update_procedure:         PatientResource.java:4060-4061</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "procedures")
public class Procedure extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Result / narrative column
    // -------------------------------------------------------------------------

    /**
     * Procedure result narrative — the "note" IS the result (TEXT, legacy length 10000).
     * (Procedure.java:44-45.) Set at add_note time when status transitions ACCEPTED → VERIFIED.
     * Also written (without status change) by the update_procedure operation.
     */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /**
     * Procedure type label (VARCHAR(60), optional — denormalised from procedureType at order time).
     * (Procedure.java — {@code type} field.)
     */
    @Column(name = "type", length = 60)
    private String type;

    /**
     * The time the procedure was performed (Procedure.java:47-48, legacy {@code @Column time_}).
     * Maps to {@code proc_time TIME} in the DB.
     */
    @Column(name = "proc_time")
    private LocalTime procTime;

    /**
     * Free-text clinical diagnosis associated with this procedure (TEXT, optional).
     */
    @Column(name = "diagnosis", columnDefinition = "TEXT")
    private String diagnosis;

    /**
     * The date the procedure was performed (Procedure.java:50-51, legacy {@code @Column date_}).
     * Maps to {@code proc_date DATE} in the DB.
     */
    @Column(name = "proc_date")
    private LocalDate procDate;

    /**
     * Duration hours for the procedure (Procedure.java:52, legacy {@code double}).
     * Migrated to NUMERIC(19,6) / BigDecimal per the pre-approved double→BigDecimal guardrail.
     */
    @Column(name = "hours", precision = 19, scale = 6)
    private BigDecimal hours;

    /**
     * Duration minutes for the procedure (Procedure.java:53, legacy {@code double}).
     * Migrated to NUMERIC(19,6) / BigDecimal per the pre-approved double→BigDecimal guardrail.
     */
    @Column(name = "minutes", precision = 19, scale = 6)
    private BigDecimal minutes;

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /**
     * Lifecycle status. (Procedure.java:54; V26 CHECK constraint.)
     * PENDING / ACCEPTED / REJECTED / VERIFIED — NO APPROVED, NO COLLECTED.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ProcedureStatus status = ProcedureStatus.PENDING;

    // -------------------------------------------------------------------------
    // Payment context
    // -------------------------------------------------------------------------

    /** Payment type (CASH / INSURANCE). Denormalised from context at order time. */
    @Column(name = "payment_type", length = 20)
    private String paymentType;

    /** Insurance membership number (empty for CASH patients). */
    @Column(name = "membership_no", length = 100)
    private String membershipNo;

    // -------------------------------------------------------------------------
    // Mandatory loose refs
    // -------------------------------------------------------------------------

    /**
     * MANDATORY loose ref to the procedure type in the masterdata module (no FK, ADR-0008).
     * (Procedure.java:85-88.) Verified via ProcedureTypeLookup before persist.
     */
    @NotBlank
    @Column(name = "procedure_type_uid", length = 26, nullable = false, updatable = false)
    private String procedureTypeUid;

    /**
     * Loose ref to the PatientBill created at order time (billing module, no FK).
     * NOT NULL — every procedure order has exactly one bill.
     */
    @NotBlank
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2 Correction, V35).
     * Replaces the original V26 patient_id BIGINT FK dropped by V35.
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Optional loose refs
    // -------------------------------------------------------------------------

    /**
     * Optional loose ref to the theatre (Procedure.java:59-62, nullable).
     * Theatre uid is NOT VALIDATED — theatre is optional and nullable.
     * No TheatreLookup validation at order time; the uid is stored as-is.
     */
    @Column(name = "theatre_uid", length = 26)
    private String theatreUid;

    /** Optional loose ref to a diagnosis type associated with this order (nullable). */
    @Column(name = "diagnosis_type_uid", length = 26)
    private String diagnosisTypeUid;

    /** Optional loose ref to the ordering clinician user (nullable). */
    @Column(name = "clinician_user_uid", length = 26)
    private String clinicianUserUid;

    /** Optional loose ref to the insurance plan (nullable; null for CASH). */
    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    // -------------------------------------------------------------------------
    // Encounter binding (exactly one non-null — V26 CHECK num_nonnulls=1)
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
     * The admissions module is DEFERRED. NULL when bound to consultation or non-consultation.
     */
    @Column(name = "admission_uid", length = 26, updatable = false)
    private String admissionUid;

    // -------------------------------------------------------------------------
    // Settlement flag (CR-INC05-01, V35)
    // -------------------------------------------------------------------------

    /**
     * Clinical-local settlement projection (CR-INC05-01, ADR-0022 D2/D4, V35).
     *
     * <p>Set at order time:
     * <ul>
     *   <li>{@code true}  — INSURANCE/COVERED or inpatient (auto-pass; no prepayment required)</li>
     *   <li>{@code false} — CASH-OPD / CASH-OUTSIDER (must be settled before add_note)</li>
     * </ul>
     *
     * <p><strong>The add_note gate:</strong>
     * Unlike LabTest and Radiology (which check settled only at worklist time), the Procedure
     * add_note transition explicitly re-checks this flag (PatientResource.java:3408-3414).
     * If {@code settled == false} at add_note time → throws PayBeforeServiceException / 422.
     *
     * <p><strong>DEFERRED NOTE — cash-PAID propagation seam:</strong>
     * When a CASH patient pays their procedure bill, a billing→clinical event must flip this flag
     * to true. Until that seam is built, CASH procedure orders are ordered correctly but the
     * add_note transition will be blocked by the settlement gate.
     * This is the same deferral pattern as LabTest.settled (V33 / C7).
     */
    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    // -------------------------------------------------------------------------
    // Lifecycle audit triplets
    // All are loose uid refs (VARCHAR(26)) + a timestamp — no FK.
    // NO collected_* (no COLLECTED state).
    // held_* is vestigial (Procedure.java:128-132) — kept for data fidelity, never written.
    // -------------------------------------------------------------------------

    /** User who placed the order (audit). */
    @Column(name = "ordered_by_user_uid", length = 26)
    private String orderedByUserUid;

    /** Business day uid when the order was placed (audit). */
    @Column(name = "ordered_on_day_uid", length = 26)
    private String orderedOnDayUid;

    /** Timestamp when the order was placed (audit). */
    @Column(name = "ordered_at")
    private Instant orderedAt;

    /** User who accepted the order (audit). */
    @Column(name = "accepted_by_user_uid", length = 26)
    private String acceptedByUserUid;

    /** Business day uid when the order was accepted (audit). */
    @Column(name = "accepted_on_day_uid", length = 26)
    private String acceptedOnDayUid;

    /** Timestamp when the order was accepted (audit). */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /**
     * User who "held" — VESTIGIAL audit field (Procedure.java:128-132).
     * Present for data fidelity. No live transition sets this in the new system (no hold endpoint).
     */
    @Column(name = "held_by_user_uid", length = 26)
    private String heldByUserUid;

    /** VESTIGIAL audit field (data fidelity). */
    @Column(name = "held_on_day_uid", length = 26)
    private String heldOnDayUid;

    /** VESTIGIAL audit field (data fidelity). */
    @Column(name = "held_at")
    private Instant heldAt;

    /** User who verified/completed the procedure (audit). */
    @Column(name = "verified_by_user_uid", length = 26)
    private String verifiedByUserUid;

    /** Business day uid when the procedure was verified (audit). */
    @Column(name = "verified_on_day_uid", length = 26)
    private String verifiedOnDayUid;

    /** Timestamp when the procedure was verified (audit). */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * User who rejected the order (audit).
     * UNREACHABLE at runtime — no reject_procedure endpoint exists.
     * Retained for entity/constraint fidelity.
     */
    @Column(name = "rejected_by_user_uid", length = 26)
    private String rejectedByUserUid;

    /** UNREACHABLE audit field (retained for fidelity). */
    @Column(name = "rejected_on_day_uid", length = 26)
    private String rejectedOnDayUid;

    /** UNREACHABLE audit field (retained for fidelity). */
    @Column(name = "rejected_at")
    private Instant rejectedAt;

    /** Reason for rejection (retained for fidelity; unreachable at runtime). */
    @Column(name = "reject_comment", columnDefinition = "TEXT")
    private String rejectComment;

    /** User who created the order record (audit). */
    @Column(name = "created_by_user_uid", length = 26)
    private String createdByUserUid;

    /** Business day uid when the record was created (audit). */
    @Column(name = "created_on_day_uid", length = 26)
    private String createdOnDayUid;

    /** Business day uid at time of record creation (loose ref, no FK). */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Factory methods (one per encounter type)
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING procedure order bound to a {@link Consultation} (outpatient path).
     *
     * <p>Guard: consultation status must be OUTPATIENT-compatible (caller verifies).
     * Stamps the ordered_* audit triplet.
     */
    public static Procedure forConsultation(Consultation consultation,
                                             String procedureTypeUid,
                                             String patientBillUid,
                                             boolean settled,
                                             String paymentType,
                                             String membershipNo,
                                             String insurancePlanUid,
                                             String diagnosisTypeUid,
                                             String clinicianUserUid,
                                             String theatreUid,
                                             String actorUserUid,
                                             String dayUid,
                                             Instant now) {
        Procedure p = new Procedure();
        p.consultation = consultation;
        p.patientUid = consultation.getPatientUid();
        p.procedureTypeUid = procedureTypeUid;
        p.patientBillUid = patientBillUid;
        p.settled = settled;
        p.paymentType = paymentType;
        p.membershipNo = membershipNo != null ? membershipNo : "";
        p.insurancePlanUid = insurancePlanUid;
        p.diagnosisTypeUid = diagnosisTypeUid;
        p.clinicianUserUid = clinicianUserUid;
        p.theatreUid = theatreUid;
        p.status = ProcedureStatus.PENDING;
        p.businessDayUid = dayUid;
        p.createdByUserUid = actorUserUid;
        p.createdOnDayUid = dayUid;
        p.orderedByUserUid = actorUserUid;
        p.orderedOnDayUid = dayUid;
        p.orderedAt = now;
        return p;
    }

    /**
     * Create a new PENDING procedure order bound to a {@link NonConsultation} (OUTSIDER/walk-in path).
     *
     * <p>Guard: non-consultation status must be IN_PROCESS (caller verifies via WalkInService).
     * Stamps the ordered_* audit triplet.
     */
    public static Procedure forNonConsultation(NonConsultation nonConsultation,
                                                String patientUid,
                                                String procedureTypeUid,
                                                String patientBillUid,
                                                boolean settled,
                                                String paymentType,
                                                String membershipNo,
                                                String insurancePlanUid,
                                                String diagnosisTypeUid,
                                                String clinicianUserUid,
                                                String theatreUid,
                                                String actorUserUid,
                                                String dayUid,
                                                Instant now) {
        Procedure p = new Procedure();
        p.nonConsultation = nonConsultation;
        p.patientUid = patientUid;
        p.procedureTypeUid = procedureTypeUid;
        p.patientBillUid = patientBillUid;
        p.settled = settled;
        p.paymentType = paymentType;
        p.membershipNo = membershipNo != null ? membershipNo : "";
        p.insurancePlanUid = insurancePlanUid;
        p.diagnosisTypeUid = diagnosisTypeUid;
        p.clinicianUserUid = clinicianUserUid;
        p.theatreUid = theatreUid;
        p.status = ProcedureStatus.PENDING;
        p.businessDayUid = dayUid;
        p.createdByUserUid = actorUserUid;
        p.createdOnDayUid = dayUid;
        p.orderedByUserUid = actorUserUid;
        p.orderedOnDayUid = dayUid;
        p.orderedAt = now;
        return p;
    }

    // -------------------------------------------------------------------------
    // Lifecycle domain methods (state machine)
    // -------------------------------------------------------------------------

    /**
     * Accept the order: PENDING | REJECTED → ACCEPTED.
     *
     * <p>Although REJECTED is unreachable at runtime (no reject endpoint), the guard
     * {@code PENDING|REJECTED → ACCEPTED} is reproduced verbatim from the legacy accept path.
     * Stamps accepted_* audit triplet. Does NOT clear rejectComment (parity: unreachable).
     *
     * <p>NO bill re-check at accept (CR-INC05-01 parity — only checked at add_note time).
     *
     * <p>Guard: caller must verify status IN (PENDING, REJECTED) before calling.
     *
     * @param actorUserUid user performing the accept
     * @param dayUid       current business day uid
     * @param now          current instant
     */
    public void accept(String actorUserUid, String dayUid, Instant now) {
        this.status = ProcedureStatus.ACCEPTED;
        this.acceptedByUserUid = actorUserUid;
        this.acceptedOnDayUid = dayUid;
        this.acceptedAt = now;
    }

    /**
     * Add procedure note and transition to VERIFIED: ACCEPTED → VERIFIED.
     *
     * <p><strong>THE DISTINCTIVE ADD_NOTE BILL GATE (PatientResource.java:3408-3414):</strong>
     * This is the ONLY transition in the entire Procedure/LabTest/Radiology family that
     * explicitly re-checks the local settled flag before completing. The guard is:
     * <ol>
     *   <li>Status must be ACCEPTED (else 422 "Please accept the procedure first").</li>
     *   <li>Note must be non-empty (caller must validate before calling).</li>
     *   <li>settled must be true — if false, throws {@link PayBeforeServiceException}
     *       with the verbatim message "Could not add procedure note. Payment not verified"
     *       (PatientResource.java:3410-3412).</li>
     * </ol>
     *
     * <p>On success: sets note, status VERIFIED, stamps verified_* audit triplet.
     *
     * <p>Guard: caller must validate status == ACCEPTED and note non-empty BEFORE calling.
     * This method enforces the settlement gate internally and throws on failure.
     *
     * @param note         the procedure result narrative (must be non-blank — caller validates)
     * @param actorUserUid user adding the note
     * @param dayUid       current business day uid
     * @param now          current instant
     * @throws PayBeforeServiceException if settled == false (payment not verified)
     */
    public void addNote(String note, String actorUserUid, String dayUid, Instant now) {
        if (!this.settled) {
            // Legacy verbatim message (PatientResource.java:3410-3412).
            throw new InvalidPatientOperationException(
                    "Could not add procedure note. Payment not verified");
        }
        this.note = note;
        this.status = ProcedureStatus.VERIFIED;
        this.verifiedByUserUid = actorUserUid;
        this.verifiedOnDayUid = dayUid;
        this.verifiedAt = now;
    }

    /**
     * Update procedure fields without status change.
     *
     * <p>Allowed when status == ACCEPTED (PatientResource.java:4060-4061).
     * Updates the note and temporal fields. Does NOT change status.
     * Guard: caller must verify status == ACCEPTED before calling.
     *
     * @param note      updated note text (may be null to leave unchanged)
     * @param procDate  updated procedure date (may be null)
     * @param procTime  updated procedure time (may be null)
     * @param hours     updated hours (may be null)
     * @param minutes   updated minutes (may be null)
     * @param diagnosis updated diagnosis text (may be null)
     */
    public void update(String note, LocalDate procDate, LocalTime procTime,
                       BigDecimal hours, BigDecimal minutes, String diagnosis) {
        if (note != null) {
            this.note = note;
        }
        if (procDate != null) {
            this.procDate = procDate;
        }
        if (procTime != null) {
            this.procTime = procTime;
        }
        if (hours != null) {
            this.hours = hours;
        }
        if (minutes != null) {
            this.minutes = minutes;
        }
        if (diagnosis != null) {
            this.diagnosis = diagnosis;
        }
    }

    /**
     * Mark as settled (clinical-local settlement flag).
     *
     * <p>Called by the billing→clinical settlement event seam when the CASH bill is PAID.
     * Until the event seam is built, this is called at order time for INSURANCE/COVERED orders.
     * For CASH-OPD: DEFERRED (same pattern as LabTest.markSettled()).
     */
    public void markSettled() {
        this.settled = true;
    }
}
