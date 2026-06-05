package com.otapp.hmis.clinical.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable cross-module projection of a {@code PatientConsumableChart} record (inc-07 07c-i,
 * ADR-0008 §1).
 *
 * <p>No entity type leaks across the module boundary. The {@code patientBillUid} is included
 * so the inpatient controller can surface the bill uid for cashier cross-reference without
 * importing the billing domain type. Mirrors {@link DressingChartView} structure.
 *
 * <p>Legacy citation: PatientConsumableChart.java; PatientServiceImpl.java:2250-2475.
 * inc-07 07c-i / CR-07-consumable-stock.
 *
 * @param uid              the consumable chart's public ULID
 * @param admissionUid     loose uid of the owning admission
 * @param nurseUid         loose uid of the nurse (nullable)
 * @param medicineUid      loose uid of the consumable Medicine
 * @param pharmacyUid      loose uid of the stock-source pharmacy
 * @param patientBillUid   loose uid of the created PatientBill
 * @param qty              quantity issued
 * @param status           chart status — always "NOT-GIVEN" (PatientServiceImpl.java:2305 quirk)
 * @param paymentType      payment type string
 * @param createdAt        audit creation instant
 */
public record ConsumableChartView(
        String uid,
        String admissionUid,
        String nurseUid,
        String medicineUid,
        String pharmacyUid,
        String patientBillUid,
        BigDecimal qty,
        String status,
        String paymentType,
        Instant createdAt
) {
}
