package com.otapp.hmis.clinical.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Published command the {@code pharmacy} module passes to
 * {@link PrescriptionDispensePort#markDispensed} after it has performed its OWN stock decrement /
 * FEFO / stock-card write (inc-08a, AC-RX-PRE-06/08). Mirror of the application-internal
 * {@code IssueRequest} with {@code issuePharmacyUid} <strong>required</strong> — the pharmacy has
 * already resolved and server-validated it (Q2), so this seam stamps it directly.
 *
 * <p>{@code lotTrace} carries the lots the pharmacy FEFO-consumed, persisted as
 * {@code PrescriptionBatch} rows for the clinical issue path (no lot-trace is dropped — RECONCILIATION N9).
 *
 * @param issued           the dispensed quantity (must equal the full prescribed qty — all-or-nothing)
 * @param issuePharmacyUid the server-validated pharmacy the stock was pulled from (required)
 * @param lotTrace         the lots consumed (may be empty); each becomes a PrescriptionBatch row
 */
public record DispenseConfirmation(
        BigDecimal issued,
        String issuePharmacyUid,
        List<LotTrace> lotTrace
) {

    /**
     * One consumed-lot record for clinical lot-traceability.
     *
     * @param batchNo          the lot/batch number consumed
     * @param manufacturedDate the lot manufacture date (nullable)
     * @param expiryDate       the lot expiry date (nullable — legacy permits null-expiry lots)
     * @param qty              the quantity drawn from this lot
     */
    public record LotTrace(
            String batchNo,
            LocalDate manufacturedDate,
            LocalDate expiryDate,
            BigDecimal qty
    ) {
    }
}
