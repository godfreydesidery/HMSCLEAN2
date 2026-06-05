package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module check: is a Medicine registered in the consumables masterdata?
 *
 * <p>Published as part of {@code masterdata :: lookup} named interface (Spring Modulith,
 * ADR-0008 §1). The implementation is package-private in {@code masterdata.application}.
 *
 * <p>The inpatient module uses this to enforce the consumable-registered guard at chart-save
 * time: if the Medicine is not listed as a consumable, the service rejects with the verbatim
 * legacy message "Medicine is not listed as consumable" (422, PatientServiceImpl.java:2260-2262).
 *
 * <p>Mirrors {@link DressingLookup} exactly (same pattern, different masterdata entity).
 *
 * <p>Legacy citation: Consumable.java (domain/Consumable.java), ConsumableRepository.java,
 * PatientServiceImpl.java:2259-2262.
 * inc-07 07c.
 */
public interface ConsumableLookup {

    /**
     * Return {@code true} if the given Medicine uid is registered in the consumables catalog.
     *
     * @param medicineUid the loose uid of the medicine
     * @return {@code true} if the medicine is listed as a consumable; {@code false} otherwise
     */
    boolean isConsumable(String medicineUid);
}
