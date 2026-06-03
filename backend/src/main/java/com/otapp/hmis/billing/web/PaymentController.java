package com.otapp.hmis.billing.web;

import com.otapp.hmis.billing.application.InvoiceQueryService;
import com.otapp.hmis.billing.application.dto.PatientPaymentDto;
import com.otapp.hmis.billing.application.dto.RecordPaymentRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cashier cash-collection payment endpoint (legacy {@code confirm_bills_payment},
 * PatientBillResource.java:269). Pays a LIST of selected bills directly — NOT anchored on an
 * invoice — because outpatient CASH bills are never attached to an invoice. Gated {@code BILL-A};
 * no {@code @Transactional} on the controller (ADR-0014 §5). The receipt for the returned payment
 * is then available at {@code GET /billing/payments/uid/{uid}/receipt}.
 */
@RestController
@RequestMapping("/api/v1/billing/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final InvoiceQueryService invoiceQueryService;
    private final BusinessDayService businessDayService;

    /**
     * Record a full payment for one or more selected bills. Exact-tender guard (CR-12) is enforced
     * in the service: tendered total must equal the sum of bill amounts, else 422.
     *
     * @param request the bill uids + tendered total + mode
     * @param jwt     the authenticated principal (cashier)
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public ResponseEntity<PatientPaymentDto> recordPayment(
            @Valid @RequestBody RecordPaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = new TxAuditContext(
                businessDayService.currentUid(),
                Instant.now(),
                jwt.getSubject());

        PatientPaymentDto result = invoiceQueryService.recordPayment(request, ctx);

        URI location = URI.create("/api/v1/billing/payments/uid/" + result.uid());
        return ResponseEntity.created(location).body(result);
    }
}
