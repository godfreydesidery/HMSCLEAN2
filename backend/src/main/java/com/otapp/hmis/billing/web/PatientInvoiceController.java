package com.otapp.hmis.billing.web;

import com.otapp.hmis.billing.application.InvoiceQueryService;
import com.otapp.hmis.billing.application.dto.PatientInvoiceDto;
import com.otapp.hmis.billing.application.dto.PatientPaymentDto;
import com.otapp.hmis.billing.application.dto.RecordPaymentRequest;
import com.otapp.hmis.billing.domain.InvoiceStatus;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
 * REST endpoints for patient invoice management and payment recording (P1 scope).
 *
 * <p>Gated with {@code BILL-A} (seeded V2:53, confirmed live — build-spec §5.4,
 * 11-DECISIONS-RATIFIED). No {@code @Transactional} on this controller (ArchUnit gate —
 * ADR-0014 §5). Transaction boundary is on the service layer.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/v1/billing/invoices?patientUid=&status= — cashier invoice queue</li>
 *   <li>GET  /api/v1/billing/invoices/uid/{uid} — invoice detail</li>
 *   <li>POST /api/v1/billing/invoices/uid/{uid}/payments — record payment</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/billing/invoices")
@RequiredArgsConstructor
public class PatientInvoiceController {

    private final InvoiceQueryService invoiceQueryService;
    private final BusinessDayService businessDayService;

    /**
     * Cashier invoice queue — list invoices for a patient, optionally filtered by status.
     *
     * @param patientUid patient uid (required)
     * @param status     optional filter (PENDING | APPROVED)
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public List<PatientInvoiceDto> listInvoices(
            @RequestParam String patientUid,
            @RequestParam(required = false) InvoiceStatus status) {
        return invoiceQueryService.listInvoices(patientUid, status);
    }

    /**
     * Invoice detail view.
     *
     * @param uid the invoice uid
     */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public PatientInvoiceDto getInvoice(@PathVariable("uid") String uid) {
        return invoiceQueryService.getInvoice(uid);
    }

    /**
     * Record a full payment for one or more bills on this invoice (POST).
     * Exact-tender guard enforced by {@link com.otapp.hmis.billing.application.PaymentService}.
     *
     * @param uid     the invoice uid
     * @param request the payment request (bill uids + tendered total + mode)
     * @param jwt     the authenticated principal (for actor username)
     */
    @PostMapping("/uid/{uid}/payments")
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public ResponseEntity<PatientPaymentDto> recordPayment(
            @PathVariable("uid") String uid,
            @Valid @RequestBody RecordPaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = new TxAuditContext(
                businessDayService.currentUid(),
                Instant.now(),
                jwt.getSubject());

        PatientPaymentDto result = invoiceQueryService.recordPayment(uid, request, ctx);

        URI location = URI.create("/api/v1/billing/payments/uid/" + result.uid());
        return ResponseEntity.created(location).body(result);
    }
}
