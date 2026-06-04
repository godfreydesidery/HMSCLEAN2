package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for saveResult (update the result text only, no status change) (C7).
 *
 * <p>Allowed when status == COLLECTED. Separate from the report field (addReport uses
 * a different endpoint/request — they are distinct fields on the LabTest row).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>PatientServiceImpl.java — saveResult path (status == COLLECTED guard)</li>
 * </ul>
 */
public record LabTestResultRequest(
        /** The result text to write / overwrite. */
        String result
) {
}
