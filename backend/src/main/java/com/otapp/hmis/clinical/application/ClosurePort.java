package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.DeceasedNoteDto;
import com.otapp.hmis.clinical.application.dto.DeceasedNoteRequest;
import com.otapp.hmis.clinical.application.dto.ReferralPlanDto;
import com.otapp.hmis.clinical.application.dto.ReferralPlanRequest;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Port for the OPD encounter-closure operations (inc-05 C12).
 *
 * <p>Published to the {@code web} layer only — package-private is not used here because
 * the controller is in a sibling package ({@code clinical.web}) and needs access.
 * The implementation ({@link ClosureService}) is package-private to the
 * {@code clinical.application} package.
 *
 * <p>Defines the six consultation-branch closure operations:
 * <ul>
 *   <li>{@link #saveDeceasedNote} — create/update the death note and hold the consultation.</li>
 *   <li>{@link #approveDeceased} — sign out the consultation, approve the note, emit deceased event.</li>
 *   <li>{@link #listDeceased} — list PENDING|APPROVED notes (ARCHIVED hidden).</li>
 *   <li>{@link #saveReferralPlan} — create the referral plan, sign out the consultation, emit insurance-cleared event.</li>
 *   <li>{@link #approveReferral} — approve the referral plan, re-confirm consultation SIGNED_OUT.</li>
 *   <li>{@link #listReferrals} — list PENDING|APPROVED plans (ARCHIVED hidden).</li>
 * </ul>
 */
public interface ClosurePort {

    /**
     * Create or update a deceased note for the given consultation (OPD path).
     *
     * <p>Guards:
     * <ol>
     *   <li>patientSummary and causeOfDeath both non-blank; else 422
     *       "Summary and cause of death are missing".</li>
     *   <li>Exactly-one-encounter: consultation must exist; admissionUid = null (OPD).</li>
     *   <li>If an existing PENDING note exists for this consultation, it is updated in-place
     *       (reuse-if-exists pattern).</li>
     * </ol>
     *
     * <p>Side effects: consultation status → HELD (unconditional).
     *
     * @param consultationUid the ULID of the consultation
     * @param request         the death note request body
     * @param ctx             transaction audit context (actor username, day uid, timestamp)
     * @return the saved/updated DeceasedNoteDto (status = PENDING)
     */
    DeceasedNoteDto saveDeceasedNote(String consultationUid, DeceasedNoteRequest request,
                                     TxAuditContext ctx);

    /**
     * Approve a deceased note (get_deceased_summary transition — OPD path).
     *
     * <p>Guards:
     * <ol>
     *   <li>If the note's consultation status != HELD: SILENT NO-OP (reproduce legacy).</li>
     *   <li>Bill-gate: no unsettled clinical order for the consultation (lab/radiology/
     *       procedure/prescription where settled=false); else 422
     *       "Could not get deceased summary. Patient have uncleared bills."</li>
     * </ol>
     *
     * <p>Side effects on success:
     * <ul>
     *   <li>consultation status → SIGNED_OUT</li>
     *   <li>note status → APPROVED; approvedByUserUid set from ctx.actorUsername()</li>
     *   <li>publishes {@code PatientDeceasedEvent(patientUid)} → registration sets
     *       Patient.type = DECEASED</li>
     * </ul>
     *
     * @param noteUid the ULID of the deceased note
     * @param ctx     transaction audit context
     * @return the updated DeceasedNoteDto (status = APPROVED), or the unchanged DTO if no-op
     */
    DeceasedNoteDto approveDeceased(String noteUid, TxAuditContext ctx);

    /**
     * List all deceased notes with status PENDING or APPROVED (ARCHIVED hidden).
     *
     * <p>Legacy citation: PatientResource.java:5826 (load_deceased_list hides ARCHIVED).
     *
     * @return list of DeceasedNoteDtos, ordered newest-first
     */
    List<DeceasedNoteDto> listDeceased();

    /**
     * Create a referral plan for the given consultation (OPD path).
     *
     * <p>Guards:
     * <ol>
     *   <li>Exactly-one-encounter: consultation must exist; admissionUid = null (OPD).</li>
     *   <li>No existing PENDING ReferralPlan for this consultation; else 422
     *       "A pending referral plan already exists for this consultation".</li>
     *   <li>Referral bill-gate (UNPAID-only subset — CR-INC05-09 asymmetry): no unsettled
     *       clinical order for the consultation; else 422
     *       "Could not save referral. Patient have uncleared bills."
     *       (See implementation note on UNPAID-vs-UNPAID|VERIFIED asymmetry.)</li>
     * </ol>
     *
     * <p>Side effects on success:
     * <ul>
     *   <li>consultation status → SIGNED_OUT (immediately at save)</li>
     *   <li>plan status = PENDING</li>
     *   <li>publishes {@code PatientInsuranceClearedEvent(patientUid)} → registration clears
     *       patient insurance (sets payment type = CASH, plan = null)</li>
     * </ul>
     *
     * @param consultationUid the ULID of the consultation
     * @param request         the referral plan request body
     * @param ctx             transaction audit context
     * @return the saved ReferralPlanDto (status = PENDING)
     */
    ReferralPlanDto saveReferralPlan(String consultationUid, ReferralPlanRequest request,
                                     TxAuditContext ctx);

    /**
     * Approve a referral plan (get_referral_summary transition — OPD path).
     *
     * <p>Guards:
     * <ol>
     *   <li>No unsettled clinical order for the consultation; else 422
     *       "Could not get referral summary. Patient have uncleared bills."</li>
     * </ol>
     *
     * <p>Side effects on success:
     * <ul>
     *   <li>consultation status → SIGNED_OUT (unconditional re-set)</li>
     *   <li>plan status → APPROVED; approvedByUserUid set from ctx.actorUsername()</li>
     * </ul>
     *
     * @param referralUid the ULID of the referral plan
     * @param ctx         transaction audit context
     * @return the updated ReferralPlanDto (status = APPROVED)
     */
    ReferralPlanDto approveReferral(String referralUid, TxAuditContext ctx);

    /**
     * List all referral plans with status PENDING or APPROVED (ARCHIVED hidden).
     *
     * <p>Mirrors the deceased list pattern.
     *
     * @return list of ReferralPlanDtos, ordered newest-first
     */
    List<ReferralPlanDto> listReferrals();
}
