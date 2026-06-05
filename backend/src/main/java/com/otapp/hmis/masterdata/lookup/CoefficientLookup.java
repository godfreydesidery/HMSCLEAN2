package com.otapp.hmis.masterdata.lookup;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Cross-module conversion-coefficient lookup for the pharmacy↔store transfer (inc-08b). Part of the
 * {@code masterdata :: lookup} named interface (ADR-0008 §1).
 *
 * <p>The store→pharmacy transfer converts {@code pharmacySKUQty = storeSKUQty * coefficient} and
 * hard-fails if no coefficient exists for the (item, medicine) pair
 * (StoreToPharmacyTOServiceImpl.java:417-424). The coefficient is {@code NUMERIC(19,6)} (ADR-0009 §3),
 * applied with full BigDecimal precision (no intermediate rounding).
 */
public interface CoefficientLookup {

    /**
     * @param itemUid     the ULID of the store item (source SKU)
     * @param medicineUid the ULID of the pharmacy medicine (target SKU)
     * @return the conversion coefficient, or empty if no coefficient row exists for the pair
     */
    Optional<BigDecimal> coefficientFor(String itemUid, String medicineUid);
}
