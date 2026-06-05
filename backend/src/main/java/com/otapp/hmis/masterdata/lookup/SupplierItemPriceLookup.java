package com.otapp.hmis.masterdata.lookup;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Cross-module per-(supplier, item) price lookup for procurement (inc-08b). Part of the
 * {@code masterdata :: lookup} named interface (ADR-0008 §1).
 *
 * <p>LPO detail creation copies the line price from the {@code SupplierItemPrice} row and HARD-rejects
 * a (supplier, item) pair that has no price row ("Item not valid for this supplier" —
 * LocalPurchaseOrderServiceImpl.java:231-273). An empty {@link Optional} signals that absence.
 * The legacy {@code active} flag is NOT checked (reproduced verbatim).
 */
public interface SupplierItemPriceLookup {

    /**
     * @param supplierUid the ULID of the supplier
     * @param itemUid     the ULID of the item
     * @return the price for that (supplier, item) pair, or empty if no price row exists
     */
    Optional<BigDecimal> priceFor(String supplierUid, String itemUid);
}
