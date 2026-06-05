package com.otapp.hmis.clinical.api;

/**
 * Write command passed by the {@code inpatient} module into {@link PrescriptionChartPort#record}
 * (inc-07 SEAM-2, ADR-0008 §1).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1).
 * The record is immutable; the inpatient module constructs it and passes it in-process inside
 * the caller's transaction.
 *
 * <p>The write guards that validate this command (prescription GIVEN check at
 * PatientServiceImpl.java:2544; exactly-one-encounter check; nurse-uid present check at
 * PatientServiceImpl.java:2564-2577) are enforced clinical-side inside
 * {@code PrescriptionChartPortImpl} — built in inc-07 chunk 07b. The command fields here are
 * the minimal set the inpatient module can supply; validation lives with the owning module.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>GIVEN guard: PatientServiceImpl.java:2544</li>
 *   <li>admission + nurse guard: PatientServiceImpl.java:2564-2577</li>
 *   <li>dosing free-text fields: PatientPrescriptionChart.java:57-75</li>
 * </ul>
 *
 * <p>inc-07 SEAM-2 / ADR-0008 §1.
 *
 * @param prescriptionUid loose uid of the parent prescription (must be GIVEN —
 *                        guard enforced in chunk 07b)
 * @param admissionUid    loose uid of the admission binding this chart entry
 *                        (PatientPrescriptionChart.java:111, nullable VARCHAR 26)
 * @param nurseUid        loose uid of the nurse administering the drug
 *                        (PatientPrescriptionChart.java:138; required for inpatient path —
 *                        enforced in chunk 07b)
 * @param dosage          administered dosage free-text (VARCHAR 200; may be null)
 * @param output          observed output free-text (VARCHAR 200; may be null)
 * @param remark          remark free-text (TEXT; may be null)
 */
public record RecordPrescriptionChartCommand(
        String prescriptionUid,
        String admissionUid,
        String nurseUid,
        String dosage,
        String output,
        String remark
) {
}
