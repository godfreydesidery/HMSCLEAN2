package com.otapp.hmis.registration.domain;

/**
 * Payment method for a patient registration (build-spec §1.2, CR-10).
 *
 * <p>Legacy citation: Patient.java:68-69; PatientServiceImpl.java:581.
 * The vocabulary is exactly two values.  DEBIT/CREDIT/MOBILE observed in
 * the planning-doc are DRIFT and are REJECTED (CR-10).
 *
 * <p>Stored via {@code @Enumerated(STRING)} as a VARCHAR(20) column.
 * The DB CHECK constraint ({@code ck_patients_payment_type}) enforces the same vocabulary.
 */
public enum PaymentType {

    /**
     * Cash-paying patient.  No insurance plan or membership required.
     * {@code insurance_plan_uid} MUST be {@code null} for CASH patients
     * (DB rule: {@code ck_patients_insurance_consistency}).
     */
    CASH,

    /**
     * Insurance-covered patient.  {@code insurance_plan_uid} and non-blank
     * {@code membershipNo} are both required (PatientResource.java:296-305; DB rule
     * {@code ck_patients_insurance_consistency}).
     */
    INSURANCE
}
