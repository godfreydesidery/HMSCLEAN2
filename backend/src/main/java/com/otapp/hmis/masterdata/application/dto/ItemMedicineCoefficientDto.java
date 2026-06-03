package com.otapp.hmis.masterdata.application.dto;

import java.math.BigDecimal;

/**
 * Wire representation of {@link com.otapp.hmis.masterdata.domain.ItemMedicineCoefficient}
 * (build-spec §1.2).
 *
 * <p>FK entities are exposed as {@code uid} strings (not nested objects).
 * {@code coefficient} is the computed value {@code medicineQty / itemQty}, scale 6.
 * No {@code id} field (ADR-0014 §1).
 */
public record ItemMedicineCoefficientDto(
        String uid,
        String itemUid,
        String medicineUid,
        BigDecimal coefficient,
        BigDecimal itemQty,
        BigDecimal medicineQty) {
}
