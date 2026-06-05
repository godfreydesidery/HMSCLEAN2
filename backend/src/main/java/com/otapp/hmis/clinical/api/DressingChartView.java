package com.otapp.hmis.clinical.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable cross-module projection of a {@code PatientDressingChart} record (inc-07 07b,
 * ADR-0008 §1).
 *
 * <p>No entity type leaks across the module boundary. The {@code patientBillUid} is included
 * so the inpatient controller can surface the bill uid for cashier cross-reference without
 * importing the billing domain type.
 *
 * <p>Legacy citation: PatientDressingChart.java:40-95; PatientServiceImpl.java:2078-2245.
 * inc-07 07b / AC-07B-DRS-01.
 *
 * @param uid              the dressing chart's public ULID
 * @param admissionUid     loose uid of the owning admission
 * @param nurseUid         loose uid of the nurse (nullable)
 * @param procedureTypeUid loose uid of the ProcedureType
 * @param patientBillUid   loose uid of the created PatientBill
 * @param qty              quantity
 * @param paymentType      payment type string
 * @param createdAt        audit creation instant
 */
public record DressingChartView(
        String uid,
        String admissionUid,
        String nurseUid,
        String procedureTypeUid,
        String patientBillUid,
        BigDecimal qty,
        String paymentType,
        Instant createdAt
) {
}
