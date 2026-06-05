package com.otapp.hmis.inpatient.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Request body for recording a closed-loop medication administration (MAR)
 * (POST …/admissions/{admissionUid}/medication-administrations — inc-07 07d, CR-07-MAR).
 *
 * <p>NET-NEW — additive over the free-text {@link DosingNoteRequest} dosing-note path; both
 * coexist. The linked prescription must be GIVEN (clinical-side guard); the admission must be
 * IN-PROCESS and {@code routeUid} must be a registered ACTIVE route (inpatient-side guards).
 *
 * <p>Mandatory fields ({@code prescriptionUid}, {@code nurseUid}, {@code routeUid},
 * {@code administeredAt}) are bean-validated for a clean 400 before the business guards run.
 *
 * @param prescriptionUid loose uid of the parent prescription (must be GIVEN)
 * @param nurseUid        loose uid of the administering nurse (required)
 * @param routeUid        loose uid of the administration route (must be a registered ACTIVE route)
 * @param administeredAt  the instant the medication was administered
 * @param doseGiven       dose given (free-text; nullable)
 * @param patientResponse observed patient response (free-text; nullable)
 */
public record MedicationAdministrationRequest(
        @NotBlank String prescriptionUid,
        @NotBlank String nurseUid,
        @NotBlank String routeUid,
        @NotNull Instant administeredAt,
        String doseGiven,
        String patientResponse
) {
}
