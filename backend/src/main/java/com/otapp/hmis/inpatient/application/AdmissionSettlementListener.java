package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.inpatient.domain.AdmissionBed;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.masterdata.lookup.WardBedClaim;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.event.BillSettledEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Inpatient-side listener for the billing settlement seam (inc-07 07a).
 *
 * <p>When a CASH bill transitions to PAID, the billing module publishes a
 * {@link BillSettledEvent}. This listener matches the bill uid against
 * {@link AdmissionBed#getPatientBillUid()} — if a match is found, it activates the
 * associated PENDING admission to IN_PROCESS and calls
 * {@link WardBedClaim#occupyBed(String)} to flip the physical bed to OCCUPIED.
 *
 * <p>Reproduces the payment-driven activation path: PatientBillResource.java:352-365 —
 * paying the ward-bed bill promotes all PENDING admissions for the patient to IN-PROCESS
 * and occupies the bed. The modernised version matches by {@code patientBillUid} on the
 * {@code AdmissionBed} row rather than by patient (avoiding the patient lookup cross-module).
 *
 * <p><strong>Cross-module event seam design (inc-07 07a — no cycle):</strong>
 * <ul>
 *   <li>The event type ({@link BillSettledEvent}) lives in {@code shared.event} — the
 *       {@code shared} module is OPEN (ADR-0014 §1), so this listener imports only
 *       {@code shared} types from outside the inpatient module.</li>
 *   <li>The {@code billing} module imports nothing from {@code inpatient}. No billing→inpatient
 *       edge is created by the publisher side.</li>
 *   <li>Therefore no cycle is introduced and {@code ApplicationModules.verify()} stays green.</li>
 * </ul>
 *
 * <p><strong>Transaction phase — {@code BEFORE_COMMIT}:</strong>
 * Runs INSIDE the billing {@code PaymentService.recordPayment} transaction immediately before
 * commit. The admission IN_PROCESS flip and the bed OCCUPIED transition are therefore atomic
 * with the PAID bill transition: both commit or both roll back.
 *
 * <p><strong>Failure mode:</strong>
 * If the bill uid does not match any {@code AdmissionBed} row (e.g. an OPD bill with no
 * admission link), the listener completes silently — no exception is thrown. The billing PAID
 * transition still commits. This mirrors the {@code ConsultationSettlementListener} failure-mode
 * policy (not every bill has a downstream clinical/inpatient entity).
 *
 * <p><strong>PHI note:</strong>
 * The bill uid and admission uid are ULIDs — safe to log at DEBUG level.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Admission activation: PatientBillResource.java:352-365</li>
 *   <li>Bed OCCUPIED: PatientBillResource.java:358-359</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class AdmissionSettlementListener {

    private static final Logger log = LoggerFactory.getLogger(AdmissionSettlementListener.class);

    private final AdmissionBedRepository admissionBedRepository;
    private final AdmissionRepository admissionRepository;
    private final WardBedClaim wardBedClaim;
    private final AuditRecorder auditRecorder;

    /**
     * Handle a {@link BillSettledEvent}: if the bill uid matches an AdmissionBed's
     * patientBillUid, flip the linked PENDING Admission to IN_PROCESS and occupy the bed.
     *
     * <p>Runs {@code BEFORE_COMMIT} — inside the billing transaction. JPA dirty-checking
     * will flush the status change to the database when the outer transaction commits.
     *
     * @param event the bill-settled event (carries billUid only — no patientUid)
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBillSettled(BillSettledEvent event) {
        String billUid = event.billUid();
        log.debug("AdmissionSettlementListener: handling BillSettledEvent for billUid={}", billUid);

        admissionBedRepository.findByPatientBillUid(billUid).ifPresent((AdmissionBed bed) -> {
            // Resolve the admission and activate it if still PENDING
            admissionRepository.findByUid(bed.getAdmissionUid()).ifPresent(admission -> {
                if (admission.getStatus() == AdmissionStatus.PENDING) {
                    admission.activate();
                    auditRecorder.record("inpatient.Admission", admission.getUid(),
                            AuditAction.UPDATE, null);
                    log.debug("AdmissionSettlementListener: Admission {} activated IN-PROCESS "
                            + "(billUid={})", admission.getUid(), billUid);
                }
            });

            // Flip the physical bed to OCCUPIED (PatientBillResource.java:358-359)
            wardBedClaim.occupyBed(bed.getWardBedUid());
            log.debug("AdmissionSettlementListener: bed {} occupied (billUid={})",
                    bed.getWardBedUid(), billUid);
        });
    }
}
