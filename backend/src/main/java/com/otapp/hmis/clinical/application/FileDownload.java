package com.otapp.hmis.clinical.application;

/**
 * Carries the file bytes and storage filename for a download response (inc-06A C7 / ITEM5).
 *
 * <p>Returned by {@link LabTestPort#downloadAttachment} and {@link RadiologyPort#downloadAttachment}
 * so that the controller can build a {@code ResponseEntity<byte[]>} with the correct
 * {@code Content-Disposition: inline; filename="<fileName>"} header without coupling the
 * service layer to the web framework.
 *
 * <p>Legacy analogue: PatientResource.java:5960-6007 (lab download),
 * PatientResource.java:6093-6140 (radiology download) — the legacy returns the bytes directly
 * from the resource method; this record decouples the service from the response-building.
 *
 * @param fileName storage filename (used to set Content-Disposition and derive Content-Type)
 * @param bytes    raw file bytes
 */
public record FileDownload(String fileName, byte[] bytes) {
}
