package com.otapp.hmis.masterdata.lookup;

import java.math.BigDecimal;

/**
 * Cross-module price lookup for the {@code Medicine} cash dispensing price (OTC flat-cash path).
 *
 * <p>Part of the {@code masterdata :: lookup} named interface (Spring Modulith, ADR-0008 §1).
 * Callers (pharmacy module OTC path) use this to obtain {@code medicines.price} for flat-cash bill
 * construction WITHOUT invoking the plan-pricing engine (Q9 / CR-09-NUM1 ratified: OTC bills use
 * {@code Medicine.price} directly, NOT the {@code service_prices} table).
 *
 * <p>Legacy citation: PatientServiceImpl.java:3395-3442 — OTC detail creation reads
 * {@code medicine.price} and multiplies by qty to build the PatientBill amount.
 */
public interface MedicinePriceLookup {

    /**
     * Return the cash dispensing price of a medicine.
     *
     * @param medicineUid the ULID of the medicine
     * @return the cash price (NUMERIC 19,2)
     * @throws com.otapp.hmis.shared.error.NotFoundException if no medicine with that uid exists
     */
    BigDecimal priceOf(String medicineUid);
}
