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
 * Laboratory test order aggregate (LabTest.java:43-154, V24/V33).
 *
 * <p>A SEPARATE entity — NOT polymorphic. Each order corresponds to one labTestType
 * and belongs to exactly one encounter (consultation, non-consultation, or admission).
 *
 * <p><strong>Result columns (LabTest.java:51-52 etc.):</strong>
 * {@code result}, {@code report} (TEXT, length 10000), {@code description}, {@code rrange}
 * ({@link #testRange} mapped via {@code @Column(name="rrange")} because 'range' is a reserved
 * SQL word), {@code level}, {@code unit}.
 *
 * <p><strong>Status (LabTest.java:55):</strong>
 * {@link LabTestStatus} enum — PENDING / ACCEPTED / REJECTED / COLLECTED / VERIFIED.
 * All values are valid Java identifiers; plain {@code @Enumerated(STRING)} — no converter.
 *
 * <p><strong>Encounter binding (V24 CHECK num_nonnulls=1):</strong>
 * Exactly one of {@code consultation} / {@code nonConsultation} / {@code admissionUid}
 * must be non-null. The consultation and non-consultation paths are real intra-module FKs;
 * the admission path is a loose uid (admission module DEFERRED).
 *
 * <p><strong>Cross-module refs (ADR-0022 D2 Correction, V33):</strong>
 * {@code patient_uid} is a loose VARCHAR(26) — replaces the V24 {@code patient_id BIGINT FK}
 * dropped by V33. {@code labTestTypeUid} is a loose ref to masterdata. {@code patientBillUid}
 * is a loose ref to billing. All other *Uid fields are loose refs (no FK).
 *
 * <p><strong>Settlement flag (CR-INC05-01, V33):</strong>
 * {@code settled} is the clinical-local settlement projection. Set at order time from the
 * billing ChargeResult: true for COVERED/VERIFIED/inpatient, false for CASH-OPD (UNPAID).
 * The cash-PAID→settled=true propagation is deferred (same pattern as Consultation).
 * The lab worklist filters by settled=true.
 *
 * <p><strong>Attachments:</strong>
 * {@link LabTestAttachment} @OneToMany with orphanRemoval=true.
 * Application rules (NOT DB): max 5 per test; attach only when status=COLLECTED.
 * Download gated on status=VERIFIED. Delete-attachment blocked when VERIFIED.
 * (PatientServiceImpl.java:2828-2834; PatientResource.java:6021.)
 *
 * <p><strong>DEFERRED — admission path:</strong>
 * The {@code admissionUid} column exists and is mapped as a loose nullable String. No
 * admission-scoped endpoints are implemented in C7. Full admission lab support is deferred
 * to the Inpatient/Nursing increment.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: LabTest.java:43-154</li>
 *   <li>rrange column name: LabTest.java:51-52</li>
 *   <li>Status: LabTest.java:55</li>
 *   <li>Encounter binding: LabTest.java:65-78</li>
 *   <li>patientBill OneToOne: LabTest.java:85-88</li>
 *   <li>Attachments orphanRemoval: LabTest.java:149-153</li>
 *   <li>Lifecycle transitions: PatientResource.java:3947-3980</li>
 *   <li>Max 5 attachments: PatientServiceImpl.java:2828-2830</li>
 *   <li>Attach only when COLLECTED: PatientServiceImpl.java:2832-2834</li>
 *   <li>Download gated VERIFIED: PatientResource.java:6021</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "lab_tests")
