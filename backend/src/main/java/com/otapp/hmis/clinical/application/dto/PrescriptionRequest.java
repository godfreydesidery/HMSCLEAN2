package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for prescribing a medicine (save_prescription — C10).
 *
 * <p>Maps to the Prescription entity fields at create time. Free-text directives
 * (dosage, frequency, route, days) are optional; qty is mandatory.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Prescription.java:38-144 (field set)</li>
 *   <li>PatientServiceImpl.java (save_prescription request shape)</li>
 * </ul>
 */
public record PrescriptionRequest(

        /**
         * MANDATORY loose ref to the medicine in the masterdata module.
         * Verified via {@code MedicineLookup} before persist.
         */
        @NotBlank String medicineUid,

        /**
         * Prescribed quantity. Must be > 0.
         */
        @NotNull @DecimalMin(value = "0.000001", message = "qty must be greater than zero")
        BigDecimal qty,

        /** Free-text dosage instructions (e.g. "1 tablet"). */
        String dosage,

        /** Free-text frequency (e.g. "OD", "BD", "TDS"). */
        String frequency,

        /** Free-text route of administration (e.g. "ORAL", "IV"). */
        String route,

        /** Duration in days as a numeric string (parsed in unfinished-course alert). */
        String days,

        /** Prescription reference note (optional). */
        String reference,

        /** Patient instructions (optional). */
        String instructions,

        /** Payment type override (CASH / INSURANCE). Derived from encounter if absent. */
        String paymentType,

        /** Insurance membership number (optional; derived from encounter if absent). */
        String membershipNo,

        /** Insurance plan uid (optional; derived from encounter if absent). */
        String insurancePlanUid,

        /** Optional loose ref to the ordering clinician user. */
        String clinicianUserUid

) {
}
