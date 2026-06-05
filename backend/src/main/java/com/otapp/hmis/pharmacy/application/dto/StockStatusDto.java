package com.otapp.hmis.pharmacy.application.dto;

import java.math.BigDecimal;

/**
 * Pharmacy stock status for one medicine (inc-08a). Exposes the public uid + the authoritative
 * scalar aggregate balance; no internal id (ADR-0014 §1).
 */
public record StockStatusDto(
        String uid,
        String pharmacyUid,
        String medicineUid,
        BigDecimal stock
) {
}
