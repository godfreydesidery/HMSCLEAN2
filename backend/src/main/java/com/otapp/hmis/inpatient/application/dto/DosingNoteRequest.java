package com.otapp.hmis.inpatient.application.dto;

/**
 * Request body for saving a free-text dosing note against a GIVEN prescription
 * (POST …/admissions/{admissionUid}/dosing-notes — inc-07 07b).
 *
 * <p>The legacy free-text {@code PatientPrescriptionChart} dosing-note path (Q1 — NOT MAR).
 * The linked prescription must be GIVEN (clinical-side guard, "Prescription not picked from
 * pharmacy"); the admission must be IN-PROCESS (inpatient-side guard). dosage/output/remark are
 * free-text — NO route, NO administeredAt, NO patientResponse (CR-07-MAR parked).
 *
 * <p>Legacy citation: PatientPrescriptionChart.java:34-82; PatientServiceImpl.java:2540-2577.
 * inc-07 07b / Q1.
 *
 * @param prescriptionUid loose uid of the parent prescription (must be GIVEN)
 * @param nurseUid        loose uid of the administering nurse (required)
 * @param dosage          administered dosage (free-text; nullable)
 * @param output          observed output (free-text; nullable)
 * @param remark          remark (free-text; nullable)
 */
public record DosingNoteRequest(
        String prescriptionUid,
        String nurseUid,
        String dosage,
        String output,
        String remark
) {
}
