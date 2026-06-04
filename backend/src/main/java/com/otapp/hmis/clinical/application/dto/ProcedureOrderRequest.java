package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Request body for ordering (saving) a clinical procedure on an encounter (C9).
 *
 * <p>The encounter uid comes from the URL path (consultation uid or non-consultation uid),
 * NOT from this body. Only {@code procedureTypeUid} is mandatory per legacy parity.
 *
 * <p>Mirrors {@link LabTestOrderRequest} and {@link RadiologyOrderRequest} with the
 * procedure-specific fields added (note, type, procDate, procTime, hours, minutes, theatreUid).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Procedure.java:85-88 (procedureType mandatory ref)</li>
 *   <li>PatientServiceImpl.java — procedure order create path (save_procedure)</li>
 * </ul>
 */
public record ProcedureOrderRequest(
        /**
         * MANDATORY loose uid of the procedure type (masterdata).
         * Verified via ProcedureTypeLookup before persist.
         */
        @NotBlank String procedureTypeUid,

        /**
         * Optional loose uid of the theatre where the procedure will be performed (nullable).
         * Theatre uid is stored as-is without validation — theatre is optional.
         */
        String theatreUid,

        /**
         * Optional loose uid of the diagnosis type to associate with this order (nullable).
         */
        String diagnosisTypeUid,

        /**
         * Optional loose uid of the ordering clinician user (nullable).
         */
        String clinicianUserUid,

        /**
         * Initial procedure note / narrative (optional; may be populated at order time or later).
         */
        String note,

        /**
         * Procedure type label to store (optional — denormalised from procedureType at order time).
         */
        String type,

        /** Free-text clinical diagnosis associated with this procedure (optional). */
        String diagnosis,

        /**
         * The date the procedure was performed (optional at order time).
         * Procedure.java:50-51, legacy @Column date_.
         */
        LocalDate procDate,

        /**
         * The time the procedure was performed (optional at order time).
         * Procedure.java:47-48, legacy @Column time_.
         */
        LocalTime procTime,

        /**
         * Duration hours (optional, legacy double → BigDecimal).
         */
        BigDecimal hours,

        /**
         * Duration minutes (optional, legacy double → BigDecimal).
         */
        BigDecimal minutes,

        /**
         * For OUTSIDER (non-consultation) orders: patient uid required for billing.
         *
         * <p>For consultation orders: patientUid is derived from the consultation entity
         * (consultation.getPatientUid()) — this field is IGNORED on the consultation path.
         *
         * <p>For non-consultation orders: REQUIRED to build the ChargeRequest correctly.
         */
        String patientUid,

        /**
         * Payment type override for the charge (CASH / INSURANCE).
         * Nullable — defaults to the encounter's payment type if not supplied.
         * For consultation path: derived from consultation.paymentMode.
         */
        String paymentType,

        /**
         * Insurance membership number (empty for CASH patients).
         * For consultation path: derived from consultation.membershipNo.
         */
        String membershipNo,

        /**
         * Insurance plan uid (null for CASH patients).
         * For consultation path: derived from consultation.insurancePlanUid.
         */
        String insurancePlanUid
) {
}
