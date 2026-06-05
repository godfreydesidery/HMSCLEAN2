package com.otapp.hmis.inpatient.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * REST request body for recording an inpatient consumable chart entry (inc-07 07c-i).
 *
 * <p>Fields mirror the legacy chart entity + the nurse/pharmacy context required by the
 * billing and stock-decrement seams (CR-07-consumable-stock, CR-07-Q13-billing-display).
 *
 * <p>Legacy citation: PatientServiceImpl.java:2250-2475 (savePatientConsumableChart).
 * inc-07 07c-i / CR-07-consumable-stock.
 *
 * @param nurseUid         ULID of the nurse recording the chart (mandatory)
 * @param medicineUid      ULID of the consumable medicine (mandatory)
 * @param medicineName     display name of the medicine (mandatory; used for billing description
 *                         literal "Consumable: <medicineName>" — CR-07-Q13-billing-display;
 *                         the frontend knows the name when selecting a medicine from the catalog)
 * @param pharmacyUid      ULID of the stock-source pharmacy (mandatory — server-validated;
 *                         mirrors inc-08 Q2 pharmacy-uid pattern)
 * @param qty              quantity issued (must be &gt; 0)
 * @param paymentType      "CASH" or "INSURANCE"
 * @param insurancePlanUid loose uid of the insurance plan (nullable)
 * @param membershipNo     insurance membership number (nullable)
 */
public record ConsumableChartRequest(
        @NotBlank String nurseUid,
        @NotBlank String medicineUid,
        @NotBlank String medicineName,
        @NotBlank String pharmacyUid,
        @NotNull @DecimalMin(value = "0.01", message = "Qty can not be zero") BigDecimal qty,
        @NotBlank String paymentType,
        String insurancePlanUid,
        String membershipNo
) {
}
