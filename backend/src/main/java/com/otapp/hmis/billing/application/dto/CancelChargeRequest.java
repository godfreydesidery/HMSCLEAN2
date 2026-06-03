package com.otapp.hmis.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for cancelling a charge (the billing-side of the legacy clinical-cancel flow).
 *
 * <p>The {@code reference} is the free-text cause label that legacy stamps onto the credit note
 * (e.g. "Canceled consultation", "Canceled lab test" — PatientResource.java:646). The caller
 * (cashier endpoint, or a clinical module in inc-05/06) supplies the appropriate label.
 *
 * @param reference cause label written onto the credit note (required, ≤255 chars)
 */
public record CancelChargeRequest(
        @NotBlank @Size(max = 255) String reference
) {
}
