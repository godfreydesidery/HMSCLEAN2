package com.otapp.hmis.inpatient.application.dto;

/**
 * Request body for saving a nursing observation chart entry
 * (POST …/admissions/{admissionUid}/nursing-charts — inc-07 07b).
 *
 * <p>The eight observation fields are free-text columns on {@code PatientNursingChart}
 * (NursingChart.java:38-45 — NOT separate FluidBalance/CareActivity entities). The
 * {@code admissionUid} comes from the path; {@code patientUid} is resolved from the admission.
 *
 * <p>Legacy citation: PatientNursingChart.java:38-45; PatientServiceImpl.java:2593-2643.
 * inc-07 07b / AC-07B-NCA-01.
 *
 * @param nurseUid         loose uid of the charting nurse (required — enforced clinical-side)
 * @param feeding          feeding observation (free-text; nullable)
 * @param changingPosition body-position change observation (free-text; nullable)
 * @param bedBathing       bed bathing observation (free-text; nullable)
 * @param randomBloodSugar random blood sugar reading (free-text; nullable)
 * @param fullBloodSugar   full blood sugar reading (free-text; nullable)
 * @param drainageOutput   drainage output (free-text; nullable)
 * @param fluidIntake      fluid intake (free-text; nullable)
 * @param urineOutput      urine output (free-text; nullable)
 */
public record NursingChartRequest(
        String nurseUid,
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
