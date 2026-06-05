package com.otapp.hmis.clinical.domain;

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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Clinical referral plan to an external medical provider (ReferralPlan.java:35-80, inc-05 C12).
 *
 * <p>A SEPARATE entity from {@link DeceasedNote} — different workflow, different purpose.
 *
 * <p><strong>External provider ref (MANDATORY loose uid):</strong>
 * {@code externalMedicalProviderUid} is a MANDATORY loose VARCHAR(26). The
 * {@code referral.external_medical_providers} table is NOT built in C12. No FK, no existence
 * check — the uid is accepted verbatim from the client (ReferralPlan.java:49-52). Document:
 * existence validation can be added in a future increment when the referral bounded context
 * is built.
 *
 * <p><strong>Seven narrative columns (ReferralPlan.java:35-80):</strong>
 * referringDiagnosis / history / investigation / management / operationNote /
 * icuAdmissionNote / generalRecommendation — all TEXT, nullable, client-supplied verbatim.
 *
 * <p><strong>Encounter binding (V28 CHECK num_nonnulls=1):</strong>
 * Exactly one of {@code consultation} (intra-module real FK) or {@code admissionUid} (loose)
 * must be non-null. OPD path uses {@code consultation}; admission path is DEFERRED.
 *
 * <p><strong>Patient ref (V28 then V37):</strong>
 * Originally a real FK {@code patient_id → patients(id)} (V28). Converted to a loose
 * {@code patient_uid VARCHAR(26)} by V37 (mirrors V30-V36 pattern). No {@code @ManyToOne Patient}.
 *
 * <p><strong>Approver (CR-INC05-03):</strong>
 * Same correction as {@link DeceasedNote}: the REAL approver (ctx.actorUsername()) is recorded,
 * not the creator. The legacy bug (ReferralPlan.java:72-74 — copies approved_by from creator)
 * is not reproduced.
 *
 * <p><strong>Admission path DEFERRED:</strong>
 * {@code admissionUid} is mapped as a loose nullable String. No admission-scoped endpoints.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: ReferralPlan.java:35-80</li>
 *   <li>Status: ReferralPlan.java:47</li>
 *   <li>Provider loose ref: ReferralPlan.java:49-52</li>
 *   <li>Encounter binding: ReferralPlan.java:54-62</li>
 *   <li>Approver bug: ReferralPlan.java:72-74 (corrected CR-INC05-03)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "referral_plans")
