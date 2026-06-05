package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code Item} catalog (inc-08b). Part of the
 * {@code masterdata :: lookup} named interface (ADR-0008 §1). Used by procurement/transfer paths to
 * validate an item uid. Existence by uid only; impl package-private in {@code masterdata.application}.
 */
public interface ItemLookup {

    /**
     * @param uid the ULID of the item
     * @return {@code true} if an item with that uid exists
     */
    boolean existsByUid(String uid);
}
