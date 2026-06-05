package com.otapp.hmis.pharmacy.api;

import com.otapp.hmis.shared.domain.TxAuditContext;
import java.math.BigDecimal;

/**
 * Published cross-module seam for debiting pharmacy stock on an inpatient consumable issue
 * (inc-07 chunk 07c, CR-07-consumable-stock, ADR-0008 §1).
 *
 * <p>The inpatient module uses this seam when a nurse records a consumable chart entry.
 * The stock decrement is net-new relative to the legacy system, which only billed consumables
 * and never touched pharmacy stock (billing-only path). This seam adds real stock accountability
 * for consumed medicines during admission (CR-07-consumable-stock APPROVED).
 *
 * <p>The dependency edge is one-directional: {@code inpatient → pharmacy :: api}. There is NO
 * {@code pharmacy → inpatient} edge, so no cycle is introduced. This edge is listed in
 * {@code inpatient/package-info.java} allowedDependencies.
 *
 * <p>The implementation ({@link com.otapp.hmis.pharmacy.application.PharmacyStockDebitImpl})
 * is package-private in {@code pharmacy.application}.
 *
 * <p>Mirror of {@link PharmacyStockCredit}: same Propagation.REQUIRED contract, same
 * {@link TxAuditContext} audit context, same {@link com.otapp.hmis.shared.error.InsufficientStockException}
 * on hard negative-stock gate.
 */
public interface PharmacyStockDebit {

    /**
     * Decrement a pharmacy's stock for an inpatient consumable issue.
     *
     * <p>Hard negative-stock gate ({@link com.otapp.hmis.shared.error.InsufficientStockException}
     * if aggregate stock &lt; qty). FEFO lot walk (same FEFO order as prescription dispense).
     * Appends a {@code CONSUMPTION} stock-card OUT row with the supplied reference.
     * Propagation REQUIRED (runs inside the caller's inpatient transaction).
     *
     * @param pharmacyUid the stock-source pharmacy (server-validated by the caller)
     * @param medicineUid the consumable medicine
     * @param qty         the quantity to issue (must be &gt; 0; pre-validated by the caller)
     * @param reference   the verbatim stock-card reference string
     *                    (e.g. "Consumable issued: admission &lt;uid&gt;")
     * @param ctx         the caller's transaction audit context (business day + timestamp)
     * @throws com.otapp.hmis.shared.error.NotFoundException if no aggregate row exists for
     *         (pharmacy, medicine)
     * @throws com.otapp.hmis.shared.error.InsufficientStockException if aggregate stock &lt; qty
     */
    void debitConsumableIssue(String pharmacyUid, String medicineUid, BigDecimal qty,
                              String reference, TxAuditContext ctx);

    /**
     * Restore pharmacy stock when a consumable chart is deleted within the 24-hour window
     * (CR-07-consumable-stock — the reversal side of {@link #debitConsumableIssue}).
     *
     * <p>Increments the aggregate stock and appends a {@code CONSUMPTION_REVERSAL} stock-card IN
     * row. No new batch is created — aggregate-only increment (the consumed lots cannot be
     * meaningfully un-walked after the fact; the aggregate balance is the correct target).
     * Propagation REQUIRED (runs inside the caller's inpatient delete transaction).
     *
     * <p>Skips the row entirely when {@code qty &lt;= 0} (defensive; the caller guarantees
     * qty &gt; 0 but the seam should be safe against edge cases).
     *
     * @param pharmacyUid the stock-source pharmacy
     * @param medicineUid the consumable medicine
     * @param qty         the quantity to restore (same qty as was issued)
     * @param reference   the verbatim stock-card reference string
     *                    (e.g. "Consumable issue reversed: admission &lt;uid&gt;")
     * @param ctx         the caller's transaction audit context
     * @throws com.otapp.hmis.shared.error.NotFoundException if no aggregate row exists for
     *         (pharmacy, medicine) — which would be unusual given stock was just decremented
     */
    void restoreConsumableIssue(String pharmacyUid, String medicineUid, BigDecimal qty,
                                String reference, TxAuditContext ctx);
}
