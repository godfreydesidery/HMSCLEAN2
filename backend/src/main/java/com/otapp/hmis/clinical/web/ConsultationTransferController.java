package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.api.ConsultationTransferDto;
import com.otapp.hmis.clinical.application.ConsultationTransferPort;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the ConsultationTransfer lifecycle (ADR-0022 D4, inc-05 C3).
 *
 * <p>Endpoints under {@code /api/v1/clinical/consultations}:
 * <ul>
 *   <li>{@code POST /uid/{uid}/transfer} — raise a transfer (body: destinationClinicUid, reason)
 *       → 201 with the transfer DTO.</li>
 *   <li>{@code POST /uid/{uid}/cancel-transfer} — cancel the PENDING transfer for this
 *       consultation → 200 with the (now CANCELED) transfer DTO, or 200 with no body if
 *       silent no-op.</li>
 *   <li>{@code GET  /transfers?status=PENDING} — system-wide pending-transfer queue.</li>
 * </ul>
 *
 * <p>Design constraints (ADR-0014 §5):
 * <ul>
 *   <li>No {@code @Transactional} on the controller — the service owns the boundary.</li>
 *   <li>Only uid-based routes (ADR-0014 §1).</li>
 *   <li>{@link TxAuditContext} built at the controller edge.</li>
 * </ul>
 *
 * <p>RBAC: authenticated-only (no {@code @PreAuthorize}) per CR-INC05-02 ratified
 * (11-DECISIONS-RATIFIED §2 — clinical lifecycle endpoints are authentication-only).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>raise:     PatientServiceImpl.java:2756-2808 (createConsultationTransfer)</li>
 *   <li>cancel:    PatientServiceImpl.java:2810-2830 (cancelConsultationTransfer)</li>
 *   <li>queue:     PatientResource.java:599 (get_consultation_transfers)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical/consultations")
@RequiredArgsConstructor
@Validated
public class ConsultationTransferController {

    private final ConsultationTransferPort transferService;
    private final BusinessDayService businessDayService;

    // -------------------------------------------------------------------------
    // RAISE — POST /uid/{uid}/transfer
    // -------------------------------------------------------------------------

    /**
     * Raise a clinic-to-clinic transfer for an IN_PROCESS consultation.
     *
     * <p>Source consultation must be IN_PROCESS; else 422 "Can not transfer. Not an active
     * consultation". Patient must have no existing PENDING transfer. Destination must differ
     * from source clinic.
     *
     * <p>On success: source consultation → TRANSFERED, transfer created PENDING. Returns 201.
     * Authenticated-only (CR-INC05-02).
     *
     * @param uid  source consultation ULID
     * @param req  raise request (destinationClinicUid, reason)
     * @param jwt  authenticated principal
     * @return the created ConsultationTransferDto
     */
    @PostMapping("/uid/{uid}/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultationTransferDto raise(
            @PathVariable("uid") String uid,
            @RequestBody RaiseTransferRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return transferService.raise(uid, req.destinationClinicUid(), req.reason(), ctxFrom(jwt));
    }

    // -------------------------------------------------------------------------
    // CANCEL — POST /uid/{uid}/cancel-transfer
    // -------------------------------------------------------------------------

    /**
     * Cancel the PENDING transfer associated with a TRANSFERED consultation.
     *
     * <p>If the consultation is not TRANSFERED: silent no-op (200, null body — legacy parity).
     * On success: transfer → CANCELED, source consultation → IN_PROCESS. Returns 200.
     * Authenticated-only (CR-INC05-02).
     *
     * @param uid source consultation ULID
     * @param jwt authenticated principal
     * @return the updated ConsultationTransferDto, or null if silent no-op
     */
    @PostMapping("/uid/{uid}/cancel-transfer")
    public ConsultationTransferDto cancelTransfer(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return transferService.cancelByConsultation(uid, ctxFrom(jwt));
    }

    // -------------------------------------------------------------------------
    // PENDING QUEUE — GET /transfers?status=PENDING
    // -------------------------------------------------------------------------

    /**
     * System-wide pending-transfer queue — ALL PENDING transfers, unscoped by patient/clinic.
     *
     * <p>Reproduces legacy {@code findAllByStatus("PENDING")} unscoped
     * (PatientResource.java:599). The {@code status} query param accepts "PENDING" only
     * in the current implementation (other values return an empty list — no other status
     * has a queue endpoint in the legacy system). Authenticated-only (CR-INC05-02).
     *
     * @param status query param (expected: "PENDING")
     * @return all PENDING transfers system-wide
     */
    @GetMapping("/transfers")
    public List<ConsultationTransferDto> transfers(
            @RequestParam(name = "status", defaultValue = "PENDING") String status) {
        // Only PENDING is a meaningful queue in the legacy system
        // (PatientResource.java:599 — get_consultation_transfers returns PENDING only).
        // Other status values return empty list (no other queue endpoint in legacy).
        if (!"PENDING".equalsIgnoreCase(status)) {
            return List.of();
        }
        return transferService.listPending();
    }

    // -------------------------------------------------------------------------
    // Request record
    // -------------------------------------------------------------------------

    /**
     * Request body for the raise-transfer endpoint.
     *
     * @param destinationClinicUid loose uid of the destination clinic (required)
     * @param reason               free-text rationale (nullable — legacy never validates it)
     */
    public record RaiseTransferRequest(
            @NotBlank String destinationClinicUid,
            String reason
    ) {
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
