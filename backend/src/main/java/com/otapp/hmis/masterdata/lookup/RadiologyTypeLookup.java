package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code RadiologyType} catalog.
 *
 * <p>This interface is part of the {@code masterdata :: lookup} named interface (Spring
 * Modulith, ADR-0001, ADR-0008) — the ONLY types from the {@code masterdata} module that
 * other modules may reference. The implementation is package-private in
 * {@code masterdata.application}.
 *
 * <p>The clinical module uses this during {@code RadiologyService.order} to verify that the
 * caller-supplied {@code radiologyTypeUid} resolves to a known entry in the catalog before
 * persisting the radiology order row. The verbatim legacy error message on failure is:
 * {@code "Radiology type not found"} (mirroring the LabTestType not-found pattern).
 *
 * <p>Existence is checked by uid (the public ULID). No business data is returned — the
 * clinical module never needs to read RadiologyType fields, only to confirm the uid resolves.
 *
 * <p>Mirrors the pattern of {@link LabTestTypeLookup}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>RadiologyType mandatory ref: Radiology.java:76-79</li>
 *   <li>PatientServiceImpl.java — radiologyType != null guard before persist</li>
 * </ul>
 */
public interface RadiologyTypeLookup {

    /**
     * Return {@code true} if a RadiologyType with the given uid exists in the catalog.
     *
     * @param uid the ULID of the radiology type
     * @return {@code true} if found; {@code false} otherwise
     */
    boolean existsByUid(String uid);
}
