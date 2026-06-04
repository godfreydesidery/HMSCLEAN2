package com.otapp.hmis.clinical.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request body for the update_procedure operation (no status change, status must be ACCEPTED) (C9).
 *
 * <p>Allows editing the note and temporal fields when the procedure order is in ACCEPTED state.
 * No field is mandatory — all are optional patches. Null values are ignored (the service does
 * not overwrite an existing value with null for optional fields).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>PatientResource.java:4060-4061 — update_procedure (edit note/fields when ACCEPTED)</li>
 * </ul>
 */
public record ProcedureUpdateRequest(
        /**
         * Updated procedure result narrative (null = leave unchanged).
         * Unlike ProcedureNoteRequest (which is the settlement-gated add_note),
         * update_procedure does NOT require settled — only status == ACCEPTED.
         */
        String note,

        /** Updated procedure type label (null = leave unchanged). */
        String type,

        /** Updated clinical diagnosis text (null = leave unchanged). */
        String diagnosis,

        /** Updated procedure date (null = leave unchanged). */
        LocalDate procDate,

        /** Updated procedure time (null = leave unchanged). */
        LocalTime procTime,

        /** Updated duration hours (null = leave unchanged). */
        BigDecimal hours,

        /** Updated duration minutes (null = leave unchanged). */
        BigDecimal minutes
) {
}
