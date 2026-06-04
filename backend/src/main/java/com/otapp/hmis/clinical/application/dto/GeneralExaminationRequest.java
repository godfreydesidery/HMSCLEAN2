package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for saving (upserting) a general examination / vital-sign record (inc-05 C5).
 *
 * <p>All 11 vital-sign fields are nullable Strings — the legacy has no numeric typing,
 * no range validation, and no server-side BMI/BSA computation (CR-INC05-13 REJECT,
 * 11-DECISIONS-RATIFIED.md §2). BMI and BSA arrive as free text and are stored verbatim.
 *
 * <p>Legacy citation: PatientResource.java:1469-1598 (saveCG UPSERT — GeneralExamination fields;
 * GeneralExamination.java:42-51 free-text vital fields).
 *
 * @param pressure               blood pressure (free text, e.g. "120/80")
 * @param temperature            body temperature (free text)
 * @param pulseRate              pulse rate (free text)
 * @param weight                 body weight (free text)
 * @param height                 body height (free text)
 * @param bodyMassIndex          BMI (free text — NOT computed server-side)
 * @param bodyMassIndexComment   BMI classification comment
 * @param bodySurfaceArea        body surface area (free text — NOT computed server-side)
 * @param saturationOxygen       oxygen saturation (free text)
 * @param respiratoryRate        respiratory rate (free text)
 * @param description            additional examination notes (max 1000 chars per V23)
 */
public record GeneralExaminationRequest(
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
