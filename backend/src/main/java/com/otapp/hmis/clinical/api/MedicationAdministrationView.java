package com.otapp.hmis.clinical.api;

import java.time.Instant;

/**
 * Immutable cross-module projection of a {@code MedicationAdministration} (MAR) record
 * (inc-07 07d, CR-07-MAR, ADR-0008 §1).
 *
 * <p>The ONLY representation of a MAR entry that modules outside {@code clinical} may consume —
 * no entity type leaks across the boundary.
 *
 * @param uid             the MAR record's public ULID
 * @param prescriptionUid loose uid of the parent prescription
 * @param admissionUid    loose uid of the admission
 * @param nurseUid        loose uid of the administering nurse
 * @param routeUid        loose uid of the administration route
 * @param administeredAt  the administration instant
 * @param doseGiven       dose given (free-text; may be null)
 * @param patientResponse observed patient response (free-text; may be null)
 * @param createdAt       audit creation instant
 */
public record MedicationAdministrationView(
        String uid,
        String prescriptionUid,
        String admissionUid,
        String nurseUid,
        String routeUid,
        Instant administeredAt,
        String doseGiven,
        String patientResponse,
        Instant createdAt
) {
}
