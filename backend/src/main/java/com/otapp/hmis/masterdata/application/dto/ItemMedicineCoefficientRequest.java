package com.otapp.hmis.masterdata.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for create/update of
 * {@link com.otapp.hmis.masterdata.domain.ItemMedicineCoefficient}.
 *
 * <p>The {@code coefficient} is computed by the service ({@code medicineQty / itemQty});
 * callers supply only {@code itemUid}, {@code medicineUid}, {@code itemQty}, and
 * {@code medicineQty}. Both qty values must be &gt; 0 (build-spec §5.3).
 */
public record ItemMedicineCoefficientRequest(
        @NotBlank String itemUid,
        @NotBlank String medicineUid,
        @NotNull BigDecimal itemQty,
        @NotNull BigDecimal medicineQty) {
}
