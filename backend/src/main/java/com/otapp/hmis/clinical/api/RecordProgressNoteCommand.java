package com.otapp.hmis.clinical.api;

/**
 * Write command for a {@code PatientNursingProgressNote} entry (inc-07 07b, ADR-0008 §1).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1).
 * Passed by the {@code inpatient} module into {@link NursingChartPort#recordProgressNote}.
 *
 * <p>Legacy citation: PatientNursingProgressNote.java:38; PatientServiceImpl.java:2647-2698.
 * inc-07 07b / AC-07B-NPR-01.
 *
 * @param admissionUid loose uid of the owning admission (non-null for inpatient path)
 * @param patientUid   loose uid of the patient
 * @param nurseUid     loose uid of the nurse (required — enforced clinical-side)
 * @param contextType  one of "ADMISSION", "CONSULTATION", "NON_CONSULTATION"
 * @param note         progress note free-text (nullable)
 */
public record RecordProgressNoteCommand(
        String admissionUid,
        String patientUid,
        String nurseUid,
        String contextType,
        String note
) {
}
