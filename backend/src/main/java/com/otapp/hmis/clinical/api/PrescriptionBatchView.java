package com.otapp.hmis.clinical.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Published read-only lot-trace projection of a {@code clinical} PrescriptionBatch, consumed by the
 * {@code pharmacy} module (inc-08a, AC-RX-PRE-08). Mirror of {@code PrescriptionBatchDto} minus the
 * internal {@code id} (ADR-0014 §1). These rows record which lot/qty satisfied a dispensed line
 * (PatientResource.java:3296-3336 — clinical {@code deductBatch} writes PrescriptionBatch trace rows).
 */
public record PrescriptionBatchView(

        String uid,
        String prescriptionUid,
        String no,
        LocalDate manufacturedDate,
        LocalDate expiryDate,
        BigDecimal qty,
        Instant createdAt
) {
}
