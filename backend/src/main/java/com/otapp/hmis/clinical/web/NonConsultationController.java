package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.api.NonConsultationDto;
import com.otapp.hmis.clinical.application.WalkInPort;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the walk-in (NonConsultation) track (inc-05 C4, ADR-0022).
 *
 * <p>Exposes the walk-in encounter lifecycle under {@code /api/v1/clinical/non-consultations}.
 * The NonConsultation is the OUTSIDER / no-doctor encounter: a patient arriving directly at the
 * lab, radiology, or procedure desk without a doctor referral.
 *
 * <p>Design constraints (ADR-0014 §5):
 * <ul>
 *   <li>No {@code @Transactional} on the controller — the service owns the boundary.</li>
 *   <li>Only uid-based routes — no {@code {id}} path variables (ADR-0014 §1).</li>
 *   <li>{@link TxAuditContext} built at the controller edge (same pattern as
 *       {@code ConsultationController}).</li>
 * </ul>
 *
 * <p>RBAC: authenticated-only (no {@code @PreAuthorize}) per CR-INC05-02 parity — the walk-in
 * track has no additional legacy RBAC gates beyond authentication.
 *
 * <p>All business-rule errors are rendered as RFC 7807 {@code ProblemDetail} by
 * {@link com.otapp.hmis.shared.error.GlobalExceptionHandler}.
 *
 * <p>DEFERRED — order-save wiring (inc-05 C4 build-spec §3):
 * The actual wiring of walk-in get-or-create into the lab/radiology/procedure order-save paths
 * (PatientServiceImpl.java:790-806, :1033-1048, :1280-1296) is deferred to C7-C9 when those
 * order entities are built. The {@code POST /} endpoint here exercises the get-or-create logic
 * directly for testing and API consumer use.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Lab get-or-create:        PatientServiceImpl.java:790-806</li>
 *   <li>Radiology get-or-create:  PatientServiceImpl.java:1033-1048</li>
 *   <li>Procedure get-or-create:  PatientServiceImpl.java:1280-1296</li>
 *   <li>Sign-out:                 PatientResource.java:350</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical/non-consultations")
@RequiredArgsConstructor
public class NonConsultationController {

    private final WalkInPort walkInService;
    private final BusinessDayService businessDayService;

    // -------------------------------------------------------------------------
    // POST / — open / get-or-create a walk-in encounter
    // -------------------------------------------------------------------------

    /**
     * Open or get the current IN_PROCESS walk-in encounter for a patient.
     *
     * <p>If the patient already has an IN_PROCESS NonConsultation, the existing row is returned
     * (idempotent — no duplicate created). Otherwise a new IN_PROCESS row is created.
     *
     * <p>This is the get-or-create entry-point; the C7-C9 order-save paths will call the service
     * directly rather than via HTTP (DEFERRED). This endpoint is exposed for direct testing and
     * manual invocation.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param request the walk-in open request (patientUid, visitUid, paymentType, etc.)
     * @param jwt     the authenticated principal
     * @return 200 with the existing IN_PROCESS encounter, or 200 with the newly-created one
     */
    @PostMapping
    public NonConsultationDto openOrGet(
            @Valid @RequestBody OpenWalkInRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return walkInService.getOrCreateInProcess(
                request.patientUid(),
                request.visitUid(),
                request.paymentType() != null ? request.paymentType() : "",
                request.membershipNo() != null ? request.membershipNo() : "",
                request.insurancePlanUid(),
                ctxFrom(jwt));
    }

    // -------------------------------------------------------------------------
    // GET /uid/{uid} — read one
    // -------------------------------------------------------------------------

    /**
     * Read a single non-consultation by ULID.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid the ULID of the NonConsultation
     * @return the NonConsultationDto
     */
    @GetMapping("/uid/{uid}")
    public NonConsultationDto getByUid(@PathVariable("uid") String uid) {
        return walkInService.getByUid(uid);
    }

    // -------------------------------------------------------------------------
    // POST /uid/{uid}/sign-out — IN_PROCESS → SIGNED_OUT
    // -------------------------------------------------------------------------

    /**
     * Sign out a walk-in encounter: IN_PROCESS → SIGNED_OUT.
     *
     * <p>Guard: status must be IN_PROCESS; else 422 "Walk-in encounter is not open".
     * Authenticated-only (CR-INC05-02).
     *
     * <p>Legacy citation: PatientResource.java:350 (free / sign-out of walk-in track).
     *
     * @param uid the ULID of the NonConsultation
     * @param jwt the authenticated principal
     * @return the updated NonConsultationDto (status = SIGNED-OUT)
     */
    @PostMapping("/uid/{uid}/sign-out")
    public NonConsultationDto signOut(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return walkInService.signOut(uid, ctxFrom(jwt));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Build the per-operation audit context at the controller edge (ADR-0008 §3). */
    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }

    // -------------------------------------------------------------------------
    // Request body record
    // -------------------------------------------------------------------------

    /**
     * Request body for {@code POST /api/v1/clinical/non-consultations}.
     *
     * @param patientUid       ULID of the patient (required)
     * @param visitUid         ULID of the associated visit (required)
     * @param paymentType      CASH, INSURANCE, or '' (optional; defaults to '' if omitted)
     * @param membershipNo     insurance membership number (optional; defaults to '' if omitted)
     * @param insurancePlanUid loose uid of the insurance plan (optional; null for CASH)
     */
    public record OpenWalkInRequest(
            @NotBlank String patientUid,
            @NotBlank String visitUid,
            String paymentType,
            String membershipNo,
            String insurancePlanUid
    ) {
    }
}
