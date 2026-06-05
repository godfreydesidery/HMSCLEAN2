package com.otapp.hmis.inpatient.application.dto;

/**
 * Request body for saving a nursing care plan entry
 * (POST …/admissions/{admissionUid}/nursing-care-plans — inc-07 07b).
 *
 * <p>Four free-text columns on {@code PatientNursingCarePlan} — NO ACTIVE/RESOLVED lifecycle,
 * NO status enum (legacy has none). {@code nurseUid} is required (enforced clinical-side).
 *
 * <p>Legacy citation: PatientNursingCarePlan.java; PatientServiceImpl.java:2593-2643.
 * inc-07 07b / AC-07B-NCP-01.
 *
 * @param nurseUid          loose uid of the charting nurse (required)
 * @param nursingDiagnosis  nursing diagnosis (free-text; nullable)
 * @param expectedOutcome   expected outcome (free-text; nullable)
 * @param implementation    implementation (free-text; nullable)
 * @param evaluation        evaluation (free-text; nullable)
 */
public record NursingCarePlanRequest(
        String nurseUid,
        String nursingDiagnosis,
        String expectedOutcome,
        String implementation,
        String evaluation
) {
}
