package com.otapp.hmis.clinical.api;

import java.util.List;

/**
 * Published pharmacy dispense-worklist seam (inc-08a, AC-RX-PRE-03/04). <strong>THE FILTER</strong>
 * — applied server-side in {@code clinical}, reproducing the verified legacy bill-status worklist
 * filter verbatim (PatientResource.java:4347/4364/4381/4410).
 *
 * <p><strong>Q1 (ratified) — this is a FILTER, not a hard terminal gate.</strong> The worklist
 * surfaces only NOT-GIVEN prescriptions whose linked bill admits dispensing for the patient type:
 * OUTPATIENT/OUTSIDER require {@code PAID|COVERED}; INPATIENT additionally admits {@code VERIFIED}
 * (inpatient credit, not insurer-verification — D18). The dispense terminal itself carries NO
 * bill-status check (RECONCILIATION §C).
 *
 * <p><strong>Reconciliation note (AC-RX-PRE-05):</strong> the inc-05 intra-module
 * {@code pharmacyWorklist()} deliberately returned ALL NOT-GIVEN regardless of bill status
 * ("pharmacist validates physically"), contradicting the legacy FILTER and ratified Q1. This
 * published port is specified to the legacy FILTER, and the inc-05 finder/comment is corrected to
 * match (inc-08a chunk 1). The three-valued bill status is read via
 * {@code billing :: api} {@code BillingQueries.worklistAdmits} (clinical already depends on
 * {@code billing :: api}; no new edge, no cycle).
 */
public interface PrescriptionWorklistPort {

    /**
     * The pharmacy dispense worklist: NOT-GIVEN prescriptions whose linked bill admits dispensing
     * for the requested patient type, oldest-first (FIFO).
     *
     * @param filter the worklist filter (patient type + optional patient scope)
     * @return the admitted NOT-GIVEN prescriptions, oldest first
     */
    List<PrescriptionView> dispenseWorklist(WorklistFilter filter);

    /**
     * Worklist filter input.
     *
     * @param patientType the encounter discriminator selecting the admitted bill-status set
     *                     (required)
     * @param patientUid  optional scope to a single patient (null = all patients)
     */
    record WorklistFilter(PrescriptionPatientType patientType, String patientUid) {
    }
}
