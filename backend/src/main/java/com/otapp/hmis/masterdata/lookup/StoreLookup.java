package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code Store} catalog (inc-08, 08b).
 *
 * <p>Part of the {@code masterdata :: lookup} named interface (ADR-0008 §1). The {@code inventory}
 * /{@code pharmacy} transfer + procurement paths use this to validate a store uid. Existence by uid
 * only; implementation package-private in {@code masterdata.application}.
 */
public interface StoreLookup {

    /**
     * @param uid the ULID of the store
     * @return {@code true} if a store with that uid exists
     */
    boolean existsByUid(String uid);
}
