package com.otapp.hmis.clinical.api;

/**
 * Response DTO for a {@link com.otapp.hmis.clinical.domain.NonConsultation} (inc-05 C4).
 *
 * <p>No internal {@code id} field — only the ULID {@code uid} is exposed (ADR-0014 §1).
 * Cross-module refs ({@code patientUid}, {@code visitUid}, {@code insurancePlanUid}) are opaque
 * ULID strings; no registration or masterdata entities are imported.
 *
 * <p>{@code paymentType} is a {@code String} (not {@code PaymentMode}) because the V21 CHECK
 * permits {@code ''} (empty string) in addition to {@code CASH} and {@code INSURANCE}.
 * Using {@code PaymentMode} enum would have no representation for {@code ''}. See
 * {@link com.otapp.hmis.clinical.domain.NonConsultation} Javadoc for the full design rationale.
 *
 * <p>{@code status} is the exact DB-spelling string (e.g. {@code "IN-PROCESS"}, {@code "SIGNED-OUT"})
 * to match the legacy observable output.
 *
 * @param uid              ULID of the non-consultation
 * @param patientUid       loose uid of the patient (registration module)
 * @param visitUid         loose uid of the associated visit (registration module)
 * @param paymentType      payment type string: CASH, INSURANCE, or '' (empty default)
 * @param membershipNo     insurance membership number (empty for CASH)
 * @param status           lifecycle status db-value string (IN-PROCESS or SIGNED-OUT)
 * @param insurancePlanUid loose uid of the insurance plan (null for CASH)
 * @param businessDayUid   loose uid of the business day at creation
 */
public record NonConsultationDto(
        String uid,
        String patientUid,
        String visitUid,
        String paymentType,
        String membershipNo,
        String status,
        String insurancePlanUid,
        String businessDayUid
) {
}
