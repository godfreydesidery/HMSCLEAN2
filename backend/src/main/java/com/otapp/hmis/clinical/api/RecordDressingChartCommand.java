package com.otapp.hmis.clinical.api;

import java.math.BigDecimal;

/**
 * Write command for a {@code PatientDressingChart} entry (inc-07 07b, ADR-0008 §1).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1).
 * Passed by the {@code inpatient} module into {@link NursingChartPort#recordDressingChart}.
 *
 * <p>The admission-IN-PROCESS gate is evaluated INPATIENT-SIDE before this command is
 * constructed. The dressing-registered guard is also evaluated INPATIENT-SIDE via
 * {@code DressingLookup.isDressing} (masterdata::lookup). Billing is handled inside the
 * port implementation via {@code BillingCommands.recordClinicalCharge(kind=PROCEDURE)}.
 *
 * <p><strong>Note on display literals (AC-07B-DRS-03):</strong>
 * The bill-line display literals {@code billItem='Procedure'}/{@code description='Dressing: <name>'}
 * are BLOCKED on CR-07-Q13-billing-display. The {@code ChargeRequest.billItem/description}
 * fields are available (owner-approved); this command carries them to enable the frozen
 * amount/status portion of AC-07B-DRS-03. The procedure type name is included for the
 * description override once CR-07-Q13-billing-display is unfrozen.
 * TODO(CR-07-Q13-billing-display): freeze the display-literal assertion once unblocked.
 *
 * <p>Legacy citation: PatientDressingChart.java:40-95; PatientServiceImpl.java:2078-2245.
 * inc-07 07b / AC-07B-DRS-01.
 *
 * @param admissionUid      loose uid of the owning admission
 * @param patientUid        loose uid of the patient
 * @param nurseUid          loose uid of the nurse (nullable)
 * @param clinicianUid      loose uid of the ordering clinician (nullable)
 * @param insurancePlanUid  loose uid of the insurance plan (nullable; null for cash)
 * @param membershipNo      insurance membership number (nullable)
 * @param procedureTypeUid  loose uid of the ProcedureType (mandatory)
 * @param procedureTypeName name of the ProcedureType (for description literal — TODO CR-07-Q13)
 * @param qty               quantity (NUMERIC 19,2)
 * @param paymentType       payment type string (e.g. "CASH" or "INSURANCE")
 */
public record RecordDressingChartCommand(
        String admissionUid,
        String patientUid,
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