public class ReferralPlan extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Seven narrative columns (ReferralPlan.java:35-80)
    // -------------------------------------------------------------------------

    /** Referring diagnosis narrative. */
    @Column(name = "referring_diagnosis", columnDefinition = "TEXT")
    private String referringDiagnosis;

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
    // Status
    // -------------------------------------------------------------------------

    /**
     * Lifecycle status (PENDING / APPROVED / ARCHIVED).
     * Plain {@code @Enumerated(STRING)} — all values are valid Java identifiers.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ReferralPlanStatus status = ReferralPlanStatus.PENDING;

    // -------------------------------------------------------------------------
    // External provider ref (MANDATORY loose uid — NO FK, NO existence check)
    // -------------------------------------------------------------------------

    /**
     * MANDATORY loose uid of the target external medical provider.
     *
     * <p><strong>IMPORTANT (ReferralPlan.java:49-52):</strong>
     * The {@code referral.external_medical_providers} table is NOT built in C12. No FK is
     * declared and no existence check is performed. The uid is accepted verbatim from the
     * client request. Future increments can add existence validation when the referral module
     * is built and the provider table is populated.
     */
    @NotBlank
    @Column(name = "external_medical_provider_uid", length = 26, nullable = false, updatable = false)
    private String externalMedicalProviderUid;

    // -------------------------------------------------------------------------
    // Encounter binding (exactly one non-null — V28 CHECK)
    // -------------------------------------------------------------------------

    /**
     * Intra-module real FK to the owning consultation (OPD path).
     * NULL when bound to an admission (DEFERRED).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", updatable = false)
    private Consultation consultation;

    /**
     * Loose ref to an admission (VARCHAR(26), nullable, no FK).
     * The admissions module is DEFERRED. NULL for OPD consultations.
     */
    @Column(name = "admission_uid", length = 26, updatable = false)
    private String admissionUid;

    // -------------------------------------------------------------------------
    // Cross-module patient ref (loose, post-V37)
    // -------------------------------------------------------------------------

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2, V37).
     * Replaces the original V28 patient_id BIGINT FK dropped by V37.
     */
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Approval audit (CR-INC05-03 — captures REAL approver, not creator)
    // -------------------------------------------------------------------------

    /**
     * Loose uid of the user who approved the plan.
     * CR-INC05-03: set from the APPROVING user (ctx.actorUsername()), NOT from the creator.
     */
    @Column(name = "approved_by_user_uid", length = 26)
    private String approvedByUserUid;

    /**
     * Loose uid of the business day on which approval occurred.
     */
    @Column(name = "approved_on_day_uid", length = 26)
    private String approvedOnDayUid;

    /**
     * Timestamp when the plan was approved (UTC).
     */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /**
     * Business day uid at time of plan creation.
     */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor — OPD/consultation path
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING referral plan bound to a consultation (OPD path).
     *
     * <p>Guards (enforced by the service layer before calling this):
     * <ul>
     *   <li>No existing PENDING ReferralPlan for this consultation.</li>
     *   <li>No unsettled (UNPAID) clinical order for the consultation (OPD gate — referral).</li>
     *   <li>externalMedicalProviderUid is non-blank (REST enforces this via @NotBlank).</li>
     * </ul>
     *
     * @param consultation                 the owning consultation (intra-module)
     * @param patientUid                   loose uid of the patient
     * @param externalMedicalProviderUid   MANDATORY loose uid of the external provider
     * @param referringDiagnosis           narrative (nullable)
     * @param history                      narrative (nullable)
     * @param investigation                narrative (nullable)
     * @param management                   narrative (nullable)
     * @param operationNote                narrative (nullable)
     * @param icuAdmissionNote             narrative (nullable)
     * @param generalRecommendation        narrative (nullable)
     * @param businessDayUid               loose uid of the current open business day
     */
    public ReferralPlan(Consultation consultation,
                        String patientUid,
                        String externalMedicalProviderUid,
                        String referringDiagnosis,
                        String history,
                        String investigation,
                        String management,
                        String operationNote,
                        String icuAdmissionNote,
                        String generalRecommendation,
                        String businessDayUid) {
        this.consultation = consultation;
        this.admissionUid = null; // OPD path — no admission
        this.patientUid = patientUid;
        this.externalMedicalProviderUid = externalMedicalProviderUid;
        this.referringDiagnosis = referringDiagnosis;
        this.history = history;
        this.investigation = investigation;
        this.management = management;
        this.operationNote = operationNote;
        this.icuAdmissionNote = icuAdmissionNote;
        this.generalRecommendation = generalRecommendation;
        this.status = ReferralPlanStatus.PENDING;
        this.businessDayUid = businessDayUid;
    }

    // -------------------------------------------------------------------------
    // Business constructor — Admission/inpatient path
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING referral plan bound to an admission (inpatient path — inc-07 07a-3).
     *
     * <p>XOR-context: {@code consultation = null}, {@code admissionUid} set. V28 CHECK
     * constraint {@code num_nonnulls(consultation_id, admission_uid) = 1} is satisfied.
     *
     * <p>Guards (enforced by the service layer before calling this):
     * <ul>
     *   <li>externalMedicalProviderUid is non-blank (CR-07-Q7 — mandatory for admission path).</li>
     *   <li>No existing ReferralPlan for this admission (service creates only if absent).</li>
     * </ul>
     *
     * <p>Legacy citation: PatientResource.java:5593-5685 (get_referral_summary for admission path).
     *
     * @param admissionUid                 loose uid of the owning admission
     * @param patientUid                   loose uid of the patient
     * @param externalMedicalProviderUid   MANDATORY loose uid of the external provider
     * @param referringDiagnosis           narrative (nullable)
     * @param history                      narrative (nullable)
     * @param investigation                narrative (nullable)
     * @param management                   narrative (nullable)
     * @param operationNote                narrative (nullable)
     * @param icuAdmissionNote             narrative (nullable)
     * @param generalRecommendation        narrative (nullable)
     * @param businessDayUid               loose uid of the current open business day
     */
    public ReferralPlan(String admissionUid,
                        String patientUid,
                        String externalMedicalProviderUid,
                        String referringDiagnosis,
                        String history,
                        String investigation,
                        String management,
                        String operationNote,
                        String icuAdmissionNote,
                        String generalRecommendation,
                        String businessDayUid) {
        this.consultation = null; // inpatient path — no consultation
        this.admissionUid = admissionUid;
        this.patientUid = patientUid;
        this.externalMedicalProviderUid = externalMedicalProviderUid;
        this.referringDiagnosis = referringDiagnosis;
        this.history = history;
        this.investigation = investigation;
        this.management = management;
        this.operationNote = operationNote;
        this.icuAdmissionNote = icuAdmissionNote;
        this.generalRecommendation = generalRecommendation;
        this.status = ReferralPlanStatus.PENDING;
        this.businessDayUid = businessDayUid;
    }

    // -------------------------------------------------------------------------
    // Domain methods — lifecycle
    // -------------------------------------------------------------------------

    /**
     * Update the narrative fields of an existing plan (reuse-if-exists pattern — admission path).
     *
     * <p>Called when {@code saveAdmissionReferralPlan} is invoked but a plan already exists for
     * this admission. Mirrors the {@code DeceasedNote.updateNarrative} pattern.
     *
     * @param externalMedicalProviderUid MANDATORY loose uid of the external provider (may change)
     * @param referringDiagnosis         updated diagnosis narrative (nullable)
     * @param history                    updated history narrative (nullable)
     * @param investigation              updated investigation narrative (nullable)
     * @param management                 updated management narrative (nullable)
     * @param operationNote              updated operation note narrative (nullable)
     * @param icuAdmissionNote           updated ICU note narrative (nullable)
     * @param generalRecommendation      updated recommendation narrative (nullable)
     */
    public void updateNarrative(String externalMedicalProviderUid,
                                String referringDiagnosis,
                                String history,
                                String investigation,
                                String management,
                                String operationNote,
                                String icuAdmissionNote,
                                String generalRecommendation) {
        this.externalMedicalProviderUid = externalMedicalProviderUid;
        this.referringDiagnosis = referringDiagnosis;
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
     * <p>Sets the approval audit triplet. The approver is the REAL approving user
     * (CR-INC05-03 — NOT copied from the creator).
     *
     * @param approverUserUid loose uid of the approving user (from ctx.actorUsername())
     * @param dayUid          current business day uid
     * @param now             current instant
     */
    public void approve(String approverUserUid, String dayUid, Instant now) {
        this.status = ReferralPlanStatus.APPROVED;
        this.approvedByUserUid = approverUserUid;
        this.approvedOnDayUid = dayUid;
        this.approvedAt = now;
    }
}
