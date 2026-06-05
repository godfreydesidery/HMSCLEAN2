package com.otapp.hmis.clinical.api;

/**
 * Published patient-type discriminator for the pharmacy dispense worklist filter (inc-08a, Q1).
 *
 * <p>This is a published {@code clinical :: api} enum — NOT a leaked domain status. It is derived
 * from the prescription's encounter binding (consultation = OUTPATIENT, non-consultation =
 * OUTSIDER, admission = INPATIENT) and selects which bill-status set the worklist FILTER admits:
 * <ul>
 *   <li>{@link #OUTPATIENT}/{@link #OUTSIDER}: bill PAID or COVERED
 *       (PatientResource.java:4347, :4364, :4410);</li>
 *   <li>{@link #INPATIENT}: additionally admits VERIFIED — inpatient credit/post-pay, NOT
 *       insurer-verification (PatientResource.java:4381; RECONCILIATION D18).</li>
 * </ul>
 */
public enum PrescriptionPatientType {

    /** Consultation-bound prescription (OPD). Worklist admits bill PAID or COVERED. */
    OUTPATIENT,

    /** Non-consultation / walk-in prescription. Worklist admits bill PAID or COVERED. */
    OUTSIDER,

    /** Admission-bound prescription. Worklist additionally admits VERIFIED (inpatient credit). */
    INPATIENT
}
