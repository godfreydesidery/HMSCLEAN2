package com.otapp.hmis.registration.lookup;

/**
 * Cross-module read seam for patient lifecycle status (inc-07 07a SEAM-B, ADR-0008 §1).
 *
 * <p>This interface is the ONLY patient-status read API that modules outside {@code registration}
 * may call. The implementation ({@code PatientStatusLookupImpl}) is package-private in
 * {@code registration.application}.
 *
 * <p>Callers (primarily {@code inpatient}) depend on this interface from
 * {@code registration :: lookup}; they MUST NEVER import
 * {@code com.otapp.hmis.registration.domain} types directly (ADR-0008 §1).
 *
 * <p><strong>Deceased guard (CR-07-deceased-guard, owner-approved):</strong>
 * A DECEASED patient may not be re-admitted. This seam exposes the minimum query surface
 * needed for that guard without leaking the {@code Patient} entity or {@code PatientType} enum
 * across the module boundary.
 *
 * <p>Legacy citation: PatientResource.java:5183-5210 — the legacy system had no admit-time
 * deceased guard; the admitted-deceased gap was owner-identified as a safety risk. This seam
 * is the module-safe implementation of that owner-approved net-new hardening.
 *
 * <p>inc-07 07a SEAM-B / CR-07-deceased-guard ratified.
 */
public interface PatientStatusLookup {

    /**
     * Test whether a patient is marked DECEASED.
     *
     * <p>Returns {@code false} for an unknown patient uid (no such patient → treat as not
     * deceased, letting downstream guards handle existence separately — see inpatient service
     * javadoc for the guard-order rationale).
     *
     * @param patientUid the public ULID of the patient
     * @return {@code true} if the patient's {@code type == DECEASED}; {@code false} otherwise
     *         (including when the patient is not found)
     */
    boolean isDeceased(String patientUid);
}
