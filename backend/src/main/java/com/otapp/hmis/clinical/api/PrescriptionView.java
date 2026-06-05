package com.otapp.hmis.clinical.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published cross-module read projection of a {@code clinical} Prescription, consumed by the
 * {@code pharmacy} module (inc-08a, AC-RX-PRE-02). A self-contained {@code clinical :: api} record
 * — it does NOT reuse the application-internal {@code PrescriptionDto}, and it exposes NO internal
 * {@code id} (ADR-0014 §1).
 *
 * <p>{@code status} is the exact DB string ("NOT-GIVEN" / "GIVEN" — PrescriptionStatus.java:23-26);
 * the medicine flow has exactly those two states (the 8-state lifecycle is a rejected phantom, D1).
 * {@code patientType} is the published encounter discriminator the worklist filter keys on; exactly
 * one of {@code consultationUid}/{@code nonConsultationUid}/{@code admissionUid} is non-null.
 */
public record PrescriptionView(

        String uid,
        String status,
        boolean settled,

        String medicineUid,
        String patientUid,
        String patientBillUid,

        String paymentType,
        String membershipNo,
        String insurancePlanUid,

        String clinicianUserUid,
        String issuePharmacyUid,

        String consultationUid,
        String nonConsultationUid,
        String admissionUid,

        /** Derived encounter discriminator (OUTPATIENT/OUTSIDER/INPATIENT). */
        PrescriptionPatientType patientType,

        BigDecimal qty,
        BigDecimal issued,
        BigDecimal balance,

        Instant orderedAt,
        Instant approvedAt
) {
}
