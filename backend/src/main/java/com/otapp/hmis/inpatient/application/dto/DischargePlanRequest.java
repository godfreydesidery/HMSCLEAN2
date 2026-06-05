package com.otapp.hmis.inpatient.application.dto;

/**
 * Request body for saving a discharge plan (POST …/discharge-plan — inc-07 07a-3).
 *
 * <p>All six narrative fields are nullable (the legacy form allows partial saves).
 * No mandatory field validation beyond what the business rules require (all nullable).
 *
 * <p>Legacy citation: PatientResource.java:5342-5390 — get_discharge_summary save body.
 *
 * @param history              patient history narrative
 * @param investigation        investigation summary
 * @param management           management/treatment summary
 * @param operationNote        operation note
 * @param icuAdmissionNote     ICU admission note
 * @param generalRecommendation general recommendation
 */
public record DischargePlanRequest(
        String history,
        String investigation,
        String management,
        String operationNote,
        String icuAdmissionNote,
        String generalRecommendation
) {
}
