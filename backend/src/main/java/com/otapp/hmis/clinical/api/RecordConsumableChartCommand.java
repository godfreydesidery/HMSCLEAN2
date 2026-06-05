package com.otapp.hmis.clinical.api;

import java.math.BigDecimal;

/**
 * Write command for a {@code PatientConsumableChart} entry (inc-07 07c-i, ADR-0008 §1).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1).
 * Passed by the {@code inpatient} module into {@link ConsumableChartPort#recordConsumableChart}.
 *
 * <p>The admission-IN-PROCESS gate is evaluated INPATIENT-SIDE before this command is
 * constructed. The consumable-registered guard is also evaluated INPATIENT-SIDE via
 * {@code ConsumableLookup.isConsumable} (masterdata::lookup). Billing is handled inside
 * the port implementation via {@code BillingCommands.recordClinicalCharge(kind=MEDICINE,
 * billItem="Medication", description="Consumable: <medicineName>")} (CR-07-Q13-billing-display
 * APPROVED). Stock decrement is handled by the INPATIENT orchestrator via
 * {@code PharmacyStockDebit.debitConsumableIssue} (CR-07-consumable-stock APPROVED).
 *
 * <p>Legacy citation: PatientServiceImpl.java:2250-2475 (savePatientConsumableChart).
 * inc-07 07c-i / CR-07-consumable-stock / CR-07-Q13-billing-display.
 *
 * @param admissionUid     loose uid of the owning admission
 * @param patientUid       loose uid of the patient
 * @param nurseUid         loose uid of the nurse (mandatory for inpatient; validated clinical-side)
 * @param medicineUid      loose uid of the consumable Medicine (mandatory)
 * @param medicineName     name of the medicine (for the billing description literal)
 * @param insurancePlanUid loose uid of the insurance plan (nullable; null for cash)
 * @param membershipNo     insurance membership number (nullable)
 * @param qty              quantity (NUMERIC 19,2; must be &gt; 0)
 * @param paymentType      payment type string ("CASH" or "INSURANCE")
 * @param pharmacyUid      loose uid of the stock-source pharmacy (mandatory; server-validated)
 */
public record RecordConsumableChartCommand(
        String admissionUid,
        String patientUid,
        String nurseUid,
        String medicineUid,
        String medicineName,
        String insurancePlanUid,
        String membershipNo,
        BigDecimal qty,
        String paymentType,
        String pharmacyUid
) {
}
