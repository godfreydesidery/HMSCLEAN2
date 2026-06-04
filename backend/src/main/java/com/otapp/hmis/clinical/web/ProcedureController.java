package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.application.ProcedurePort;
import com.otapp.hmis.clinical.application.dto.ProcedureDto;
import com.otapp.hmis.clinical.application.dto.ProcedureNoteRequest;
import com.otapp.hmis.clinical.application.dto.ProcedureOrderRequest;
import com.otapp.hmis.clinical.application.dto.ProcedureUpdateRequest;
import com.otapp.hmis.clinical.domain.ProcedureStatus;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the Procedure aggregate (inc-05 C9).
 *
 * <p>All endpoints are <strong>authenticated-only</strong> (no {@code @PreAuthorize})
 * per CR-INC05-02 parity — procedure endpoints carry no additional legacy RBAC gates
 * beyond authentication.
 *
 * <p>No {@code @Transactional} on the controller — the service owns the transaction
 * boundary (ADR-0014 §5).
 *
 * <p><strong>Base paths:</strong>
 * <ul>
 *   <li>{@code /api/v1/clinical/consultations}     — consultation-scoped order creation + list</li>
 *   <li>{@code /api/v1/clinical/non-consultations} — non-consultation-scoped order creation</li>
 *   <li>{@code /api/v1/clinical/procedures}        — procedure lifecycle + worklist + by-patient</li>
 * </ul>
 *
 * <p><strong>Endpoint surface (11 endpoints):</strong>
 * <ul>
 *   <li>{@code POST   /consultations/uid/{uid}/procedures}       → 201 (order on consultation)</li>
 *   <li>{@code GET    /consultations/uid/{uid}/procedures}       → 200 (list by consultation)</li>
 *   <li>{@code POST   /non-consultations/uid/{uid}/procedures}   → 201 (order on walk-in)</li>
 *   <li>{@code GET    /procedures/uid/{uid}}                     → 200 (get by uid)</li>
 *   <li>{@code POST   /procedures/uid/{uid}/accept}              → 200 (accept PENDING|REJECTED → ACCEPTED)</li>
 *   <li>{@code POST   /procedures/uid/{uid}/note}                → 200 (add_note ACCEPTED+settled → VERIFIED)</li>
 *   <li>{@code PUT    /procedures/uid/{uid}}                     → 200 (update fields, ACCEPTED only)</li>
 *   <li>{@code DELETE /procedures/uid/{uid}}                     → 204 (delete PENDING only)</li>
 *   <li>{@code GET    /procedures/worklist}                      → 200 (settled PENDING+ACCEPTED)</li>
 *   <li>{@code GET    /procedures}                               → 200 (by-patient query)</li>
 * </ul>
 *
 * <p><strong>NO approve, NO reject, NO hold, NO collect endpoints.</strong>
 * The planning-doc M14 "approve" step is FABRICATED. There is no reject_procedure endpoint
 * in the legacy system. The held_* columns are VESTIGIAL.
 *
 * <p><strong>Distinctive behaviour:</strong>
 * The {@code POST /procedures/uid/{uid}/note} endpoint is the ONLY endpoint in the order family
 * that gates on the local settled flag mid-transition (PatientResource.java:3408-3414).
 * The 422 verbatim message is "Could not add procedure note. Payment not verified".
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>add_note (bill gate): PatientResource.java:3408-3414</li>
 *   <li>update_procedure:     PatientResource.java:4060-4061</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class ProcedureController {

    private final ProcedurePort      procedureService;
    private final BusinessDayService businessDayService;

    // =========================================================================
    // Order creation — consultation path
    // POST /api/v1/clinical/consultations/uid/{uid}/procedures
    // =========================================================================

    /**
     * Order a clinical procedure on an existing consultation (outpatient path).
     *
     * <p>Guards: consultation exists (404); procedureType exists (404 "Procedure type not found");
     * no duplicate type for this consultation (422 "Duplicate procedure type is not allowed for
     * this encounter"). Creates a PatientBill via billing charge. Returns PENDING order.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/consultations/uid/{uid}/procedures")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcedureDto orderForConsultation(
            @PathVariable("uid") String consultationUid,
            @Valid @RequestBody ProcedureOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.orderForConsultation(consultationUid, request, ctxFrom(jwt));
    }

    /**
     * List all procedure orders for a consultation (ordered by creation time ascending).
     *
     * <p>Returns an empty list if the consultation has no procedure orders.
     * Returns 404 if the consultation uid is unknown.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/consultations/uid/{uid}/procedures")
    public List<ProcedureDto> listByConsultation(
            @PathVariable("uid") String consultationUid,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.listByConsultation(consultationUid);
    }

    // =========================================================================
    // Order creation — non-consultation (walk-in/OUTSIDER) path
    // POST /api/v1/clinical/non-consultations/uid/{uid}/procedures
    // =========================================================================

    /**
     * Order a clinical procedure on an existing non-consultation (OUTSIDER/walk-in path).
     *
     * <p>Guards: non-consultation exists (404); procedureType exists (404); no duplicate type
     * for this non-consultation (422). Creates a PatientBill via billing charge. Returns PENDING.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/non-consultations/uid/{uid}/procedures")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcedureDto orderForNonConsultation(
            @PathVariable("uid") String nonConsultationUid,
            @Valid @RequestBody ProcedureOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.orderForNonConsultation(nonConsultationUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Get by uid
    // GET /api/v1/clinical/procedures/uid/{uid}
    // =========================================================================

    /**
     * Get a procedure order by ULID.
     *
     * <p>Returns 404 if the uid is unknown. No id in response (ADR-0014 §1).
     * Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/procedures/uid/{uid}")
    public ProcedureDto getByUid(
            @PathVariable("uid") String procedureUid,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.getByUid(procedureUid);
    }

    // =========================================================================
    // Lifecycle transitions
    // POST /api/v1/clinical/procedures/uid/{uid}/<transition>
    // =========================================================================

    /**
     * Accept the procedure order: PENDING | REJECTED → ACCEPTED.
     *
     * <p>Guard: status must be PENDING or REJECTED (else 422).
     * REJECTED is unreachable at runtime (no reject endpoint) but the guard is reproduced.
     * NO bill re-check (CR-INC05-01 parity — settlement only checked at add_note time).
     * Authenticated-only.
     */
    @PostMapping("/procedures/uid/{uid}/accept")
    public ProcedureDto accept(
            @PathVariable("uid") String procedureUid,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.accept(procedureUid, ctxFrom(jwt));
    }

    /**
     * Add procedure note and transition to VERIFIED: ACCEPTED + settled → VERIFIED.
     *
     * <p><strong>DISTINCTIVE SETTLEMENT GATE (PatientResource.java:3408-3414):</strong>
     * Requires status==ACCEPTED AND note non-blank AND settled==true.
     * <ul>
     *   <li>422 "Please accept the procedure first" — if status != ACCEPTED</li>
     *   <li>400 validation error — if note is blank (@NotBlank on request body)</li>
     *   <li>422 "Could not add procedure note. Payment not verified" — if settled==false</li>
     * </ul>
     * This is the ONLY transition in the entire order family that explicitly re-checks
     * settlement at transition time.
     * Authenticated-only.
     */
    @PostMapping("/procedures/uid/{uid}/note")
    public ProcedureDto addNote(
            @PathVariable("uid") String procedureUid,
            @Valid @RequestBody ProcedureNoteRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.addNote(procedureUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Update (no status change) — ACCEPTED gate
    // PUT /api/v1/clinical/procedures/uid/{uid}
    // =========================================================================

    /**
     * Update procedure fields without status change. Status must be ACCEPTED.
     *
     * <p>Allows editing note, type, diagnosis, procDate, procTime, hours, minutes.
     * Does NOT require settled — update is not gated on payment (PatientResource.java:4060-4061).
     * Guard: status must be ACCEPTED (else 422 "Procedure order must be accepted to update").
     * Authenticated-only.
     */
    @PutMapping("/procedures/uid/{uid}")
    public ProcedureDto update(
            @PathVariable("uid") String procedureUid,
            @RequestBody ProcedureUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.update(procedureUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Delete
    // DELETE /api/v1/clinical/procedures/uid/{uid}
    // =========================================================================

    /**
     * Hard-delete a PENDING procedure order. Only allowed when status == PENDING (else 422).
     * Credit-note for already-PAID bills is DEFERRED. Authenticated-only.
     */
    @DeleteMapping("/procedures/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("uid") String procedureUid,
            @AuthenticationPrincipal Jwt jwt) {
        procedureService.delete(procedureUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Worklist
    // GET /api/v1/clinical/procedures/worklist
    // =========================================================================

    /**
     * Procedure worklist — settled orders in actionable statuses {PENDING, ACCEPTED}.
     *
     * <p>Outpatient + outsider only (no inpatient procedure worklist in legacy).
     * The settled filter (local flag) replaces reading billing bill status (CR-INC05-01).
     * Optional {@code status} query param to filter to a single status.
     * Authenticated-only.
     *
     * @param status optional status filter (PENDING / ACCEPTED)
     */
    @GetMapping("/procedures/worklist")
    public List<ProcedureDto> worklist(
            @RequestParam(name = "status", required = false) ProcedureStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.worklist(status);
    }

    // =========================================================================
    // By-patient query
    // GET /api/v1/clinical/procedures?patientUid=...&status=...
    // =========================================================================

    /**
     * All procedure orders for a patient, optionally filtered by status.
     *
     * <p>Returns an empty list if the patient has no procedure orders.
     * Ordered newest first. Authenticated-only.
     *
     * <p><strong>DEFERRED (CR-INC05-15):</strong> The legacy by-patient query excludes
     * admission-scoped procedures. No admission procedures exist in C9, so this returns all.
     *
     * @param patientUid MANDATORY patient ULID
     * @param status     optional status filter
     */
    @GetMapping("/procedures")
    public List<ProcedureDto> byPatient(
            @RequestParam("patientUid") String patientUid,
            @RequestParam(name = "status", required = false) ProcedureStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        return procedureService.byPatient(patientUid, status);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Build the per-operation audit context at the controller edge (ADR-0008 §3). */
    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(),
                jwt.getSubject());
    }
}
