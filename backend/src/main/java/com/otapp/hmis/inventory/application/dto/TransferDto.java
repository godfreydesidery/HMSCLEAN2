package com.otapp.hmis.inventory.application.dto;

import java.math.BigDecimal;

/**
 * Generic transfer-document response (inc-08b chunk 6) — covers the PSR (RO), SPTO (TO), and PGRN
 * (RN) headers with a uniform shape. No id (ADR-0014 §1). {@code documentType} is RO/TO/RN.
 */
public record TransferDto(
        String uid,
        String no,
        String documentType,
        String status,
        String statusDescription,
        String pharmacyUid,
        String storeUid,
        java.util.List<Line> details
) {
    /** A transfer line — fields are populated per document type (RO uses orderedQty only). */
    public record Line(
            String uid,
            String itemUid,
            String medicineUid,
            BigDecimal orderedQty,
            BigDecimal transferedStoreQty,
            BigDecimal transferedPharmacyQty
    ) {
    }
}
