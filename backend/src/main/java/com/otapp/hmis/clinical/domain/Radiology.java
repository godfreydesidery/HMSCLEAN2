package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Radiology imaging order aggregate (Radiology.java:42-150, V25/V34).
 *
 * <p>A SEPARATE entity — NOT polymorphic. Each order corresponds to one radiologyType
 * and belongs to exactly one encounter (consultation, non-consultation, or admission).
 *
 * <p><strong>Result columns (Radiology.java:50-52):</strong>
 * {@code result}, {@code report} (TEXT, legacy length 10000), {@code description}.
 * NO range/level/unit — those are lab-specific. Radiology has result + report + the
 * inline attachment blob.
 *
 * <p><strong>Inline attachment blob (Radiology.java:50):</strong>
 * The {@code attachment} column is a BYTEA blob on the radiology row itself (set at verify time).
 * Mapped as plain {@code byte[]} — NO {@code @Lob}: @Lob byte[] maps to OID in Hibernate 6,
 * which fails ddl-auto=validate against BYTEA. This is separate from the
 * {@link RadiologyAttachment} child table (named file attachments).
 *
 * <p><strong>Status (Radiology.java:55):</strong>
 * {@link RadiologyStatus} enum — PENDING / ACCEPTED / REJECTED / COLLECTED / VERIFIED.
 * COLLECTED is a DEAD state (CR-INC05-14 — collect_radiology111 is a dead endpoint).
 * Active path: PENDING → ACCEPTED → VERIFIED.
 *
 * <p><strong>NO collect step (CR-INC05-14):</strong>
 * The verify transition goes ACCEPTED → VERIFIED DIRECTLY (PatientResource.java:4280-4281).
 * There is NO collect() domain method and NO collect endpoint.
 *
 * <p><strong>reject asymmetry (legacy verified):</strong>
 * When transitioning back out of REJECTED (e.g. accept from REJECTED), the {@code reject_comment}
 * is NOT cleared. This is an asymmetry vs LabTest which clears it. Reproduced verbatim.
 * (Verification finding — no legacy citation explicitly documents this; it is the absence
 * of the clear-on-accept call in the radiology accept path.)
 *
 * <p><strong>Attachment gate is ACCEPTED (not COLLECTED):</strong>
 * Radiology attachments may be added only when radiology.status == ACCEPTED
 * (PatientServiceImpl.java:2931-2933). Max 5. Download gated on VERIFIED
 * (PatientResource.java:6154).
 *
 * <p><strong>Encounter binding (V25 CHECK num_nonnulls=1):</strong>
 * Exactly one of {@code consultation} / {@code nonConsultation} / {@code admissionUid}
 * must be non-null.
 *
 * <p><strong>Cross-module refs (ADR-0022 D2 Correction, V34):</strong>
 * {@code patient_uid} is a loose VARCHAR(26) — replaces the V25 {@code patient_id BIGINT FK}
 * dropped by V34. {@code radiologyTypeUid} is a loose ref to masterdata. {@code patientBillUid}
 * is a loose ref to billing. All other *Uid fields are loose refs (no FK).
 *
 * <p><strong>Settlement flag (CR-INC05-01, V34):</strong>
 * {@code settled} is the clinical-local settlement projection. Set at order time from the
 * billing ChargeResult: true for COVERED/VERIFIED/inpatient, false for CASH-OPD (UNPAID).
 * The cash-PAID→settled=true propagation is deferred (same pattern as LabTest / Consultation).
 * The radiology worklist filters by settled=true.
 *
 * <p><strong>Attachments:</strong>
 * {@link RadiologyAttachment} @OneToMany with orphanRemoval=true.
 * Application rules (NOT DB): max 5 per order; attach only when status=ACCEPTED.
 * Download gated on status=VERIFIED. Delete-attachment blocked when VERIFIED.
 * (PatientServiceImpl.java:2931-2933; PatientResource.java:6154.)
 *
 * <p><strong>DEFERRED — admission path:</strong>
 * The {@code admissionUid} column exists and is mapped as a loose nullable String. No
 * admission-scoped endpoints are implemented in C8. Full admission radiology support is
 * deferred to the Inpatient/Nursing increment.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: Radiology.java:42-150</li>
 *   <li>Inline attachment blob: Radiology.java:50 (Byte[] image)</li>
 *   <li>Status: Radiology.java:55</li>
 *   <li>Encounter binding: Radiology.java:65-78</li>
 *   <li>patientBill: Radiology.java:85-88</li>
 *   <li>Attachments orphanRemoval: RadiologyAttachment.java:28-50</li>
 *   <li>Lifecycle transitions: PatientResource.java:4280-4292</li>
 *   <li>verify from ACCEPTED: PatientResource.java:4280-4281</li>
 *   <li>Max 5 attachments: PatientServiceImpl.java:2928-2930</li>
 *   <li>Attach only when ACCEPTED: PatientServiceImpl.java:2931-2933</li>
 *   <li>Download gated VERIFIED: PatientResource.java:6154</li>
 *   <li>Dead collect endpoint: PatientResource.java:4317, CR-INC05-14</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "radiologies")
