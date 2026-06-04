package com.otapp.hmis.clinical.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTO for a {@link com.otapp.hmis.clinical.domain.Prescription} response (C10, no id leak).
 *
 * <p>All internal {@code id} fields are EXCLUDED (ADR-0014 §1). Only the public {@code uid}
 * is exposed. Encounter binding is expressed as *Uid strings (loose refs).
 *
 * <p><strong>alerts[] field (C10 — empty list here; C11 populates it):</strong>
 * The {@code alerts} field is designed now so that C11 (drug-alert queries) can populate it
 * without changing the DTO contract. In C10 the field is always an empty list.
 * (PatientResource.java:4496, 4556 alert queries — C11 scope.)
 */
public record PrescriptionDto(

        String uid,

        /** Lifecycle status as the exact DB string: "NOT-GIVEN" or "GIVEN" (via the converter's dbValue()). */
        String status,

        /** Clinical-local settlement projection (CR-INC05-01). */
        boolean settled,

        // Mandatory loose cross-module refs
        String medicineUid,
        String patientUid,
        String patientBillUid,

        // Payment context
        String paymentType,
        String membershipNo,
        String insurancePlanUid,

        // Optional loose refs
        String clinicianUserUid,
        String issuePharmacyUid,

        // Encounter binding (exactly one non-null)
        String consultationUid,
        String nonConsultationUid,
        String admissionUid,

        // Quantities (BigDecimal — no float on wire)
        BigDecimal qty,
        BigDecimal issued,
        BigDecimal balance,

        // Free-text directives
        String dosage,
        String frequency,
        String route,
        String days,
        String reference,
        String instructions,

        // Lifecycle audit — ordered_* (written at prescribe)
        String orderedByUserUid,
        String orderedOnDayUid,
        Instant orderedAt,

        // Lifecycle audit — approved_* (written at dispense)
        String approvedByUserUid,
        String approvedOnDayUid,
        Instant approvedAt,

        String businessDayUid,
        Instant createdAt,

        /**
         * Drug-alert advisory list (empty in C10; populated by C11 alert queries).
         * Designed now so C11 fills it without a DTO contract change.
         * (PatientResource.java:4496, 4556.)
         */
        List<String> alerts

) {
}