public class LabTest extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Result columns
    // -------------------------------------------------------------------------

    /** Free-text result (written at verify time). */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    /**
     * Full report / interpretation text (TEXT, legacy length 10000).
     * A separate field from {@code result} — updated via {@code addReport}.
     */
    @Column(name = "report", columnDefinition = "TEXT")
    private String report;

    /**
     * Retained prior report narrative — set when a VERIFIED report is amended (inc-06A C6 / ITEM4).
     * Append-only audit of the pre-amendment text; never overwritten back into {@code report}.
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
     * Reference range for the test result.
     *
     * <p><strong>Column name {@code rrange} (LabTest.java:51-52):</strong>
     * 'range' is a reserved SQL word; legacy uses {@code @Column(name="rrange")} verbatim.
     * Mapped via {@code @Column(name="rrange")} — the Java field is named {@code testRange}
     * to avoid Java reserved-word conflicts (alternatively could be {@code range} but that
     * conflicts with SQL in native queries and documentation). The DB column name is authoritative.
     */
    @Column(name = "rrange", length = 200)
    private String testRange;

    /** Result level indicator (e.g., HIGH / LOW / NORMAL). */
    @Column(name = "level", length = 200)
    private String level;

    /** Unit of measure for the result (e.g., mg/dL). */
    @Column(name = "unit", length = 60)
    private String unit;

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /**
     * Lifecycle status. All five values are valid Java identifiers — plain @Enumerated(STRING).
     * (LabTest.java:55; V24 CHECK constraint.)
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private LabTestStatus status = LabTestStatus.PENDING;

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
     * MANDATORY loose ref to the lab test type in the masterdata module (no FK, ADR-0008).
     * Verified via LabTestTypeLookup before persist.
     */
    @NotBlank
    @Column(name = "lab_test_type_uid", length = 26, nullable = false, updatable = false)
    private String labTestTypeUid;

    /**
     * Loose ref to the PatientBill created at order time (billing module, no FK).
     * NOT NULL — every lab test order has exactly one bill.
     */
    @NotBlank
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2 Correction, V33).
     * Replaces the original V24 patient_id BIGINT FK dropped by V33.
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Optional loose refs
    // -------------------------------------------------------------------------

    /** Optional loose ref to a diagnosis type associated with this test (nullable). */
    @Column(name = "diagnosis_type_uid", length = 26)
    private String diagnosisTypeUid;

    /** Optional loose ref to the ordering clinician user (nullable). */
    @Column(name = "clinician_user_uid", length = 26)
    private String clinicianUserUid;

    /** Optional loose ref to the insurance plan (nullable; null for CASH). */
    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    // -------------------------------------------------------------------------
    // Encounter binding (exactly one non-null — V24 CHECK num_nonnulls=1)
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
    // Settlement flag (CR-INC05-01, V33)
    // -------------------------------------------------------------------------

    /**
     * Clinical-local settlement projection (CR-INC05-01, ADR-0022 D2/D4, V33).
     *
     * <p>Set at order time:
     * <ul>
     *   <li>{@code true}  — INSURANCE/COVERED or inpatient (auto-pass; no prepayment required)</li>
     *   <li>{@code false} — CASH-OPD / CASH-OUTSIDER (bill must be paid before worklist shows)</li>
     * </ul>
     *
     * <p><strong>DEFERRED NOTE — cash-PAID propagation seam:</strong>
     * When a CASH patient pays their lab test bill, a billing→clinical event must flip this flag
     * to true. Until that seam is built, CASH lab orders are ordered correctly but do NOT appear
     * on the worklist until the seam lands. INSURANCE/COVERED orders appear immediately.
     * This is the same deferral pattern as {@code Consultation.settled} (V29 / C2).
     */
    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    // -------------------------------------------------------------------------
    // Lifecycle audit triplets (ordered_* / accepted_* / held_* / collected_* / verified_* / rejected_*)
    // All are loose uid refs (VARCHAR(26)) + a timestamp — no FK.
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

    /** User who collected the specimen (audit). */
    @Column(name = "collected_by_user_uid", length = 26)
    private String collectedByUserUid;

    /** Business day uid when the specimen was collected (audit). */
    @Column(name = "collected_on_day_uid", length = 26)
    private String collectedOnDayUid;

    /** Timestamp when the specimen was collected (audit). */
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

    /** Reason for rejection (cleared on accept). */
    @Column(name = "reject_comment", columnDefinition = "TEXT")
    private String rejectComment;

    /** User who created the order record (audit — same as orderedByUserUid in typical flow). */
    @Column(name = "created_by_user_uid", length = 26)
    private String createdByUserUid;

    /** Business day uid when the record was created (audit). */
    @Column(name = "created_on_day_uid", length = 26)
    private String createdOnDayUid;

    /** Business day uid at time of record creation (loose ref, no FK). */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Attachments
    // -------------------------------------------------------------------------

    /**
     * Lab test result file attachments (LabTest.java:149-153).
     *
     * <p>orphanRemoval=true — removing from this list hard-deletes the attachment.
     * Application rules (NOT DB): max 5 per test; attach only when status=COLLECTED.
     */
    @OneToMany(mappedBy = "labTest", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<LabTestAttachment> labTestAttachments = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Factory methods (one per encounter type)
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING lab test order bound to a {@link Consultation} (outpatient path).
     *
     * <p>Guard: consultation status must be OUTPATIENT-compatible (caller verifies).
     * Stamps the ordered_* audit triplet.
     */
    public static LabTest forConsultation(Consultation consultation,
                                           String labTestTypeUid,
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
        LabTest lt = new LabTest();
        lt.consultation = consultation;
        lt.patientUid = consultation.getPatientUid();
        lt.labTestTypeUid = labTestTypeUid;
        lt.patientBillUid = patientBillUid;
        lt.settled = settled;
        lt.paymentType = paymentType;
        lt.membershipNo = membershipNo != null ? membershipNo : "";
        lt.insurancePlanUid = insurancePlanUid;
        lt.diagnosisTypeUid = diagnosisTypeUid;
        lt.clinicianUserUid = clinicianUserUid;
        lt.status = LabTestStatus.PENDING;
        lt.businessDayUid = dayUid;
        lt.createdByUserUid = actorUserUid;
        lt.createdOnDayUid = dayUid;
        lt.orderedByUserUid = actorUserUid;
        lt.orderedOnDayUid = dayUid;
        lt.orderedAt = now;
        return lt;
    }

    /**
     * Create a new PENDING lab test order bound to a {@link NonConsultation} (OUTSIDER/walk-in path).
     *
     * <p>Guard: non-consultation status must be IN_PROCESS (caller verifies via WalkInService).
     * Stamps the ordered_* audit triplet.
     */
    public static LabTest forNonConsultation(NonConsultation nonConsultation,
                                              String patientUid,
                                              String labTestTypeUid,
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
        LabTest lt = new LabTest();
        lt.nonConsultation = nonConsultation;
        lt.patientUid = patientUid;
        lt.labTestTypeUid = labTestTypeUid;
        lt.patientBillUid = patientBillUid;
        lt.settled = settled;
        lt.paymentType = paymentType;
        lt.membershipNo = membershipNo != null ? membershipNo : "";
        lt.insurancePlanUid = insurancePlanUid;
        lt.diagnosisTypeUid = diagnosisTypeUid;
        lt.clinicianUserUid = clinicianUserUid;
        lt.status = LabTestStatus.PENDING;
        lt.businessDayUid = dayUid;
        lt.createdByUserUid = actorUserUid;
        lt.createdOnDayUid = dayUid;
        lt.orderedByUserUid = actorUserUid;
        lt.orderedOnDayUid = dayUid;
        lt.orderedAt = now;
        return lt;
    }

    // -------------------------------------------------------------------------
    // Lifecycle domain methods (state machine)
    // -------------------------------------------------------------------------

    /**
     * Accept the order: PENDING | REJECTED → ACCEPTED.
     *
     * <p>Clears reject_* fields (accept from REJECTED resets rejection context).
     * Stamps accepted_* audit triplet.
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
        this.status = LabTestStatus.ACCEPTED;
        this.acceptedByUserUid = actorUserUid;
        this.acceptedOnDayUid = dayUid;
        this.acceptedAt = now;
        // Clear reject fields (PatientResource.java parity — accept clears prior rejection)
        this.rejectedByUserUid = null;
        this.rejectedOnDayUid = null;
        this.rejectedAt = null;
        this.rejectComment = null;
    }

    /**
     * Reject the order: PENDING | ACCEPTED → REJECTED.
     *
     * <p>Clears accept_* fields. Sets rejectComment. Stamps rejected_* audit triplet.
     *
     * <p>Guard: caller must verify status IN (PENDING, ACCEPTED) before calling.
     *
     * @param rejectComment  reason for rejection (required)
     * @param actorUserUid   user performing the rejection
     * @param dayUid         current business day uid
     * @param now            current instant
     */
    public void reject(String rejectComment, String actorUserUid, String dayUid, Instant now) {
        this.status = LabTestStatus.REJECTED;
        this.rejectComment = rejectComment;
        this.rejectedByUserUid = actorUserUid;
        this.rejectedOnDayUid = dayUid;
        this.rejectedAt = now;
        // Clear accept fields (parity — reject from ACCEPTED resets acceptance context)
        this.acceptedByUserUid = null;
        this.acceptedOnDayUid = null;
        this.acceptedAt = null;
    }

    /**
     * Edit the rejection comment on an already-REJECTED order (inc-06A C3 / ITEM3).
     *
     * <p>Reproduces legacy {@code save_reason_for_rejection} (PatientResource.java:2034-2048):
     * sets ONLY {@code rejectComment}, with NO status change and NO audit-triplet re-stamp,
     * re-callable any number of times. The legacy code applies no null/blank validation, so a
     * null/empty comment is persisted verbatim.
     *
     * <p>Guard: caller must verify {@code status == REJECTED} before calling (distinct from
     * {@link #reject} which requires PENDING|ACCEPTED).
     *
     * @param rejectComment the new rejection reason (may be null/blank — persisted as-is)
     */
    public void updateRejectComment(String rejectComment) {
        this.rejectComment = rejectComment;
    }

    /**
     * Collect specimen: ACCEPTED → COLLECTED.
     *
     * <p>Guard: caller must verify status == ACCEPTED before calling.
     * Wrong-status throws 422 "Please accept the lab test first" (PatientResource.java parity).
     *
     * @param actorUserUid user collecting the specimen
     * @param dayUid       current business day uid
     * @param now          current instant
     */
    public void collect(String actorUserUid, String dayUid, Instant now) {
        this.status = LabTestStatus.COLLECTED;
        this.collectedByUserUid = actorUserUid;
        this.collectedOnDayUid = dayUid;
        this.collectedAt = now;
    }

    /**
     * Verify the result: COLLECTED → VERIFIED.
     *
     * <p>Writes result/level/testRange/unit from the request body.
     * Guard: caller must verify status == COLLECTED before calling.
     * Wrong-status throws 422 "Please collect the lab test first" (PatientResource.java parity).
     *
     * @param result       the test result value
     * @param level        result level indicator
     * @param testRange    reference range (mapped to rrange column)
     * @param unit         unit of measure
     * @param actorUserUid user verifying the result
     * @param dayUid       current business day uid
     * @param now          current instant
     */
    public void verify(String result, String level, String testRange, String unit,
                       String actorUserUid, String dayUid, Instant now) {
        this.status = LabTestStatus.VERIFIED;
        this.result = result;
        this.level = level;
        this.testRange = testRange;
        this.unit = unit;
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
        this.status = LabTestStatus.PENDING;
        this.heldByUserUid = actorUserUid;
        this.heldOnDayUid = dayUid;
        this.heldAt = now;
    }

    /**
     * Save/update the result text without status change.
     *
     * <p>Allowed when status == COLLECTED (PatientServiceImpl.java parity).
     * Guard: caller must verify status == COLLECTED before calling.
     *
     * @param result the result text
     */
    public void saveResult(String result) {
        this.result = result;
    }

    /**
     * Add/update the report text without status change.
     *
     * <p>Allowed when status == COLLECTED (PatientServiceImpl.java parity — report is a
     * separate field from result).
     * Guard: caller must verify status == COLLECTED before calling.
     *
     * @param report the full report text
     */
    public void addReport(String report) {
        this.report = report;
    }

    /**
     * Amend the report narrative of an already-VERIFIED lab test (inc-06A C6 / ITEM4).
     *
     * <p>Ratified audited-amend policy: rather than reproduce the legacy silent post-VERIFIED
     * overwrite (a patient-safety defect — no amendment trail), a VERIFIED report may be changed
     * ONLY through this path, which RETAINS the current narrative into {@code priorReport}
     * (append-only) and stamps the amend audit triplet ({@code reportAmendedBy/On/At}).
     * {@code result}/{@code testRange}/{@code level}/{@code unit} stay immutable after VERIFIED.
     *
     * <p>Guard: caller must verify {@code status == VERIFIED} and the bill-gate before calling.
     *
     * @param newReport     the amended report text
     * @param actorUserUid  user performing the amendment
     * @param dayUid        current business day uid
     * @param now           current instant
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
     * For CASH-OPD: DEFERRED (same pattern as Consultation.markSettled()).
     */
    public void markSettled() {
        this.settled = true;
    }

    // -------------------------------------------------------------------------
    // Attachment helpers
    // -------------------------------------------------------------------------

    /**
     * Maximum number of attachments per lab test (PatientServiceImpl.java:2828-2830).
     */
    public static final int MAX_ATTACHMENTS = 5;

    /**
     * Returns true if this lab test can have an attachment added now.
     * Rules (PatientServiceImpl.java:2828-2834):
     * <ol>
     *   <li>Status must be COLLECTED.</li>
     *   <li>Current attachment count must be less than MAX_ATTACHMENTS (5).</li>
     * </ol>
     *
     * @param currentCount current number of attachments (from repository count)
     * @return true if attachment can be added
     */
    public boolean canAttach(long currentCount) {
        return this.status == LabTestStatus.COLLECTED && currentCount < MAX_ATTACHMENTS;
    }

    /**
     * Returns true if attachments can be deleted (blocked when VERIFIED — order is finalized).
     *
     * @return true if deletion is allowed
     */
    public boolean canDeleteAttachment() {
        return this.status != LabTestStatus.VERIFIED;
    }

    /**
     * Returns true if attachments can be downloaded/viewed (gated on VERIFIED).
     *
     * <p><strong>NET-NEW PHI-safety control (inc-06A C7 review F3/SEC-05 — ratified deviation):</strong>
     * the legacy download (PatientResource.java:5960-6007 lab) is UNGATED — it streams at any order
     * status. This VERIFIED download-gate is a deliberate tightening so unverified result images are
     * not exposed; it is NOT legacy parity. (PatientResource.java:6021 is the legacy attachment-DELETE
     * VERIFIED gate, a different operation — do not read it as the download source.)
     *
     * @return true if download is allowed
     */
    public boolean canDownloadAttachment() {
        return this.status == LabTestStatus.VERIFIED;
    }
}
