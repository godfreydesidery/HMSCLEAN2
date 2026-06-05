package com.otapp.hmis.inpatient.application.dto;

/**
 * Request body for saving a nursing progress note
 * (POST …/admissions/{admissionUid}/progress-notes — inc-07 07b).
 *
 * <p>A single free-text {@code note} on {@code PatientNursingProgressNote} — NO kind enum
 * (legacy has none). {@code nurseUid} is required (enforced clinical-side).
 *
 * <p>Legacy citation: PatientNursingProgressNote.java; PatientServiceImpl.java:2647-2698.
 * inc-07 07b / AC-07B-NPN-01.
 *
 * @param nurseUid loose uid of the charting nurse (required)
 * @param note     the progress note free-text (nullable)
 */
public record ProgressNoteRequest(
        String nurseUid,
        String note
) {
}
