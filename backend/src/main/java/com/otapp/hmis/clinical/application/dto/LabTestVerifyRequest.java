package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for verifying a lab test result (COLLECTED → VERIFIED) (C7).
 *
 * <p>All result fields (result, level, testRange, unit) are written at verify time.
 * All are nullable — the legacy allows partial result entry (not all tests have all fields).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>LabTest.java:51-52 (result columns including rrange)</li>
 *   <li>PatientResource.java:3965-3980 (verify path writes result fields)</li>
 * </ul>
 */
public record LabTestVerifyRequest(
        /** The test result value (free text). */
        String result,

        /** Result level indicator (e.g., HIGH / LOW / NORMAL). */
        String level,

        /**
         * Reference range for the result (mapped to {@code rrange} DB column).
         * Field name is {@code testRange} to avoid Java/SQL reserved-word issues.
         */
        String testRange,

        /** Unit of measure for the result (e.g., mg/dL). */
        String unit
) {
}
