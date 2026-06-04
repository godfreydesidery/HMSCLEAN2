package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code ProcedureType} catalog.
 *
 * <p>This interface is part of the {@code masterdata :: lookup} named interface (Spring
 * Modulith, ADR-0001, ADR-0008) — the ONLY types from the {@code masterdata} module that
 * other modules may reference. The implementation is package-private in
 * {@code masterdata.application}.
 *
 * <p>The clinical module uses this during {@code ProcedureService.order} to verify that the
 * caller-supplied {@code procedureTypeUid} resolves to a known entry in the catalog before
 * persisting the procedure order row. The verbatim legacy error message on failure is:
 * {@code "Procedure type not found"} (mirroring the LabTestType / RadiologyType not-found pattern).
 *
 * <p>Existence is checked by uid (the public ULID). No business data is returned — the
 * clinical module never needs to read ProcedureType fields, only to confirm the uid resolves.
 *
 * <p>Mirrors the pattern of {@link LabTestTypeLookup} and {@link RadiologyTypeLookup}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>ProcedureType mandatory ref: Procedure.java:85-88</li>
 *   <li>PatientServiceImpl.java — procedureType != null guard before persist</li>
 * </ul>
 */
public interface ProcedureTypeLookup {

    /**
     * Return {@code true} if a ProcedureType with the given uid exists in the catalog.
     *
     * @param uid the ULID of the procedure type
     * @return {@code true} if found; {@code false} otherwise
     */
    boolean existsByUid(String uid);
}
