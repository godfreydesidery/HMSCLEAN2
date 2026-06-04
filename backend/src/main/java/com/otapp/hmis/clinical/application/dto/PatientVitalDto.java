package com.otapp.hmis.clinical.application.dto;

/**
 * Response DTO for a {@link com.otapp.hmis.clinical.domain.PatientVital} (inc-05 C5).
 *
 * <p>No internal {@code id} field — only the ULID {@code uid} is exposed (ADR-0014 §1).
 * All 11 vital fields are nullable Strings (CR-INC05-13). The {@code status} field reflects
 * the staging lifecycle: EMPTY, SUBMITTED, or ARCHIVED.
 *
 * @param uid                    ULID of the patient vital record
 * @param consultationUid        ULID of the owning consultation (null for non-consultation or admission)
 * @param nonConsultationUid     ULID of the owning non-consultation (null)
 * @param admissionUid           loose uid of the owning admission (null; DEFERRED)
 * @param businessDayUid         loose uid of the open business day at creation
 * @param status                 staging status: EMPTY, SUBMITTED, or ARCHIVED
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
public record PatientVitalDto(
        String uid,
        String consultationUid,
        String nonConsultationUid,
        String admissionUid,
        String businessDayUid,
        String status,
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
