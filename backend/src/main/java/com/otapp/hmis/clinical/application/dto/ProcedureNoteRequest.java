package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the add_note transition (ACCEPTED + settled → VERIFIED) (C9).
 *
 * <p>The note is the procedure result narrative (Procedure.java:44-45, TEXT length 10000).
 * The note must be non-blank — empty note is rejected with 422 before calling the domain method.
 * This is the DISTINCTIVE transition that gates on the local settled flag (PatientResource.java:3408-3414).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>PatientResource.java:3408-3414 — add_note endpoint (procedures/add_note)</li>
 *   <li>Procedure.java:44-45 — note TEXT(10000)</li>
 * </ul>
 */
public record ProcedureNoteRequest(
        /**
         * The procedure result narrative.
         * Must be non-blank — blank note is rejected before the settlement gate is checked.
         * (PatientResource.java:3408-3414 — the legacy gate checks note non-empty first.)
         */
        @NotBlank String note
) {
}
