package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code Supplier} catalog (inc-08b). Part of the
 * {@code masterdata :: lookup} named interface (ADR-0008 §1). Existence by uid only.
 */
public interface SupplierLookup {

    /**
     * @param uid the ULID of the supplier
     * @return {@code true} if a supplier with that uid exists
     */
    boolean existsByUid(String uid);
}
