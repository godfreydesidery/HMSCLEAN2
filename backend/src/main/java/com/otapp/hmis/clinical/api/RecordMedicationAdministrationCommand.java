package com.otapp.hmis.clinical.api;

import java.time.Instant;

/**
 * Write command passed by the {@code inpatient} module into
 * {@link MedicationAdministrationPort#record} (inc-07 07d, CR-07-MAR, ADR-0008 §1).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1). The record is
 * immutable; the inpatient module constructs it and passes it in-process inside the caller's
 * transaction.
 *
 * <p>Guard split: the inpatient module validates the admission IN-PROCESS gate and that
 * {@code routeUid} is a registered ACTIVE route (RouteLookup) before calling; the clinical port
 * validates the prescription exists + is GIVEN and that {@code nurseUid} is present.
 *
 * @param prescriptionUid loose uid of the parent prescription (must be GIVEN)
 * @param admissionUid    loose uid of the admission binding this MAR entry
 * @param nurseUid        loose uid of the administering nurse (required)
 * @param routeUid        loose uid of the administration route (must be a registered ACTIVE route)
 * @param administeredAt  the instant the medication was administered
 * @param doseGiven       dose-given free-text (may be null)
 * @param patientResponse observed patient-response free-text (may be null)
 */
public record RecordMedicationAdministrationCommand(
        String prescriptionUid,
        String admissionUid,
        String nurseUid,
        String routeUid,
        Instant administeredAt,
        String doseGiven,
        String patientResponse
) {
}
