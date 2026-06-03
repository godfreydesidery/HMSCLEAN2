package com.otapp.hmis.billing.web;

import com.otapp.hmis.billing.application.BillQueryService;
import com.otapp.hmis.billing.application.dto.PatientBillDto;
import com.otapp.hmis.billing.domain.BillStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the cashier bill-collection queue (build-spec §5.4 "cashier queue").
 *
 * <p>Outpatient CASH charges are not attached to any invoice, so {@code listInvoices} cannot
 * surface them; this lists a patient's bills directly so the cashier can select the UNPAID/VERIFIED
 * charges to collect. Gated {@code BILL-A}. No {@code @Transactional} on the controller (ADR-0014 §5).
 */
@RestController
@RequestMapping("/api/v1/billing/bills")
@RequiredArgsConstructor
public class PatientBillController {

    private final BillQueryService billQueryService;

    /**
     * List a patient's bills, optionally filtered by status (the cashier collection queue).
     *
     * @param patientUid patient uid (required)
     * @param status     optional status filter (UNPAID/VERIFIED/COVERED/PAID/NONE/CANCELED)
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('BILL-A')")
    public List<PatientBillDto> listBills(
            @RequestParam String patientUid,
            @RequestParam(required = false) BillStatus status) {
        return billQueryService.listBills(patientUid, status);
    }
}
