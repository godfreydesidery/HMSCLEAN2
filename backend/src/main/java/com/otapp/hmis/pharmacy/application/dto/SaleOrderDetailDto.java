package com.otapp.hmis.pharmacy.application.dto;

import java.math.BigDecimal;

/**
 * OTC sale-order detail response (inc-08a chunk 4). No internal id (ADR-0014 §1). {@code status} is
 * the exact fulfilment DB string ("NOT-GIVEN"/"GIVEN"); {@code payStatus} is UNPAID/PAID.
 */
public record SaleOrderDetailDto(
        String uid,
        String medicineUid,
        String patientBillUid,
        String issuePharmacyUid,
        BigDecimal qty,
        BigDecimal issued,
        BigDecimal balance,
        String status,
        String payStatus,
        String dosage,
        String frequency,
        String route,
        String days
) {
}
