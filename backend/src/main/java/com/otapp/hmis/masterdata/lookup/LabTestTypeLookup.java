package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code LabTestType} catalog.
 *
 * <p>This interface is part of the {@code masterdata :: lookup} named interface (Spring
 * Modulith, ADR-0001, ADR-0008) — the ONLY types from the {@code masterdata} module that
 * other modules may reference. The implementation is package-private in
 * {@code masterdata.application}.
 *
 * <p>The clinical module uses this during {@code LabTestService.order} to verify that the
 * caller-supplied {@code labTestTypeUid} resolves to a known entry in the catalog before
 * persisting the lab test order row. The verbatim legacy error message on failure is:
 * {@code "Lab test type not found"} (mirroring the DiagnosisType not-found pattern from
 * PatientResource.java:1659).
 *
 * <p>Existence is checked by uid (the public ULID). No business data is returned — the
 * clinical module never needs to read LabTestType fields, only to confirm the uid resolves.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>LabTestType mandatory ref: LabTest.java:80-83</li>
 *   <li>PatientServiceImpl.java:820 (labTestType != null guard before persist)</li>
 * </ul>
 */
public interface LabTestTypeLookup {

    /**
     * Return {@code true} if a LabTestType with the given uid exists in the catalog.
     *
     * @param uid the ULID of the lab test type
     * @return {@code true} if found; {@code false} otherwise
     */
    boolean existsByUid(String uid);
}