public class Radiology extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Result columns
    // -------------------------------------------------------------------------

    /** Free-text result (written at verify time). */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    /**
     * Full report / interpretation text (TEXT, legacy length 10000).
     * A separate field from {@code result} — updated at verify or via saveResult.
     */
    @Column(name = "report", columnDefinition = "TEXT")
    private String report;

    /**
     * Retained prior report narrative — set when a VERIFIED report is amended (inc-06A C6 / ITEM4).
     * Append-only audit of the pre-amendment text.
     */
    @Column(name = "prior_report", columnDefinition = "TEXT")
    private String priorReport;

    /** Amend audit triplet (inc-06A C6): who/when last amended a VERIFIED report. */
    @Column(name = "report_amended_by_user_uid", length = 26)
    private String reportAmendedByUserUid;

    @Column(name = "report_amended_on_day_uid", length = 26)
    private String reportAmendedOnDayUid;

    @Column(name = "report_amended_at")
    private Instant reportAmendedAt;

    /** Optional description / clinical notes. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Inline attachment blob — the legacy {@code Byte[] image} set at verify time
     * (Radiology.java:50).
     *
     * <p><strong>NO {@code @Lob}</strong>: {@code @Lob byte[]} maps to PostgreSQL OID in
     * Hibernate 6, which causes ddl-auto=validate to fail against the BYTEA column created
     * by V25. Plain {@code byte[]} maps correctly to BYTEA (the memory lesson).
     *
     * <p>This is SEPARATE from the {@link RadiologyAttachment} child table (named file
     * attachments). So Radiology has BOTH: this inline blob AND the @OneToMany attachments.
     */
    @Column(name = "attachment")
    private byte[] attachment;

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /**
     * Lifecycle status. COLLECTED is a DEAD state (CR-INC05-14) retained for data fidelity.
     * Active path: PENDING → ACCEPTED → VERIFIED (PatientResource.java:4280-4281).
     * Plain @Enumerated(STRING) — no converter needed.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RadiologyStatus status = RadiologyStatus.PENDING;

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
     * MANDATORY loose ref to the radiology type in the masterdata module (no FK, ADR-0008).
     * Verified via RadiologyTypeLookup before persist.
     */
    @NotBlank
    @Column(name = "radiology_type_uid", length = 26, nullable = false, updatable = false)
    private String radiologyTypeUid;

    /**
     * Loose ref to the PatientBill created at order time (billing module, no FK).
     * NOT NULL — every radiology order has exactly one bill.
     */
    @NotBlank
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2 Correction, V34).
     * Replaces the original V25 patient_id BIGINT FK dropped by V34.
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Optional loose refs
    // -------------------------------------------------------------------------

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
    // Encounter binding (exactly one non-null — V25 CHECK num_nonnulls=1)
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
    // Settlement flag (CR-INC05-01, V34)
    // -------------------------------------------------------------------------

    /**
     * Clinical-local settlement projection (CR-INC05-01, ADR-0022 D2/D4, V34).
     *
     * <p>Set at order time:
     * <ul>
     *   <li>{@code true}  — INSURANCE/COVERED or inpatient (auto-pass; no prepayment required)</li>
     *   <li>{@code false} — CASH-OPD / CASH-OUTSIDER (bill must be paid before worklist shows)</li>
     * </ul>
     *
     * <p><strong>DEFERRED NOTE — cash-PAID propagation seam:</strong>
     * When a CASH patient pays their radiology bill, a billing→clinical event must flip this flag
     * to true. Until that seam is built, CASH radiology orders are ordered correctly but do NOT
     * appear on the worklist until the seam lands. INSURANCE/COVERED orders appear immediately.
     * This is the same deferral pattern as {@code LabTest.settled} (V33 / C7).
     */
    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    // -------------------------------------------------------------------------
    // Lifecycle audit triplets (ordered_* / accepted_* / held_* / collected_* / verified_* / rejected_*)
    // All are loose uid refs (VARCHAR(26)) + a timestamp — no FK.
    // The collected_* triplet exists in V25 for data fidelity (DEAD state).
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

    /** User who held (reverted to PENDING) the order (audit). */
    @Column(name = "held_by_user_uid", length = 26)
    private String heldByUserUid;

    /** Business day uid when the order was held (audit). */
    @Column(name = "held_on_day_uid", length = 26)
    private String heldOnDayUid;

    /** Timestamp when the order was held (audit). */
    @Column(name = "held_at")
    private Instant heldAt;

    /**
     * User who "collected" — DEAD audit field. Present for V25 data fidelity.
     * No live transition sets this in the new system (CR-INC05-14).
     */
    @Column(name = "collected_by_user_uid", length = 26)
    private String collectedByUserUid;

    /** DEAD audit field (data fidelity). */
    @Column(name = "collected_on_day_uid", length = 26)
    private String collectedOnDayUid;

    /** DEAD audit field (data fidelity). */
    @Column(name = "collected_at")
    private Instant collectedAt;

    /** User who verified the result (audit). */
    @Column(name = "verified_by_user_uid", length = 26)
    private String verifiedByUserUid;

    /** Business day uid when the result was verified (audit). */
    @Column(name = "verified_on_day_uid", length = 26)
    private String verifiedOnDayUid;

    /** Timestamp when the result was verified (audit). */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** User who rejected the order (audit). */
    @Column(name = "rejected_by_user_uid", length = 26)
    private String rejectedByUserUid;

    /** Business day uid when the order was rejected (audit). */
    @Column(name = "rejected_on_day_uid", length = 26)
    private String rejectedOnDayUid;

    /** Timestamp when the order was rejected (audit). */
    @Column(name = "rejected_at")
    private Instant rejectedAt;

    /**
     * Reason for rejection.
     *
     * <p><strong>Radiology asymmetry vs LabTest (verified finding):</strong>
     * This field is NOT cleared when the order is accepted back from REJECTED. This is a
     * deliberate legacy asymmetry — the LabTest.accept() clears rejectComment but the
     * radiology accept path does NOT. Reproduced verbatim per exact-process mandate.
     * (Verification finding — absence of clear call in legacy radiology accept path.)
     */
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
    // Attachments (named file attachments — child table)
    // -------------------------------------------------------------------------

    /**
     * Radiology result file attachments (RadiologyAttachment.java:28-50).
     *
     * <p>orphanRemoval=true — removing from this list hard-deletes the attachment.
     * Application rules (NOT DB): max 5 per order; attach only when status=ACCEPTED
     * (PatientServiceImpl.java:2931-2933 — note ACCEPTED gate, different from lab COLLECTED).
     */
    @OneToMany(mappedBy = "radiology", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<RadiologyAttachment> radiologyAttachments = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Factory methods (one per encounter type)
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING radiology order bound to a {@link Consultation} (outpatient path).
     *
     * <p>Guard: consultation status must be OUTPATIENT-compatible (caller verifies).
     * Stamps the ordered_* audit triplet.
     */
    public static Radiology forConsultation(Consultation consultation,
                                             String radiologyTypeUid,
                                             String patientBillUid,
                                             boolean settled,
                                             String paymentType,
                                             String membershipNo,
                                             String insurancePlanUid,
                                             String diagnosisTypeUid,
                                             String clinicianUserUid,
                                             String actorUserUid,
                                             String dayUid,
                                             Instant now) {
        Radiology r = new Radiology();
        r.consultation = consultation;
        r.patientUid = consultation.getPatientUid();
        r.radiologyTypeUid = radiologyTypeUid;
        r.patientBillUid = patientBillUid;
        r.settled = settled;
        r.paymentType = paymentType;
        r.membershipNo = membershipNo != null ? membershipNo : "";
        r.insurancePlanUid = insurancePlanUid;
        r.diagnosisTypeUid = diagnosisTypeUid;
        r.clinicianUserUid = clinicianUserUid;
        r.status = RadiologyStatus.PENDING;
        r.businessDayUid = dayUid;
        r.createdByUserUid = actorUserUid;
        r.createdOnDayUid = dayUid;
        r.orderedByUserUid = actorUserUid;
        r.orderedOnDayUid = dayUid;
        r.orderedAt = now;
        return r;
    }

    /**
     * Create a new PENDING radiology order bound to a {@link NonConsultation} (OUTSIDER/walk-in path).
     *
     * <p>Guard: non-consultation status must be IN_PROCESS (caller verifies via WalkInService).
     * Stamps the ordered_* audit triplet.
     */
    public static Radiology forNonConsultation(NonConsultation nonConsultation,
                                                String patientUid,
                                                String radiologyTypeUid,
                                                String patientBillUid,
                                                boolean settled,
                                                String paymentType,
                                                String membershipNo,
                                                String insurancePlanUid,
                                                String diagnosisTypeUid,
                                                String clinicianUserUid,
                                                String actorUserUid,
                                                String dayUid,
                                                Instant now) {
        Radiology r = new Radiology();
        r.nonConsultation = nonConsultation;
        r.patientUid = patientUid;
        r.radiologyTypeUid = radiologyTypeUid;
        r.patientBillUid = patientBillUid;
        r.settled = settled;
        r.paymentType = paymentType;
        r.membershipNo = membershipNo != null ? membershipNo : "";
        r.insurancePlanUid = insurancePlanUid;
        r.diagnosisTypeUid = diagnosisTypeUid;
        r.clinicianUserUid = clinicianUserUid;
        r.status = RadiologyStatus.PENDING;
        r.businessDayUid = dayUid;
        r.createdByUserUid = actorUserUid;
        r.createdOnDayUid = dayUid;
        r.orderedByUserUid = actorUserUid;
        r.orderedOnDayUid = dayUid;
        r.orderedAt = now;
        return r;
    }

    // -------------------------------------------------------------------------
    // Lifecycle domain methods (state machine)
    // -------------------------------------------------------------------------

    /**
     * Accept the order: PENDING | REJECTED → ACCEPTED.
     *
     * <p><strong>Radiology asymmetry:</strong> Unlike LabTest.accept(), this does NOT clear
     * the reject_comment when accepting from REJECTED. This reproduces the legacy radiology
     * behaviour (verified finding — no clear call in legacy radiology accept path).
     * Stamps accepted_* audit triplet. Clears accepted_* fields from any prior accept.
     *
     * <p>NO bill re-check (CR-INC05-01 parity — the settlement gate is only at order/worklist time).
     *
     * <p>Guard: caller must verify status IN (PENDING, REJECTED) before calling.
     *
     * @param actorUserUid user performing the accept
     * @param dayUid       current business day uid
     * @param now          current instant
     */
    public void accept(String actorUserUid, String dayUid, Instant now) {
        this.status = RadiologyStatus.ACCEPTED;
        this.acceptedByUserUid = actorUserUid;
        this.acceptedOnDayUid = dayUid;
        this.acceptedAt = now;
        // NOTE: Do NOT clear reject_comment here — radiology asymmetry vs LabTest.
        // The legacy radiology accept path does NOT clear rejectComment.
        // (Verification finding — exact-process mandate.)
    }

    /**
     * Reject the order: PENDING | ACCEPTED → REJECTED.
     *
     * <p>Clears accept_* fields. Sets rejectComment. Stamps rejected_* audit triplet.
     *
     * <p>Guard: caller must verify status IN (PENDING, ACCEPTED) before calling.
     *
     * @param rejectComment  reason for rejection
     * @param actorUserUid   user performing the rejection
     * @param dayUid         current business day uid
     * @param now            current instant
     */
    public void reject(String rejectComment, String actorUserUid, String dayUid, Instant now) {
        this.status = RadiologyStatus.REJECTED;
        this.rejectComment = rejectComment;
        this.rejectedByUserUid = actorUserUid;
        this.rejectedOnDayUid = dayUid;
        this.rejectedAt = now;
        // Clear accept fields (reject from ACCEPTED resets acceptance context)
        this.acceptedByUserUid = null;
        this.acceptedOnDayUid = null;
        this.acceptedAt = null;
    }

    /**
     * Edit the rejection comment on an already-REJECTED order (inc-06A C3 / ITEM3).
     *
     * <p>Reproduces legacy {@code save_reason_for_rejection} (PatientResource.java:2018-2032):
     * sets ONLY {@code rejectComment}, no status change, re-callable; no null/blank validation.
     *
     * <p>Guard: caller must verify {@code status == REJECTED} before calling.
     *
     * @param rejectComment the new rejection reason (may be null/blank — persisted as-is)
     */
    public void updateRejectComment(String rejectComment) {
        this.rejectComment = rejectComment;
    }

    /**
     * Verify the result: ACCEPTED → VERIFIED (PatientResource.java:4280-4281).
     *
     * <p><strong>Active path is ACCEPTED → VERIFIED DIRECTLY.</strong>
     * There is no collect step for radiology (CR-INC05-14). This is the primary behavioural
     * difference from LabTest (which goes COLLECTED → VERIFIED).
     *
     * <p>Writes result/report from the request body. Sets the inline attachment blob.
     * Guard: caller must verify status == ACCEPTED before calling.
     * Wrong-status throws 422 "Please accept the radiology order first" (parity pattern).
     *
     * @param result       the examination result text
     * @param report       the full report / interpretation text
     * @param attachment   the inline image/report blob (nullable — not all verifications include a blob)
     * @param actorUserUid user verifying the result
     * @param dayUid       current business day uid
     * @param now          current instant
     */
    public void verify(String result, String report, byte[] attachment,
                       String actorUserUid, String dayUid, Instant now) {
        this.status = RadiologyStatus.VERIFIED;
        this.result = result;
        this.report = report;
        this.attachment = attachment;
        this.verifiedByUserUid = actorUserUid;
        this.verifiedOnDayUid = dayUid;
        this.verifiedAt = now;
    }

    /**
     * Hold (revert): ACCEPTED → PENDING.
     *
     * <p>Stamps held_* audit triplet, then reverts to PENDING.
     * Guard: caller must verify status == ACCEPTED before calling.
     *
     * @param actorUserUid user performing the hold
     * @param dayUid       current business day uid
     * @param now          current instant
     */
    public void hold(String actorUserUid, String dayUid, Instant now) {
        this.status = RadiologyStatus.PENDING;
        this.heldByUserUid = actorUserUid;
        this.heldOnDayUid = dayUid;
        this.heldAt = now;
    }

    /**
     * Save/update the result text without status change.
     *
     * <p>Allowed when status == ACCEPTED (PatientResource.java:4305-4306 parity — radiology
     * edits when ACCEPTED, not COLLECTED like lab tests).
     * Guard: caller must verify status == ACCEPTED before calling.
     *
     * @param result the result text
     */
    public void saveResult(String result) {
        this.result = result;
    }

    /**
     * Add/update the radiologist report text without status change (inc-06A C5 / ITEM2).
     *
     * <p>Reproduces legacy {@code radiologies/add_report} (PatientResource.java:3183-3197): writes
     * ONLY the {@code report} field, gated on the BILL status (not order status). The bill-gate is
     * enforced in the service layer; this domain method just sets the field.
     *
     * @param report the report text to write / overwrite
     */
    public void addReport(String report) {
        this.report = report;
    }

    /**
     * Amend the report narrative of an already-VERIFIED radiology order (inc-06A C6 / ITEM4).
     *
     * <p>Ratified audited-amend policy: retains the current narrative into {@code priorReport}
     * (append-only) and stamps the amend audit triplet. result stays immutable after VERIFIED.
     *
     * <p>Guard: caller must verify {@code status == VERIFIED} and the bill-gate before calling.
     *
     * @param newReport    the amended report text
     * @param actorUserUid user performing the amendment
     * @param dayUid       current business day uid
     * @param now          current instant
     */
    public void amendReport(String newReport, String actorUserUid, String dayUid, Instant now) {
        this.priorReport = this.report;
        this.report = newReport;
        this.reportAmendedByUserUid = actorUserUid;
        this.reportAmendedOnDayUid = dayUid;
        this.reportAmendedAt = now;
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

    // -------------------------------------------------------------------------
    // Attachment helpers
    // -------------------------------------------------------------------------

    /**
     * Maximum number of named attachments per radiology order (PatientServiceImpl.java:2928-2930).
     */
    public static final int MAX_ATTACHMENTS = 5;

    /**
     * Returns true if this radiology order can have a named attachment added now.
     *
     * <p>Rules (PatientServiceImpl.java:2931-2933):
     * <ol>
     *   <li>Status must be ACCEPTED (note: ACCEPTED gate, NOT COLLECTED like lab).</li>
     *   <li>Current attachment count must be less than MAX_ATTACHMENTS (5).</li>
     * </ol>
     *
     * @param currentCount current number of named attachments (from repository count)
     * @return true if attachment can be added
     */
    public boolean canAttach(long currentCount) {
        return this.status == RadiologyStatus.ACCEPTED && currentCount < MAX_ATTACHMENTS;
    }

    /**
     * Returns true if named attachments can be deleted (blocked when VERIFIED — order finalized).
     *
     * @return true if deletion is allowed
     */
    public boolean canDeleteAttachment() {
        return this.status != RadiologyStatus.VERIFIED;
    }

    /**
     * Returns true if named attachments can be downloaded/viewed (gated on VERIFIED).
     *
     * <p><strong>NET-NEW PHI-safety control (inc-06A C7 review F3/SEC-05 — ratified deviation):</strong>
     * the legacy download (PatientResource.java:6093-6140 radiology) is UNGATED. This VERIFIED
     * download-gate is a deliberate tightening, NOT legacy parity. (PatientResource.java:6154 is the
     * legacy attachment-DELETE VERIFIED gate, a different operation.)
     *
     * @return true if download is allowed
     */
    public boolean canDownloadAttachment() {
        return this.status == RadiologyStatus.VERIFIED;
    }
}
