package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code Pharmacy} catalog (inc-08).
 *
 * <p>Part of the {@code masterdata :: lookup} named interface — the ONLY masterdata types other
 * modules may reference (ADR-0008 §1). The {@code pharmacy} module uses this to server-validate the
 * required {@code pharmacyUid} stock-source selector (Q2): a missing/unresolvable pharmacy is
 * rejected before any stock effect. NO business data crosses the boundary — existence by uid only.
 *
 * <p>Implementation is package-private in {@code masterdata.application}.
 */
public interface PharmacyLookup {

    /**
     * @param uid the ULID of the pharmacy
     * @return {@code true} if a pharmacy with that uid exists
     */
    boolean existsByUid(String uid);
}
