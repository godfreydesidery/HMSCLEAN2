package com.otapp.hmis.clinical.api;

/**
 * Response DTO for a {@link com.otapp.hmis.clinical.domain.ConsultationTransfer}.
 *
 * <p>Published in {@code clinical.api} (named interface "api") so that the completion seam
 * return type is accessible to modules that call clinical::api. No internal {@code id} field —
 * only the ULID {@code uid} is exposed (ADR-0014 §1).
 *
 * <p>Legacy citations: ConsultationTransfer.java:35-65; PatientResource.java:599.
 *
 * @param uid                  ULID of the transfer
 * @param status               transfer lifecycle status string (PENDING / COMPLETED / CANCELED)
 * @param consultationUid      ULID of the source consultation
 * @param patientUid           loose uid of the patient (registration module)
 * @param destinationClinicUid loose uid of the destination clinic (masterdata module)
 * @param reason               free-text rationale (nullable)
 * @param businessDayUid       loose uid of the business day at creation time
 */
public record ConsultationTransferDto(
        String uid,
        String status,
        String consultationUid,
        String patientUid,
        String destinationClinicUid,
        String reason,
        String businessDayUid
) {
}
