package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.api.ConsultationDto;
import com.otapp.hmis.clinical.application.ConsultationLifecyclePort;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for clinical consultation lifecycle (ADR-0022 D4, inc-05 §2, §D).
 *
 * <p>Design constraints (ADR-0014 §5):
 * <ul>
 *   <li>No {@code @Transactional} on the controller — the service owns the boundary.</li>
 *   <li>Only uid-based routes — no {@code {id}} path variables (ADR-0014 §1).</li>
 *   <li>{@link TxAuditContext} built at the controller edge (same pattern as
 *       {@code registration.web.PatientController}).</li>
 * </ul>
 *
 * <p>RBAC: authenticated-only (no {@code @PreAuthorize}) per CR-INC05-02 ratified
 * (11-DECISIONS-RATIFIED.md §2 — only the 4 real legacy gates apply: PATIENT-ALL/CREATE/UPDATE
 * on do_consultation/switch/cancel/free. Those gates live on the registration controller's
 * send-to-doctor endpoint. The clinical lifecycle endpoints are authentication-only per parity).
 *
 * <p>All business-rule errors are rendered as RFC 7807 {@code ProblemDetail} by
 * {@link com.otapp.hmis.shared.error.GlobalExceptionHandler}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>open:          PatientResource.java:886 (open_consultation)</li>
 *   <li>openFollowUp:  PatientResource.java:915 (open_follow_up_consultation)</li>
 *   <li>cancel:        PatientResource.java:618 (cancel_consultation)</li>
 *   <li>free:          PatientResource.java:699, :764 (free_consultation)</li>
 *   <li>switchNormal:  PatientResource.java (switch_to_consultation)</li>
 *   <li>worklist:      PatientResource.java:817-826 (pending reception queue)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical/consultations")
@RequiredArgsConstructor
public class ConsultationController {

    private final ConsultationLifecyclePort lifecycleService;
    private final BusinessDayService businessDayService;

    /**
     * Get a single consultation by uid.
     * Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/uid/{uid}")
    public ConsultationDto getByUid(@PathVariable("uid") String uid) {
        return lifecycleService.getByUid(uid);
    }

    /**
     * Open a PENDING consultation: PENDING → IN_PROCESS.
     *
     * <p>Settlement gate evaluated here (CR-INC05-01 — gate at OPEN only).
     * CASH-unsettled → 422 {@code PAY_BEFORE_SERVICE}.
     * Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the updated ConsultationDto
     */
    @PostMapping("/uid/{uid}/open")
    public ConsultationDto open(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.open(uid, ctxFrom(jwt));
    }

    /**
     * Open a PENDING follow-up consultation: PENDING → IN_PROCESS (followUp=true only).
     *
     * <p>Guard: consultation must have {@code followUp=true}; else 422.
     * If status is not PENDING: silent no-op (legacy parity).
     * Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the current (possibly unchanged) ConsultationDto
     */
    @PostMapping("/uid/{uid}/open-follow-up")
    public ConsultationDto openFollowUp(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.openFollowUp(uid, ctxFrom(jwt));
    }

    /**
     * Cancel a PENDING consultation: PENDING → CANCELED.
     *
     * <p>Guard: status must be PENDING; else 422 with verbatim legacy message.
     * Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the updated ConsultationDto
     */
    @PostMapping("/uid/{uid}/cancel")
    public ConsultationDto cancel(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.cancel(uid, ctxFrom(jwt));
    }

    /**
     * Free (sign out) a consultation: IN_PROCESS or TRANSFERED → SIGNED_OUT.
     *
     * <p>Guard: status must be IN_PROCESS or TRANSFERED; else 422 with verbatim legacy message.
     * Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the updated ConsultationDto
     */
    @PostMapping("/uid/{uid}/free")
    public ConsultationDto free(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.free(uid, ctxFrom(jwt));
    }

    /**
     * Switch a follow-up consultation to a normal (charged) consultation.
     *
     * <p>Sets {@code followUp=false}. For CASH patients, resets {@code settled=false}
     * so the payment gate is re-evaluated on next open. INSURANCE stays settled.
     * Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the updated ConsultationDto
     */
    @PostMapping("/uid/{uid}/switch-to-normal")
    public ConsultationDto switchToNormal(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.switchToNormal(uid, ctxFrom(jwt));
    }

    /**
     * Doctor reception queue — non-follow-up PENDING settled consultations for a clinician
     * (PART D, PatientResource.java:817-826).
     *
     * <p>The {@code settled=true} filter is the local-flag equivalent of the legacy bill
     * PAID/COVERED filter (ADR-0022 D4, inc-05 §5 — clinical never reads billing bill status).
     *
     * <p>TODO: If the legacy behaviour derives the clinician from the authenticated principal
     * rather than an explicit query param, wire that derivation here (principal.getSubject() →
     * clinicianUserUid lookup via iam::lookup). For now, an explicit {@code clinicianUserUid}
     * query param is accepted to keep the endpoint simple and testable.
     * Authenticated-only (CR-INC05-02).
     *
     * @param clinicianUserUid the ULID of the clinician user (explicit param)
     * @return ordered list of consultations in the reception queue
     */
    @GetMapping("/reception-queue")
    public List<ConsultationDto> receptionQueue(
            @RequestParam("clinicianUserUid") String clinicianUserUid) {
        return lifecycleService.receptionQueue(clinicianUserUid);
    }

    /** Build the per-operation audit context at the controller edge (ADR-0008 §3). */
    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
