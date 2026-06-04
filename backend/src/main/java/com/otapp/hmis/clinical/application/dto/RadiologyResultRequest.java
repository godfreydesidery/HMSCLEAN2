package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for saveResult on a radiology order (update result text only, no status change)
 * (C8).
 *
 * <p>Allowed when status == ACCEPTED (PatientResource.java:4305-4306 parity — radiology
 * edits when ACCEPTED, not COLLECTED like lab tests).
 */
public record RadiologyResultRequest(
        /** The result text to write / overwrite. */
        String result
) {
}
