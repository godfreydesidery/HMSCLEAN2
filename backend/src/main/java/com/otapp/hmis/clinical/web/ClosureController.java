package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.application.ClosurePort;
import com.otapp.hmis.clinical.application.dto.DeceasedNoteDto;
import com.otapp.hmis.clinical.application.dto.DeceasedNoteRequest;
import com.otapp.hmis.clinical.application.dto.ReferralPlanDto;
import com.otapp.hmis.clinical.application.dto.ReferralPlanRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for OPD encounter-closure operations (inc-05 C12).
 *
 * <p>All endpoints are <strong>authenticated-only</strong> (no additional {@code @PreAuthorize})
 * per CR-INC05-02 parity — closure endpoints carry no additional legacy RBAC gates beyond
 * authentication (11-DECISIONS-RATIFIED.md §2).
 *
 * <p>No {@code @Transactional} on the controller — the service owns the transaction
 * boundary (ADR-0014 §5).
 *
 * <p><strong>Endpoint surface (6 endpoints):</strong>
 * <ul>
 *   <li>{@code POST /consultations/uid/{uid}/deceased-note}   → 201 (save deceased note → HELD + PENDING)</li>
 *   <li>{@code POST /deceased-notes/uid/{noteUid}/approve}    → 200 (approve → SIGNED_OUT + APPROVED + PatientDeceasedEvent)</li>
 *   <li>{@code GET  /deceased-notes}                          → 200 (list PENDING|APPROVED)</li>
 *   <li>{@code POST /consultations/uid/{uid}/referral}        → 201 (save referral → SIGNED_OUT + PENDING + PatientInsuranceClearedEvent)</li>
 *   <li>{@code POST /referrals/uid/{referralUid}/approve}     → 200 (approve referral → APPROVED)</li>
 *   <li>{@code GET  /referrals}                               → 200 (list PENDING|APPROVED)</li>
 * </ul>
 *
 * <p>Base path: {@code /api/v1/clinical}
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>save_deceased_note: PatientResource.java (OPD death note save)</li>
 *   <li>get_deceased_summary: PatientResource.java (approve / sign-out)</li>
 *   <li>load_deceased_list: PatientResource.java:5826</li>
 *   <li>save_referral_plan: PatientResource.java (OPD referral save)</li>
 *   <li>get_referral_summary: PatientResource.java (referral approve)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class ClosureController {

    private final ClosurePort    closurePort;
    private final BusinessDayService businessDayService;

    // =========================================================================
    // DeceasedNote endpoints
    // =========================================================================

    /**
     * Save (create or update) a deceased note for a consultation.
     *
     * <p>Side effects: consultation → HELD; note → PENDING (or updated if reuse-if-exists).
     *
     * <p>Guards (422):
     * <ul>
     *   <li>patientSummary or causeOfDeath blank → "Summary and cause of death are missing"</li>
     * </ul>
     *
     * @param uid    the consultation ULID
     * @param body   the deceased note request (patientSummary, causeOfDeath, deathDate, deathTime)
     * @param jwt    the authenticated principal (provides actorUsername)
     * @return 201 + DeceasedNoteDto (status=PENDING)
     */
    @PostMapping("/consultations/uid/{uid}/deceased-note")
    @ResponseStatus(HttpStatus.CREATED)
    public DeceasedNoteDto saveDeceasedNote(
            @PathVariable String uid,
            @RequestBody DeceasedNoteRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        TxAuditContext ctx = buildCtx(jwt);
        return closurePort.saveDeceasedNote(uid, body, ctx);
    }

    /**
     * Approve a deceased note (get_deceased_summary transition).
     *
     * <p>Side effects (when consultation is HELD and no uncleared bills):
     * <ul>
     *   <li>consultation → SIGNED_OUT</li>
     *   <li>note → APPROVED</li>
     *   <li>PatientDeceasedEvent published → Patient.type = DECEASED</li>
     * </ul>
     *
     * <p>Silent no-op if consultation is not HELD (legacy behaviour reproduced).
     *
     * <p>Guards (422):
     * <ul>
     *   <li>Uncleared clinical orders → "Could not get deceased summary. Patient have uncleared bills."</li>
     * </ul>
     *
     * @param noteUid the deceased note ULID
     * @param jwt     the authenticated principal
     * @return 200 + DeceasedNoteDto (status=APPROVED, or unchanged if no-op)
     */
    @PostMapping("/deceased-notes/uid/{noteUid}/approve")
    public DeceasedNoteDto approveDeceased(
            @PathVariable String noteUid,
            @AuthenticationPrincipal Jwt jwt) {
        TxAuditContext ctx = buildCtx(jwt);
        return closurePort.approveDeceased(noteUid, ctx);
    }

    /**
     * List deceased notes (PENDING and APPROVED; ARCHIVED hidden).
     *
     * <p>Legacy citation: PatientResource.java:5826 (load_deceased_list — hides ARCHIVED).
     *
     * @return 200 + list of DeceasedNoteDto, newest-first
     */
    @GetMapping("/deceased-notes")
    public List<DeceasedNoteDto> listDeceased() {
        return closurePort.listDeceased();
    }

    // =========================================================================
    // ReferralPlan endpoints
    // =========================================================================

    /**
     * Save a referral plan for a consultation.
     *
     * <p>Side effects:
     * <ul>
     *   <li>consultation → SIGNED_OUT (immediately at save)</li>
     *   <li>plan → PENDING</li>
     *   <li>PatientInsuranceClearedEvent published → Patient paymentType = CASH, plan = null</li>
     * </ul>
     *
     * <p>Guards (422):
     * <ul>
     *   <li>Existing PENDING plan → "A pending referral plan already exists for this consultation"</li>
     *   <li>Uncleared clinical orders → "Could not save referral. Patient have uncleared bills."</li>
     * </ul>
     *
     * @param uid    the consultation ULID
     * @param body   the referral plan request (externalMedicalProviderUid + 7 narrative fields)
     * @param jwt    the authenticated principal
     * @return 201 + ReferralPlanDto (status=PENDING)
     */
    @PostMapping("/consultations/uid/{uid}/referral")
    @ResponseStatus(HttpStatus.CREATED)
    public ReferralPlanDto saveReferralPlan(
            @PathVariable String uid,
            @Valid @RequestBody ReferralPlanRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        TxAuditContext ctx = buildCtx(jwt);
        return closurePort.saveReferralPlan(uid, body, ctx);
    }

    /**
     * Approve a referral plan (get_referral_summary transition).
     *
     * <p>Side effects:
     * <ul>
     *   <li>consultation → SIGNED_OUT (unconditional re-confirm)</li>
     *   <li>plan → APPROVED</li>
     * </ul>
     *
     * <p>Guards (422):
     * <ul>
     *   <li>Uncleared clinical orders → "Could not get referral summary. Patient have uncleared bills."</li>
     * </ul>
     *
     * @param referralUid the referral plan ULID
     * @param jwt         the authenticated principal
     * @return 200 + ReferralPlanDto (status=APPROVED)
     */
    @PostMapping("/referrals/uid/{referralUid}/approve")
    public ReferralPlanDto approveReferral(
            @PathVariable String referralUid,
            @AuthenticationPrincipal Jwt jwt) {
        TxAuditContext ctx = buildCtx(jwt);
        return closurePort.approveReferral(referralUid, ctx);
    }

    /**
     * List referral plans (PENDING and APPROVED; ARCHIVED hidden).
     *
     * @return 200 + list of ReferralPlanDto, newest-first
     */
    @GetMapping("/referrals")
    public List<ReferralPlanDto> listReferrals() {
        return closurePort.listReferrals();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private TxAuditContext buildCtx(Jwt jwt) {
        String actor = jwt != null ? jwt.getSubject() : "system";
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), actor);
    }
}
