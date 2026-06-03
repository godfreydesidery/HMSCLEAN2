package com.otapp.hmis.billing.web;

import com.otapp.hmis.billing.application.CreditNoteService;
import com.otapp.hmis.billing.application.dto.CancelChargeRequest;
import com.otapp.hmis.billing.application.dto.CancellationResultDto;
import com.otapp.hmis.billing.application.dto.CreditNoteDto;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for charge cancellation + credit-note (refund) viewing (P2 scope, build-spec §3.3,
 * §5.4). Gated {@code BILL-A} (seeded V2:53). The legacy PCN is born only as a side-effect of a
 * cancellation, so there is no standalone credit-note creation endpoint — credit notes are read-only
 * here and created by {@link #cancelCharge}.
 *
 * <p>No {@code @Transactional} on this controller (ArchUnit gate — ADR-0014 §5); the transaction
 * boundary lives on {@link CreditNoteService}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/v1/billing/bills/uid/{billUid}/cancellation — cancel a charge (+ refund/PCN)</li>
 *   <li>GET  /api/v1/billing/credit-notes?patientUid= — a patient's credit notes (newest first)</li>
 *   <li>GET  /api/v1/billing/credit-notes/uid/{uid} — a single credit note</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class CreditNoteController {

    private final CreditNoteService creditNoteService;
    private final BusinessDayService businessDayService;

    /**
     * Cancel a charge: soft-cancel the bill, refund any received payment (issuing a PENDING
     * full-amount credit note), and detach it from its insurance claim (CR-10 FIX).
     *
     * @param billUid the bill to cancel
     * @param request the cause label stamped onto the credit note
     * @param jwt     the authenticated principal (for actor username)
     * @return the cancellation result (bill status + optional credit note)
     */
    @PostMapping("/bills/uid/{billUid}/cancellation")
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public CancellationResultDto cancelCharge(
            @PathVariable("billUid") String billUid,
            @Valid @RequestBody CancelChargeRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = new TxAuditContext(
                businessDayService.currentUid(),
                Instant.now(),
                jwt.getSubject());

        return creditNoteService.cancel(billUid, request.reference(), ctx);
    }

    /**
     * List a patient's credit notes, newest first.
     *
     * @param patientUid the patient uid (required)
     */
    @GetMapping("/credit-notes")
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public List<CreditNoteDto> listCreditNotes(@RequestParam String patientUid) {
        return creditNoteService.listByPatient(patientUid);
    }

    /**
     * Fetch a single credit note by uid.
     *
     * @param uid the credit-note uid
     */
    @GetMapping("/credit-notes/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public CreditNoteDto getCreditNote(@PathVariable("uid") String uid) {
        return creditNoteService.getByUid(uid);
    }
}
