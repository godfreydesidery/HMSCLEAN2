package com.otapp.hmis.inpatient.application.dto;

import java.math.BigDecimal;

/**
 * Request body for saving a dressing chart (a BILLING record) for an admission
 * (POST …/admissions/{admissionUid}/dressing-charts — inc-07 07b).
 *
 * <p>{@code PatientDressingChart} is a billing record (NO WoundStatus/wound field). It creates a
 * PROCEDURE-kind {@code PatientBill} at {@code ProcedureType.price} (UNPAID/COVERED/VERIFIED
 * trichotomy by insurance/admission context). The procedure type must be registered as a dressing
 * (DressingLookup, verbatim 422 "Procedure type is not listed as dressing").
 *
 * <p>Legacy citation: PatientDressingChart.java:40-95; PatientServiceImpl.java:2078-2245.
 * inc-07 07b / AC-07B-DRS-01..06.
 *
 * @param nurseUid          loose uid of the nurse (required)
 * @param clinicianUid      loose uid of the ordering clinician (nullable)
 * @param insurancePlanUid  loose uid of the insurance plan (nullable; cash if null)
 * @param membershipNo      insurance membership number (nullable)
 * @param procedureTypeUid  loose uid of the dressing ProcedureType (required)
 * @param procedureTypeName the dressing procedure name (for the bill-line description —
 *                          NOTE the 'Dressing: &lt;name&gt;' display literal is CR-blocked,
 *                          CR-07-Q13-billing-display)
 * @param qty               quantity (NUMERIC 19,2)
 * @param paymentType       requested payment mode (CASH / INSURANCE)
 */
public record DressingChartRequest(
        String nurseUid,
        String clinicianUid,
        String insurancePlanUid,
        String membershipNo,
        String procedureTypeUid,
        String procedureTypeName,
        BigDecimal qty,
        String paymentType
) {
}
