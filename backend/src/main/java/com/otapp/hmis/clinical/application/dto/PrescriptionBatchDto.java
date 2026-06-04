package com.otapp.hmis.clinical.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DTO for a {@link com.otapp.hmis.clinical.domain.PrescriptionBatch} response (C10).
 *
 * <p>No id leak (ADR-0014 §1). Only the public {@code uid} is exposed.
 *
 * <p>Legacy citation: PrescriptionBatch.java:34-48.
 */
public record PrescriptionBatchDto(

        String uid,
        String prescriptionUid,
        String no,
        LocalDate manufacturedDate,
        LocalDate expiryDate,
        BigDecimal qty,
        Instant createdAt

) {
}
