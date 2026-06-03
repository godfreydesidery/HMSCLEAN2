package com.otapp.hmis.registration.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request body for {@code PUT /api/v1/patients/uid/{uid}} (update demographics + kin).
 *
 * <p>This DTO intentionally excludes {@code no} (MRN), {@code type}, {@code paymentType},
 * and all insurance fields — those are controlled by their own dedicated endpoints
 * (build-spec §1.3, §8 C4):
 * <ul>
 *   <li>{@code PATCH .../patient-type} via {@link ChangePatientTypeRequest}</li>
 *   <li>{@code PATCH .../payment-type} via {@link ChangePaymentTypeRequest}</li>
 * </ul>
 *
 * <p>All fields mirror the mutable demographics set accepted at registration
 * ({@link RegisterPatientRequest}), with the same Bean Validation constraints
 * (build-spec §5.3, PatientResource.java:378-395).
 *
 * <p>Legacy citation: PatientResource.java:378-395 (update patient endpoint).
 */
public record UpdatePatientRequest(

        /** First name — required (Patient.java:54-55). */
        @NotBlank String firstName,

        /** Middle name — nullable (Patient.java:56). */
        String middleName,

        /** Last name — required (Patient.java:57-58). */
        @NotBlank String lastName,

        /** Date of birth — required (Patient.java:59-60). */
        @NotNull LocalDate dateOfBirth,

        /**
         * Gender — free-text {@code @NotBlank}; no enum (CR-17).
         */
        @NotBlank String gender,

        /** Phone number — nullable (Patient.java:74). */
        String phoneNo,

        /** Postal or physical address — nullable (Patient.java:75). */
        String address,

        /** Email address — nullable (Patient.java:76). */
        String email,

        /** Nationality — nullable (Patient.java:77). */
        String nationality,

        /** National identity document number — nullable (Patient.java:78). */
        String nationalId,

        /** Passport number — nullable (Patient.java:79). */
        String passportNo,

        /** Full name of next-of-kin — nullable (Patient.java:83, CR-14). */
        String kinFullName,

        /** Relationship of next-of-kin to patient — nullable (Patient.java:84). */
        String kinRelationship,

        /** Phone number of next-of-kin — nullable (Patient.java:85). */
        String kinPhoneNo
) {
}
