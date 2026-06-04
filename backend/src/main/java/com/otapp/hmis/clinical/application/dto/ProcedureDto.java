package com.otapp.hmis.clinical.application.dto;

import com.otapp.hmis.clinical.domain.ProcedureStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO for a {@link com.otapp.hmis.clinical.domain.Procedure} response (C9, no id leak).
 *
 * <p>All internal {@code id} fields are EXCLUDED (ADR-0014 §1). Only the public {@code uid}
 * is exposed. Encounter binding is expressed as *Uid strings (loose refs).
 *
 * <p>Result column is procedure-specific: only {@code note} (TEXT, legacy length 10000).
 * Unlike LabTest (result/level/range/unit) or Radiology (result + report + blob), Procedure
 * has a single narrative note field.
 *
 * <p>Temporal fields: {@code procTime} (LocalTime, legacy {@code time_}),
 * {@code procDate} (LocalDate, legacy {@code date_}), {@code hours}/{@code minutes} (BigDecimal,
 * legacy double — pre-approved migration).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Procedure.java:40-147 (entity shape)</li>
 *   <li>PatientResource.java:3408-3414 (add_note transition — ACCEPTED + settled → VERIFIED)</li>
 * </ul>
 */
public record ProcedureDto(
        String uid,
        ProcedureStatus status,
        boolean settled,
        String procedureTypeUid,
        String patientUid,
        String patientBillUid,
        String paymentType,
        String membershipNo,
        String insurancePlanUid,
        String diagnosisTypeUid,
        String clinicianUserUid,
        String theatreUid,
        // Encounter binding (exactly one non-null)
        String consultationUid,
        String nonConsultationUid,
        String admissionUid,
        // Result / narrative
        /**
         * Procedure result narrative (TEXT, legacy 10000 chars).
         * Set at add_note time (ACCEPTED + settled → VERIFIED). Also writable at update time.
         */
        String note,
        String type,
        String diagnosis,
        // Temporal fields
        LocalDate procDate,
        LocalTime procTime,
        BigDecimal hours,
        BigDecimal minutes,
        // Lifecycle audit
        String orderedByUserUid,
        String orderedOnDayUid,
        Instant orderedAt,
        String acceptedByUserUid,
        String acceptedOnDayUid,
        Instant acceptedAt,
        String verifiedByUserUid,
        String verifiedOnDayUid,
        Instant verifiedAt,
        String rejectedByUserUid,
        String rejectedOnDayUid,
        Instant rejectedAt,
        String rejectComment,
        String businessDayUid,
        Instant createdAt
) {
}
