package com.otapp.hmis.pharmacy.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pharmacy stock batch (lot) read projection (inc-08a). No internal id (ADR-0014 §1).
 */
public record StockBatchDto(
        String uid,
        String pharmacyUid,
        String medicineUid,
        String batchNo,
        LocalDate manufacturedDate,
        LocalDate expiryDate,
        BigDecimal receivedQty,
        BigDecimal remainingQty
) {
}
