package com.otapp.hmis.clinical.api;

import com.otapp.hmis.shared.domain.TxAuditContext;

/**
 * Published write seam: save and approve admission-bound referral plans and deceased notes
 * (clinical::api, inc-07 07a-3).
 *
 * <p>Mirrors the consultation-path {@link ConsultationSignOut} / {@link PrescriptionChartPort}
 * seam pattern: the {@code clinical} module owns the {@link com.otapp.hmis.clinical.domain.ReferralPlan}
 * and {@link com.otapp.hmis.clinical.domain.DeceasedNote} entities; the {@code inpatient} module
 * orchestrates the disposition gates and then calls down through this port.
 *
 * <p><strong>Responsibility split (inc-07 07a-3 architecture ruling):</strong>
 * <ul>
 *   <li><strong>Inpatient orchestrates:</strong> bills-cleared gate, SoD second-approver gate,
 *       admission status transition, bed free, PatientDeceasedEvent/PatientReferredEvent/
 *       PatientDischargedEvent publication, AdmissionBed.close().</li>
 *   <li><strong>Clinical owns the entity write:</strong> save the note/plan with admissionUid set
 *       (XOR-context); approve transitions PENDING→APPROVED with the real approver from ctx;
 *       audit records via AuditRecorder. Clinical does NOT run gates, free beds, or publish
 *       patient events for the admission path.</li>
 * </ul>
 *
 * <p><strong>XOR error message (verbatim legacy typo — reproduced exactly):</strong>
 * When an entity is saved without exactly one context set (both null or both non-null), the
 * service throws 422 with the EXACT legacy string
 * {@code "Patient should be inpatient or outpatioent, but not both"} — note the 'outpatioent'
 * typo. Both the "both null" and "both non-null" branches use this SAME string verbatim.
 *
 * <p><strong>Verbatim deceased-save validation message:</strong>
 * When {@code patientSummary} or {@code causeOfDeath} is blank, the service throws 422 with
 * the EXACT legacy string {@code "Summary and cause of death are missing"}
 * (PatientResource.java:5720-5730). Enforced in the service, NOT via @NotBlank.
 *
 * <p><strong>Transaction contract:</strong>
 * All methods use {@code Propagation.REQUIRED} — they join the calling inpatient transaction.
 * The approve methods assert that the note/plan belongs to the specified admissionUid and is
 * PENDING before approving.
 *
 * <p><strong>Module boundary (ADR-0008 §6, ADR-0022 D5):</strong>
 * <ul>
 *   <li>This interface is published in {@code clinical.api} — accessible to {@code inpatient}
 *       which already declares {@code clinical::api} as an allowed dependency.</li>
 *   <li>No new module edge is introduced (inpatient already depends on clinical::api).</li>
 *   <li>{@code ApplicationModules.verify()} remains green.</li>
 * </ul>
 *
 * <p>Implementation is package-private in {@code clinical.application.ClosureService}
 * (the deferred admission-path branches are now fully implemented in 07a-3).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>save_deceased_note (admission): PatientResource.java:5693-5773</li>
 *   <li>get_deceased_summary (approve): PatientResource.java:5837-5934</li>
 *   <li>save_referral_plan (admission): PatientResource.java:5342 (discharge context) /
 *       :5593-5685 (referral context)</li>
 *   <li>get_referral_summary (approve): PatientResource.java:5593-5685</li>
 *   <li>XOR typo: legacy message verbatim from PatientResource.java</li>
 * </ul>
 */
public interface AdmissionDispositionPort {

    /**
     * Save a referral plan bound to an admission (inpatient path).
     *
     * <p>Validates externalMedicalProviderUid is non-blank (CR-07-Q7). Creates a new PENDING
     * {@link com.otapp.hmis.clinical.domain.ReferralPlan} with {@code admissionUid} set and
     * {@code consultation = null}. Idempotent: if a plan already exists for this admission,
     * updates it in-place (same reuse-if-exists pattern as the OPD deceased path).
     *
     * <p>Does NOT transition the admission status — that is the caller's (inpatient) responsibility.
     * Does NOT publish any patient event — inpatient publishes those.
     *
     * @param cmd the referral command (admissionUid, patientUid, provider, narrative fields)
     * @param ctx transaction audit context (dayUid, actor, timestamp)
     * @return the saved {@link ReferralPlanView} (status = PENDING); includes createdByUserUid for SoD
     */
    ReferralPlanView saveAdmissionReferralPlan(RecordAdmissionReferralCommand cmd, TxAuditContext ctx);

    /**
     * Save a deceased note bound to an admission (inpatient path).
     *
     * <p>Validates patientSummary AND causeOfDeath non-blank — throws 422
     * {@code "Summary and cause of death are missing"} if either is blank.
     * Creates a new PENDING {@link com.otapp.hmis.clinical.domain.DeceasedNote} with
     * {@code admissionUid} set and {@code consultation = null}. Idempotent: if a note already
     * exists for this admission, updates it in-place.
     *
     * <p>Does NOT transition the admission to HELD — that is the caller's (inpatient) responsibility.
     * Does NOT free the bed — inpatient does that.
     *
     * @param cmd the deceased note command (admissionUid, patientUid, summary, cause, date/time)
     * @param ctx transaction audit context (dayUid, actor, timestamp)
     * @return the saved {@link DeceasedNoteView} (status = PENDING); includes createdByUserUid for SoD
     */
    DeceasedNoteView saveAdmissionDeceasedNote(RecordAdmissionDeceasedNoteCommand cmd, TxAuditContext ctx);

    /**
     * Approve a referral plan bound to an admission (inpatient path).
     *
     * <p>Pre-conditions (verified by this method):
     * <ul>
     *   <li>The plan with {@code referralUid} exists.</li>
     *   <li>The plan's admissionUid matches {@code admissionUid} (ownership guard).</li>
     *   <li>The plan's status is PENDING.</li>
     * </ul>
     *
     * <p>On success: transitions plan PENDING→APPROVED; sets approvedByUserUid from
     * {@code ctx.actorUsername()}; records audit. Does NOT check bills or manage beds.
     *
     * @param referralUid  uid of the referral plan to approve
     * @param admissionUid loose uid of the owning admission (ownership guard)
     * @param ctx          transaction audit context (actor = approver)
     * @return the updated {@link ReferralPlanView} (status = APPROVED)
     */
    ReferralPlanView approveAdmissionReferralPlan(String referralUid, String admissionUid,
                                                  TxAuditContext ctx);

    /**
     * Approve a deceased note bound to an admission (inpatient path).
     *
     * <p>Pre-conditions (verified by this method):
     * <ul>
     *   <li>The note with {@code noteUid} exists.</li>
     *   <li>The note's admissionUid matches {@code admissionUid} (ownership guard).</li>
     *   <li>The note's status is PENDING.</li>
     * </ul>
     *
     * <p>On success: transitions note PENDING→APPROVED; sets approvedByUserUid from
     * {@code ctx.actorUsername()}; records audit. Does NOT publish PatientDeceasedEvent —
     * inpatient publishes that. Does NOT run the bills gate.
     *
     * @param noteUid      uid of the deceased note to approve
     * @param admissionUid loose uid of the owning admission (ownership guard)
     * @param ctx          transaction audit context (actor = approver)
     * @return the updated {@link DeceasedNoteView} (status = APPROVED)
     */
    DeceasedNoteView approveAdmissionDeceasedNote(String noteUid, String admissionUid,
                                                  TxAuditContext ctx);
}
