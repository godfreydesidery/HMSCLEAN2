package com.otapp.hmis.clinical.api;

/**
 * Write command for a {@code PatientNursingCarePlan} entry (inc-07 07b, ADR-0008 §1).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1).
 * Passed by the {@code inpatient} module into
 * {@link NursingChartPort#recordNursingCarePlan}.
 *
 * <p>Legacy citation: PatientNursingCarePlan.java:38-41; PatientServiceImpl.java:2593-2643.
 * inc-07 07b / AC-07B-NCP-01.
 *
 * @param admissionUid     loose uid of the owning admission (non-null for inpatient path)
 * @param patientUid       loose uid of the patient
 * @param nurseUid         loose uid of the nurse (required — enforced clinical-side)
 * @param contextType      one of "ADMISSION", "CONSULTATION", "NON_CONSULTATION"
 * @param nursingDiagnosis nursing diagnosis free-text (nullable)
 * @param expectedOutcome  expected outcome free-text (nullable)
 * @param implementation   implementation free-text (nullable)
 * @param evaluation       evaluation free-text (nullable)
 */
public record RecordNursingCarePlanCommand(
        String admissionUid,
        String patientUid,
        String nurseUid,
        String contextType,
        String nursingDiagnosis,
        String expectedOutcome,
        String implementation,
        String evaluation
) {
}
