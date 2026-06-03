package com.otapp.hmis.billing.web;

import com.otapp.hmis.billing.application.CollectionReportService;
import com.otapp.hmis.billing.application.ReceiptService;
import com.otapp.hmis.billing.application.dto.CollectionReportRow;
import com.otapp.hmis.billing.application.dto.ReceiptDto;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the EOD collections (cash-up) report and the POS receipt (P3 scope,
 * build-spec §5.1/§5.3). Both gated {@code BILL-A} (build-spec §5.4; legacy report endpoints were
 * ungated — this is net-new hardening). No {@code @Transactional} on this controller (ADR-0014 §5).
 *
 * <p>CashierShift / persisted EOD snapshot / NO_OPEN_SHIFT are [GATED:CR-04 — DEFERRED]; the report
 * always re-aggregates the {@code collections} ledger at read time.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/v1/billing/reports/collections?from=&amp;to=&amp;cashier= — EOD cash-up</li>
 *   <li>GET /api/v1/billing/payments/uid/{uid}/receipt — POS receipt</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingReportController {

    private final CollectionReportService collectionReportService;
    private final ReceiptService receiptService;

    /**
     * EOD collections report for the inclusive day range {@code [from, to]}. When {@code cashier}
     * is supplied the report is filtered to that user; otherwise it sums across all users.
     *
     * @param from    first business day (inclusive, ISO yyyy-MM-dd)
     * @param to      last business day (inclusive, ISO yyyy-MM-dd)
     * @param cashier optional username filter (per-cashier cash-up)
     */
    @GetMapping("/reports/collections")
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public List<CollectionReportRow> collectionsReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cashier) {
        return collectionReportService.collectionsReport(from, to, cashier);
    }

    /**
     * POS receipt for a recorded payment (anchored on the payment uid).
     *
     * @param uid the payment uid
     */
    @GetMapping("/payments/uid/{uid}/receipt")
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public ReceiptDto receipt(@PathVariable("uid") String uid) {
        return receiptService.receiptForPayment(uid);
    }
}
