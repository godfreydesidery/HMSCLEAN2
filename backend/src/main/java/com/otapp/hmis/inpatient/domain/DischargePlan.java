package com.otapp.hmis.inpatient.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Discharge plan narrative for an inpatient admission (inpatient-owned — inc-07 07a-3).
 *
 * <p>There is no equivalent clinical entity for discharge (only ReferralPlan and DeceasedNote
 * exist in the clinical domain). This entity is owned entirely by the inpatient module.
 *
 * <p><strong>Six narrative fields (legacy DischargePlan.java:39-44 verbatim):</strong>
 * <ul>
 *   <li>{@code history}</li>
 *   <li>{@code investigation}</li>
 *   <li>{@code management}</li>
 *   <li>{@code operationNote}</li>
 *   <li>{@code icuAdmissionNote}</li>
 *   <li>{@code generalRecommendation}</li>
 * </ul>
 *
 * <p><strong>Cross-module FK discipline (ADR-0008 §1):</strong>
 * {@code admissionUid} is a loose VARCHAR(26) with no physical FK — the admission lives in the
 * same inpatient module but keeping it loose maintains the flat entity graph and allows the
 * repository to query without loading the Admission entity.
 *
 * <p><strong>Status lifecycle:</strong>
 * PENDING (at save) → APPROVED (at approve). No ARCHIVED state for discharge plans.
 * Stored as a VARCHAR CHECK-constrained column (not an enum converter — both values are plain
 * identifiers).
 *
 * <p><strong>Approver audit (CR-07-SoD):</strong>
 * The approvedBy/approvedAt triplet is set at approval time from ctx.actorUsername().
 * The SoD gate (approver != createdBy) is enforced by DispositionService before calling
 * {@link #approve}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/DischargePlan.java:39-44 (six narrative fields)</li>
 *   <li>get_discharge_summary: PatientResource.java:5342-5390 (save + approve discharge)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "discharge_plans")
public class DischargePlan extends AuditableEntity {

    /**
     * Loose intra-module ref to the owning admission.
     * VARCHAR(26), NOT NULL, no physical FK (keeps entity graph flat).
     * PatientResource.java:5342 — each discharge plan is bound to one admission.
     */
    @NotBlank
    @Column(name = "admission_uid", length = 26, nullable = false, updatable = false)
    private String admissionUid;

    // -------------------------------------------------------------------------
    // Six narrative columns (DischargePlan.java:39-44 — VERBATIM field names)
    // -------------------------------------------------------------------------

    /** Patient history narrative. */
    @Column(name = "history", columnDefinition = "TEXT")
    private String history;

    /** Investigation summary narrative. */
    @Column(name = "investigation", columnDefinition = "TEXT")
    private String investigation;

    /** Management/treatment summary narrative. */
    @Column(name = "management", columnDefinition = "TEXT")
    private String management;

    /** Operation note narrative (if applicable). */
    @Column(name = "operation_note", columnDefinition = "TEXT")
    private String operationNote;

    /** ICU admission note narrative (if applicable). */
    @Column(name = "icu_admission_note", columnDefinition = "TEXT")
    private String icuAdmissionNote;

    /** General recommendation narrative. */
    @Column(name = "general_recommendation", columnDefinition = "TEXT")
    private String generalRecommendation;

    // -------------------------------------------------------------------------
    // Status: PENDING / APPROVED
    // -------------------------------------------------------------------------

    /**
     * Lifecycle status. Two-value vocabulary: {@code PENDING} (at save) / {@code APPROVED}
     * (after second-approver sign-off). Stored as VARCHAR(20) with a CHECK constraint (V46).
     */
    @NotBlank
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    // -------------------------------------------------------------------------
    // Approval audit (CR-07-SoD — captures REAL approver, not creator)
    // -------------------------------------------------------------------------

    /**
     * Username of the user who approved this plan.
     * CR-07-SoD: set from ctx.actorUsername() at approve time; must differ from createdBy.
     * Nullable until approved.
     */
    @Column(name = "approved_by", length = 80)
    private String approvedBy;

    /**
     * Loose uid of the business day on which approval occurred.
     */
    @Column(name = "approved_on_day_uid", length = 26)
    private String approvedOnDayUid;

    /**
     * Timestamp when the plan was approved (UTC). Nullable until approved.
     */
    @Column(name = "approved_at")
    private Instant approvedAt;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING discharge plan for an admission (PatientResource.java:5342-5390).
     *
     * @param admissionUid         loose uid of the owning admission
     * @param history              patient history narrative (nullable)
     * @param investigation        investigation summary (nullable)
     * @param management           management summary (nullable)
     * @param operationNote        operation note (nullable)
     * @param icuAdmissionNote     ICU admission note (nullable)
     * @param generalRecommendation general recommendation (nullable)
     */
    public DischargePlan(String admissionUid,
                         String history,
                         String investigation,
                         String management,
                         String operationNote,
                         String icuAdmissionNote,
                         String generalRecommendation) {
        this.admissionUid = admissionUid;
        this.history = history;
        this.investigation = investigation;
        this.management = management;
        this.operationNote = operationNote;
        this.icuAdmissionNote = icuAdmissionNote;
        this.generalRecommendation = generalRecommendation;
        this.status = "PENDING";
    }

    // -------------------------------------------------------------------------
    // Domain methods
    // -------------------------------------------------------------------------

    /**
     * Update the narrative fields (reuse-if-exists pattern — idempotent save).
     *
     * @param history              updated history narrative
     * @param investigation        updated investigation summary
     * @param management           updated management summary
     * @param operationNote        updated operation note
     * @param icuAdmissionNote     updated ICU note
     * @param generalRecommendation updated general recommendation
     */
    public void updateNarrative(String history,
                                String investigation,
                                String management,
                                String operationNote,
                                String icuAdmissionNote,
                                String generalRecommendation) {
        this.history = history;
        this.investigation = investigation;
        this.management = management;
        this.operationNote = operationNote;
        this.icuAdmissionNote = icuAdmissionNote;
        this.generalRecommendation = generalRecommendation;
    }

    /**
     * Approve the plan: PENDING → APPROVED.
     *
     * <p>Sets the approval audit triplet. The SoD gate (approver != createdBy) is enforced
     * by the service layer before calling this method (CR-07-SoD).
     *
     * @param approverUsername loose username of the approving user (ctx.actorUsername())
     * @param dayUid           current business day uid
     * @param now              current instant
     */
    public void approve(String approverUsername, String dayUid, Instant now) {
        this.status = "APPROVED";
        this.approvedBy = approverUsername;
        this.approvedOnDayUid = dayUid;
        this.approvedAt = now;
    }
}
