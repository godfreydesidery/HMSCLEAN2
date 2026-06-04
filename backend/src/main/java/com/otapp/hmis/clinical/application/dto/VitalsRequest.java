package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for the nurse vitals-submit endpoint (inc-05 C5).
 *
 * <p>Carries the same 11 free-text vital-sign fields as {@link GeneralExaminationRequest}.
 * Submitted by the nurse via {@code POST /consultations/uid/{uid}/vitals}; triggers a
 * transition from EMPTY → SUBMITTED on the {@link com.otapp.hmis.clinical.domain.PatientVital}.
 *
 * <p>All fields nullable — the legacy PatientVital has no mandatory vital fields.
 *
 * <p>Legacy citation: PatientVital.java:23-67; PatientResource.java:1321 (nurse submit flow).
 *
 * @param pressure               blood pressure (free text)
 * @param temperature            body temperature (free text)
 * @param pulseRate              pulse rate (free text)
 * @param weight                 body weight (free text)
 * @param height                 body height (free text)
 * @param bodyMassIndex          BMI (free text — NOT computed server-side, CR-INC05-13)
 * @param bodyMassIndexComment   BMI classification comment
 * @param bodySurfaceArea        body surface area (free text — NOT computed server-side)
 * @param saturationOxygen       oxygen saturation (free text)
 * @param respiratoryRate        respiratory rate (free text)
 * @param description            additional notes
 */
public record VitalsRequest(
        String pressure,
        String temperature,
        String pulseRate,
        String weight,
        String height,
        String bodyMassIndex,
        String bodyMassIndexComment,
        String bodySurfaceArea,
        String saturationOxygen,
        String respiratoryRate,
        String description
) {
}
