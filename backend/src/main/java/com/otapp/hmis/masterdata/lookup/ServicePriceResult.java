package com.otapp.hmis.masterdata.lookup;

import java.math.BigDecimal;

/**
 * Projection returned by {@link PriceLookup#resolve} (build-spec §2.2).
 *
 * <p>This record is the cross-module contract consumed by billing, clinical, and pharmacy
 * increments. It carries exactly the fields that the consuming code needs to build a
 * {@code PatientBill} line item:
 * <ul>
 *   <li>{@code amount} — the price to charge (CASH or plan-covered).</li>
 *   <li>{@code covered} — TRUE when an insurance plan covers this service
 *       (drives the {@code COVERED} bill status in billing logic).</li>
 *   <li>{@code planUid} — the plan uid that resolved, or {@code null} for cash.</li>
 *   <li>{@code kind} — the service category.</li>
 *   <li>{@code serviceUid} — the service entity uid, or {@code null} for REGISTRATION.</li>
 *   <li>{@code currency} — ISO 4217 code (NET-NEW; inert — CR-11).</li>
 *   <li>{@code minAmount}/{@code maxAmount} — optional bounds (NET-NEW; inert — CR-11).</li>
 * </ul>
 *
 * <p><b>Important:</b> {@code minAmount}, {@code maxAmount}, and {@code currency} are stored
 * and passed through but MUST NOT drive any behaviour in the consuming billing code. They
 * are present only to allow future CRs to activate them without an API change.
 */
public record ServicePriceResult(
        BigDecimal amount,
        boolean covered,
        String planUid,
        ServiceKind kind,
        String serviceUid,
        String currency,
        BigDecimal minAmount,
        BigDecimal maxAmount
) {
}
