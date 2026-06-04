package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for addReport (update the report text only, no status change) (C7).
 *
 * <p>Allowed when status == COLLECTED. The report is a separate field from result on the
 * LabTest row (TEXT, legacy length 10000). Both can be updated independently.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>LabTest.java — report field (TEXT, distinct from result)</li>
 *   <li>PatientServiceImpl.java — addReport path (status == COLLECTED guard)</li>
 * </ul>
 */
public record LabTestReportRequest(
        /** The report text to write / overwrite. */
        String report
) {
}
