package com.otapp.hmis.clinical.application.dto;

/**
 * Request body for the radiology stand-alone {@code add_report} (inc-06A C5 / ITEM2).
 *
 * <p>Writes ONLY the {@code report} field on the radiology row, gated on the BILL status
 * ({@code PAID|COVERED|VERIFIED}) and independent of order status — reproducing legacy
 * {@code radiologies/add_report} (PatientResource.java:3183-3197).
 */
public record RadiologyReportRequest(
        /** The radiologist report text to write / overwrite. */
        String report
) {
}
