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
}
