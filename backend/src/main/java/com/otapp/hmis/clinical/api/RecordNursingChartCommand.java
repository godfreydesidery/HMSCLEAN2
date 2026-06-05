package com.otapp.hmis.clinical.api;

/**
 * Write command for a {@code PatientNursingChart} entry (inc-07 07b, ADR-0008 §1).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1).
 * Passed by the {@code inpatient} module into {@link NursingChartPort#recordNursingChart}.
 *
 * <p>The admission-IN-PROCESS gate is evaluated INPATIENT-SIDE before this command is
 * constructed and passed into the port (build spec §3.4 guard split).
 * The nurse-uid present guard and context-exclusivity guard are enforced clinical-side.
 *
 * <p>Legacy citation: PatientNursingChart.java:38-45; PatientServiceImpl.java:2593-2643.
 * inc-07 07b / AC-07B-NCA-01.
 *
 * @param admissionUid     loose uid of the owning admission (non-null for inpatient path)
 * @param patientUid       loose uid of the patient
 * @param nurseUid         loose uid of the nurse (required — enforced clinical-side)
 * @param contextType      one of "ADMISSION", "CONSULTATION", "NON_CONSULTATION" — used
 *                         by the clinical port to enforce the context-exclusivity guard
 *                         verbatim (PatientServiceImpl.java:2616-2619)
 * @param feeding          feeding observation (free-text; nullable)
 * @param changingPosition body-position change observation (free-text; nullable)
 * @param bedBathing       bed bathing observation (free-text; nullable)
 * @param randomBloodSugar random blood sugar reading (free-text; nullable)
 * @param fullBloodSugar   full blood sugar reading (free-text; nullable)
 * @param drainageOutput   drainage output (free-text; nullable)
 * @param fluidIntake      fluid intake (free-text; nullable)
 * @param urineOutput      urine output (free-text; nullable)
 */
public record RecordNursingChartCommand(
        String admissionUid,
        String patientUid,
        String nurseUid,
        String contextType,
        String feeding,
        String changingPosition,
        String bedBathing,
        String randomBloodSugar,
        String fullBloodSugar,
        String drainageOutput,
        String fluidIntake,
        String urineOutput
) {
}
