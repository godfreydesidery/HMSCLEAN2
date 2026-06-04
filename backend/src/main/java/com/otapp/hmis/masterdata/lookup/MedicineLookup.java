package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code Medicine} catalog.
 *
 * <p>This interface is part of the {@code masterdata :: lookup} named interface (Spring
 * Modulith, ADR-0001, ADR-0008) — the ONLY types from the {@code masterdata} module that
 * other modules may reference. The implementation is package-private in
 * {@code masterdata.application}.
 *
 * <p>The clinical module uses this during {@code PrescriptionService.prescribe} to verify
 * that the caller-supplied {@code medicineUid} resolves to a known entry in the medicine
 * catalog before persisting the prescription row.
 *
 * <p>Existence is checked by uid (the public ULID). No business data is returned — the
 * clinical module never needs to read Medicine fields directly, only to confirm the uid
 * resolves (ADR-0008 §1: cross-module refs are loose uids only).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Medicine mandatory ref: Prescription.java:72-75</li>
 *   <li>Medicine existence guard before persist: PatientServiceImpl.java (save_prescription)</li>
 * </ul>
 */
public interface MedicineLookup {

    /**
     * Return {@code true} if a Medicine with the given uid exists in the catalog.
     *
     * @param uid the ULID of the medicine
     * @return {@code true} if found; {@code false} otherwise
     */
    boolean existsByUid(String uid);
}
