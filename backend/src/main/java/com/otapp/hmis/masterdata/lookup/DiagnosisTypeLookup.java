package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module existence check for the {@code DiagnosisType} catalog.
 *
 * <p>This interface is part of the {@code masterdata :: lookup} named interface (Spring
 * Modulith, ADR-0001, ADR-0008) — the ONLY types from the {@code masterdata} module that
 * other modules may reference. The implementation is package-private in
 * {@code masterdata.application}.
 *
 * <p>The clinical module uses this during
 * {@code DiagnosisService.addWorkingDiagnosis} / {@code addFinalDiagnosis} to verify that
 * the caller-supplied {@code diagnosisTypeUid} resolves to a known entry in the catalog
 * before persisting the diagnosis row.  The verbatim legacy error message on failure is:
 * {@code "Diagnosis type not found"} (PatientResource.java:1654-1678 / :1782).
 *
 * <p>Existence is checked by uid (the public ULID). No business data is returned — the
 * clinical module never needs to read DiagnosisType fields, only to confirm the uid resolves.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Diagnosis-type mandatory ref: WorkingDiagnosis.java:48-53; FinalDiagnosis.java:51-56</li>
 *   <li>NotFound guard: PatientResource.java:1659 (diagnosisType != null check → 404-equivalent)</li>
 * </ul>
 */
public interface DiagnosisTypeLookup {

    /**
     * Return {@code true} if a DiagnosisType with the given uid exists in the catalog.
     *
     * @param uid the ULID of the diagnosis type
     * @return {@code true} if found; {@code false} otherwise
     */
    boolean existsByUid(String uid);
}
