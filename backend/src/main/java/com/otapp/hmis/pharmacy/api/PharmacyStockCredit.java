package com.otapp.hmis.pharmacy.api;

import com.otapp.hmis.shared.domain.TxAuditContext;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Published cross-module seam for crediting pharmacy stock from a transfer receipt (inc-08b chunk 6).
 *
 * <p>The store→pharmacy receiving-note (RN), owned by the {@code inventory} module, increments
 * pharmacy stock and creates a destination {@code PharmacyMedicineBatch} when the pharmacy receives
 * transferred goods (StoreToPharmacyRNServiceImpl.java:201-242). Pharmacy stock lives in the
 * {@code pharmacy} module, so inventory calls DOWN into {@code pharmacy::api}. Propagation REQUIRED
 * (the caller's RN transaction) — atomic with the RN COMPLETED flip. The edge is
 * {@code inventory → pharmacy::api}; no reverse edge, no cycle.
 */
public interface PharmacyStockCredit {

    /**
     * Credit a transferred lot into a pharmacy: increment the aggregate, write a TRANSFER_IN
     * stock-card row (when qty&gt;0), and create one destination {@code PharmacyMedicineBatch}
     * carrying the transferred lot's identity (no/dates) and qty.
     *
     * @param pharmacyUid the receiving pharmacy
     * @param medicineUid the medicine
     * @param batchNo     the transferred lot number (manually entered on the TO, carried through)
     * @param manufacturedDate the lot manufacture date (nullable)
     * @param expiryDate  the lot expiry date (nullable)
     * @param qty         the received pharmacy-SKU quantity (post-coefficient)
     * @param reference   the verbatim stock-card reference ("Medicine received # &lt;rn-no&gt;")
     * @param ctx         the caller's transaction audit context
     */
    void creditTransferLot(String pharmacyUid, String medicineUid, String batchNo,
                           LocalDate manufacturedDate, LocalDate expiryDate, BigDecimal qty,
                           String reference, TxAuditContext ctx);

    /**
     * Credit a transferred quantity into a pharmacy WITHOUT creating a destination batch — increment
     * the aggregate + write a TRANSFER_IN stock-card row (when qty&gt;0) only. This reproduces the
     * legacy pharmacy↔pharmacy RN gap (Q7 baseline): the destination batch-creation block is
     * commented out in {@code PharmacyToPharmacyRNServiceImpl} (:221-232), so inter-pharmacy stock
     * loses lot/expiry traceability at the destination. Contrast {@link #creditTransferLot} (the
     * store↔pharmacy path, which DOES create destination batches). Propagation REQUIRED.
     *
     * @param pharmacyUid the receiving (requesting) pharmacy
     * @param medicineUid the medicine
     * @param qty         the received quantity (1:1, no coefficient)
     * @param reference   the verbatim stock-card reference ("Medicine received # &lt;rn-no&gt;")
     * @param ctx         the caller's transaction audit context
     */
    void creditTransferAggregate(String pharmacyUid, String medicineUid, BigDecimal qty,
                                 String reference, TxAuditContext ctx);

    /**
     * Debit a pharmacy's stock for an outbound transfer (the source/delivering side of a
     * pharmacy↔pharmacy transfer at TO.issue). Hard negative-stock gate, decrement the aggregate,
     * FEFO over the source batches, write a TRANSFER_OUT stock-card row
     * (PharmacyToPharmacyTOServiceImpl.java:242-280). Propagation REQUIRED.
     *
     * @param pharmacyUid the delivering (source) pharmacy
     * @param medicineUid the medicine
     * @param qty         the transferred quantity (1:1)
     * @param reference   the verbatim stock-card reference ("Goods transfered to Pharmacy PTPO# ...")
     * @param ctx         the caller's transaction audit context
     * @throws com.otapp.hmis.shared.error.InsufficientStockException if source stock &lt; qty
     */
    void debitTransferOut(String pharmacyUid, String medicineUid, BigDecimal qty,
                          String reference, TxAuditContext ctx);
}
