package com.otapp.hmis.clinical.api;

import com.otapp.hmis.shared.domain.TxAuditContext;

/**
 * Published dispense/decrement seam (inc-08a, AC-RX-PRE-06/07). The {@code pharmacy} module
 * orchestrates the dispense — it owns the {@code pharmacyUid}-selected stock source (Q2), performs
 * its OWN stock decrement / FEFO / stock-card write — then calls back here to flip the clinical
 * Prescription NOT-GIVEN → GIVEN and stamp the {@code approved_*} dispense audit + the
 * {@code issuePharmacyUid}.
 *
 * <p><strong>Atomicity (AC-RX-PRE-07):</strong> {@link #markDispensed} propagates {@code REQUIRED}
 * (the caller's transaction) — NO {@code @Async}, NO {@code REQUIRES_NEW}. The clinical flip and the
 * pharmacy decrement therefore commit (or roll back) together, exactly as the legacy
 * {@code issue_medicine} terminal did both in one transaction (PatientResource.java:3199-3293).
 *
 * <p><strong>No hard pay gate at this seam (Q1):</strong> {@code markDispensed} performs NO
 * {@code PatientBill.status} re-check and NEVER calls {@code BillingQueries.getBillStatus} — payment
 * enforcement is the worklist FILTER only ({@link PrescriptionWorklistPort}). The four legacy guards
 * (status==NOT-GIVEN; issued&gt;0; issued&gt;=balance; issued==qty — all-or-nothing) are enforced
 * inside the clinical aggregate.
 */
public interface PrescriptionDispensePort {

    /**
     * Flip a NOT-GIVEN prescription to GIVEN after the pharmacy decrement, persisting any supplied
     * lot-trace and stamping the dispense audit.
     *
     * @param prescriptionUid the ULID of the prescription being dispensed
     * @param cmd             the dispense confirmation (issued qty, required issuePharmacyUid, lots)
     * @param ctx             the transaction audit context (actor, business day, timestamp)
     * @return the updated published projection (status=GIVEN)
     * @throws com.otapp.hmis.shared.error.NotFoundException        if no prescription with that uid exists
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if a dispense guard fails
     *         (verbatim legacy messages: "...not a pending prescription", "Invalid issue value",
     *         "You can only issue the prescribed qty")
     */
    PrescriptionView markDispensed(String prescriptionUid, DispenseConfirmation cmd, TxAuditContext ctx);
}
