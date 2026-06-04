package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for adding a prescription batch record (C10).
 *
 * <p>Batch records are near-inert traceability children (PrescriptionBatch.java:34-48).
 *
 * <p>Legacy citation: PrescriptionBatch.java:34-48.
 */
public record PrescriptionBatchRequest(

        /** Free-text batch number (not generated). NOT NULL and non-blank. */
        @NotBlank String no,

        /** Manufacturing date (nullable). */
        LocalDate manufacturedDate,

        /** Expiry date (nullable). */
        LocalDate expiryDate,

        /** Quantity in this batch. Must be >= 0. */
        @NotNull BigDecimal qty

) {
}
