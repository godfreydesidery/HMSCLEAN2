package com.otapp.hmis.registration.application.dto;

import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Response DTO for a patient resource (build-spec §1.3, PatientDto).
 *
 * <p>Rules (ADR-0014 §1, §3):
 * <ul>
 *   <li>No {@code id} field — ULID {@code uid} is the only public identifier.</li>
 *   <li>No {@code searchKey} — it is a {@code @PiiField}-tagged derived key for internal
 *       search only (build-spec §5.2); never surfaced on the API.</li>
 *   <li>{@code lastVisitAt} is populated in C5 (search + last-visit); it is null in C3
 *       to avoid DTO churn (build-spec §8 C5).</li>
 *   <li>PHI fields are present in the API response (legitimate for authenticated callers)
 *       but are redacted by the audit serializer in {@code audit_log} (ADR-0007, §5.2).</li>
 * </ul>
 *
 * <p>Legacy citation: PatientResource.java:267-287 (patient response shape).
 */
public record PatientDto(

        /** ULID public identifier (never the hidden surrogate id). */
        String uid,

        /** Medical Record Number — {@code MRNO/{EAT-year}/{seq}} (build-spec §2.1, CR-02). */
        String no,

        String firstName,
        String middleName,
        String lastName,
        LocalDate dateOfBirth,
        String gender,

        /** Patient type vocabulary: OUTPATIENT / OUTSIDER / INPATIENT / DECEASED (CR-11). */
        PatientType type,

        /** Payment classification: CASH or INSURANCE (CR-10). */
        PaymentType paymentType,

        /** Insurance membership number; empty-string for CASH patients. */
        String membershipNo,

        String phoneNo,
        String address,
        String email,
        String nationality,
        String nationalId,
        String passportNo,

        /** Full name of next-of-kin (CR-14 — exactly ONE kin, 3 flat columns). */
        String kinFullName,
        String kinRelationship,
        String kinPhoneNo,

        boolean active,

        /**
         * Loose uid of the insurance plan (masterdata module); null for CASH patients.
         * ADR-0008 — no FK across module boundaries.
         */
        String insurancePlanUid,

        /**
         * Timestamp of the patient's most recent visit.
         * Populated in C5 (build-spec §8 C5, CR-08); null in C3.
         */
        Instant lastVisitAt
) {
}
