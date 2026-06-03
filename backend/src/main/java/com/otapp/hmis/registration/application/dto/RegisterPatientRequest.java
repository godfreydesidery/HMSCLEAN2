package com.otapp.hmis.registration.application.dto;

import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/patients} (register a new patient).
 *
 * <p>The {@code no} (MRN) field is intentionally absent — it is server-assigned from
 * {@code seq_mrno} (build-spec §2.1, CR-02). Any client-supplied {@code no} is ignored.
 *
 * <p>Insurance consistency (build-spec §2.3 step 1, §5.3, PatientResource.java:299-301):
 * if {@code paymentType=INSURANCE}, {@code insurancePlanUid} must be non-null AND
 * {@code membershipNo} must be non-blank — enforced in {@code PatientRegistrationProcess},
 * not via a class-level constraint, to produce a single clear
 * {@code MissingInsuranceInformationException} with the stable error-code URI.
 *
 * <p>Legacy citation: PatientResource.java:288-305 (register endpoint + insurance guard).
 */
public record RegisterPatientRequest(

        /** First name — required (Patient.java:54-55). */
        @NotBlank String firstName,

        /** Middle name — nullable (Patient.java:56). */
        String middleName,

        /** Last name — required (Patient.java:57-58). */
        @NotBlank String lastName,

        /** Date of birth — required (Patient.java:59-60). */
        @NotNull LocalDate dateOfBirth,

        /**
         * Gender — free-text, @NotBlank; no enum (CR-17, PatientResource.java:61-62).
         * DB has no CHECK constraint; Bean Validation enforces non-blank only.
         */
        @NotBlank String gender,

        /**
         * Payment classification (CR-10): CASH or INSURANCE.
         * When INSURANCE, {@code insurancePlanUid} + {@code membershipNo} are required
         * (enforced in {@code PatientRegistrationProcess}).
         */
        @NotNull PaymentType paymentType,

        /**
         * Patient type; defaults to OUTPATIENT when null (PatientResource.java:410-414, CR-11).
         * Vocabulary: OUTPATIENT / OUTSIDER / INPATIENT / DECEASED.
         */
        PatientType patientType,

        /** Phone number — nullable (Patient.java:74). */
        String phoneNo,

        /** Address — nullable (Patient.java:75). */
        String address,

        /** Email — nullable (Patient.java:76). */
        String email,

        /** Nationality — nullable (Patient.java:77). */
        String nationality,

        /** National ID — nullable (Patient.java:78). */
        String nationalId,

        /** Passport number — nullable (Patient.java:79). */
        String passportNo,

        /**
         * Loose cross-module uid of the insurance plan (masterdata module).
         * Required when {@code paymentType=INSURANCE}; must be null (or ignored) for CASH.
         * Build-spec §2.3 step 1, §1.2 insurance-consistency.
         */
        String insurancePlanUid,

        /**
         * Insurance membership number.
         * Required (non-blank) when {@code paymentType=INSURANCE}.
         */
        String membershipNo,

        /** Full name of next-of-kin — nullable (Patient.java:83, CR-14). */
        String kinFullName,

        /** Relationship of next-of-kin — nullable (Patient.java:84). */
        String kinRelationship,

        /** Phone number of next-of-kin — nullable (Patient.java:85). */
        String kinPhoneNo
) {
}
