package com.otapp.hmis.pharmacy.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pharmacy stock-card (movement ledger) read projection (inc-08a). Exposes the typed movement kind
 * AND the verbatim legacy reference string. No internal id (ADR-0014 §1).
 */
public record StockMovementDto(
        String uid,
        String pharmacyUid,
        String medicineUid,
        String movementType,
        BigDecimal qtyIn,
        BigDecimal qtyOut,
        BigDecimal runningBalance,
        String reference,
        Instant occurredAt
) {
}
