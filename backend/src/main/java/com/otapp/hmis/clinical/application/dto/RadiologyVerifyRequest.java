package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for verifying a radiology order result (ACCEPTED → VERIFIED) (C8).
 *
 * <p>The verify transition goes ACCEPTED → VERIFIED DIRECTLY (PatientResource.java:4280-4281).
 * There is no collect step for radiology (CR-INC05-14).
 *
 * <p>Result fields are radiology-specific: result + report + optional inline attachment blob.
 * NO range/level/unit — those are lab-specific.
 *
 * <p>The {@code attachment} field carries the inline image blob (Radiology.java:50). It is
 * optional — not all verifications include an image. If supplied, it is stored in the
 * {@code attachment BYTEA} column directly on the radiology row.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Radiology.java:50 (inline Byte[] attachment set at verify)</li>
 *   <li>PatientResource.java:4280-4292 (verify path from ACCEPTED)</li>
 * </ul>
 */
public record RadiologyVerifyRequest(
        /** The examination result value (free text). */
        String result,

        /**
         * Full report / interpretation text (TEXT, legacy length 10000).
         * A separate field from {@code result}.
         */
        String report,

        /**
         * Optional inline image/report blob (mapped to {@code attachment BYTEA} on the row).
         * Nullable — not all verifications include a binary attachment.
         */
        byte[] attachment
) {
}
