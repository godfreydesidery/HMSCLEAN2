package com.otapp.hmis.pharmacy.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request to dispense a clinical prescription from a pharmacy (inc-08a chunk 3).
 *
 * <p>{@code pharmacyUid} is the required, server-validated stock-source selector (Q2). {@code issued}
 * must equal the full prescribed qty (all-or-nothing; the clinical aggregate enforces it). The lots
 * consumed are chosen by FEFO server-side — the caller does not pick lots.
 *
 * @param pharmacyUid the pharmacy to pull stock from (required, server-validated; no affiliation check)
 * @param issued      the quantity to dispense (must equal the prescribed qty)
 */
public record DispenseRequest(
        @NotBlank String pharmacyUid,
        @NotNull @Positive BigDecimal issued
) {
}
