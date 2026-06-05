package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.clinical.api.ConsultationSignOut;
import com.otapp.hmis.inpatient.domain.AdmissionBed;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.masterdata.lookup.WardBedClaim;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
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
 * {@link AdmissionBed#getPatientBillUid()} — if a match is found, it:
 * <ol>
 *   <li>Signs out any IN_PROCESS OPD consultations for the patient via
 *       {@link ConsultationSignOut#signOutInProcessConsultations} (PatientBillResource.java:353-364
 *       — cash payment-driven sign-out; only IN_PROCESS, not PENDING).</li>
 *   <li>Activates the associated PENDING admission to IN_PROCESS.</li>
 *   <li>Calls {@link WardBedClaim#occupyBed(String)} to flip the physical bed to OCCUPIED.</li>
 * </ol>
 *
 * <p>Reproduces the payment-driven activation path: PatientBillResource.java:352-365 —
 * paying the ward-bed bill promotes all PENDING admissions for the patient to IN-PROCESS,
 * signs out IN_PROCESS consultations, and occupies the bed. The modernised version matches
 * by {@code patientBillUid} on the {@code AdmissionBed} row rather than by patient
 * (avoiding the patient lookup cross-module).
 *
 * <p><strong>Cross-module event seam design (inc-07 07a — no cycle):</strong>
 * <ul>
 *   <li>The event type ({@link BillSettledEvent}) lives in {@code shared.event} — the
 *       {@code shared} module is OPEN (ADR-0014 §1), so this listener imports only
 *       {@code shared} types from outside the inpatient module.</li>
 *   <li>The {@link ConsultationSignOut} port is in {@code clinical::api} — already an
 *       allowed dependency of the {@code inpatient} module (package-info.java allowedDependencies).
 *       No new module edge is introduced.</li>
 *   <li>The {@code billing} module imports nothing from {@code inpatient}. No billing→inpatient
 *       edge is created by the publisher side.</li>
 *   <li>Therefore no cycle is introduced and {@code ApplicationModules.verify()} stays green.</li>
 * </ul>
 *
 * <p><strong>Transaction phase — {@code BEFORE_COMMIT}:</strong>
 * Runs INSIDE the billing {@code PaymentService.recordPayment} transaction immediately before
 * commit. The consultation sign-out, admission IN_PROCESS flip, and bed OCCUPIED transition
 * are therefore atomic with the PAID bill transition: all commit or all roll back.
 *
 * <p><strong>Failure mode:</strong>
 * If the bill uid does not match any {@code AdmissionBed} row (e.g. an OPD bill with no
 * admission link), the listener completes silently — no exception is thrown. The billing PAID
 * transition still commits. This mirrors the {@code ConsultationSettlementListener} failure-mode
 * policy (not every bill has a downstream clinical/inpatient entity).
 *
 * <p><strong>Audit context:</strong>
 * The settlement listener runs in a system-initiated transaction with no JWT actor. The
 * {@link TxAuditContext} is constructed with {@code actorUsername = null} (same pattern as
 * the pre-existing admission audit record in this class). The {@code AuditRecorder} handles
 * null actors by recording a system/anonymous audit trail entry.
 *
 * <p><strong>PHI note:</strong>
 * The bill uid and admission uid are ULIDs — safe to log at DEBUG level. The patientUid
 * is also a ULID and is passed only to the clinical port (not written to logs).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Admission activation: PatientBillResource.java:352-365</li>
 *   <li>Bed OCCUPIED: PatientBillResource.java:358-359</li>
 *   <li>Consultation sign-out (cash path): PatientBillResource.java:353-364</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class AdmissionSettlementListener {

    private static final Logger log = LoggerFactory.getLogger(AdmissionSettlementListener.class);

    private final AdmissionBedRepository admissionBedRepository;
    private final AdmissionRepository    admissionRepository;
    private final WardBedClaim           wardBedClaim;
    private final ConsultationSignOut    consultationSignOut;
    private final AuditRecorder          auditRecorder;

    /**
     * Handle a {@link BillSettledEvent}: if the bill uid matches an AdmissionBed's
     * patientBillUid, sign out IN_PROCESS consultations, flip the linked PENDING Admission
     * to IN_PROCESS, and occupy the bed.
     *
     * <p>Legacy order matches PatientBillResource.java:352-365:
     * <ol>
     *   <li>Activate PENDING admissions (lines :354-360).</li>
     *   <li>Sign out IN_PROCESS consultations (lines :361-364).</li>
     *   <li>Bed OCCUPIED is set inline on the admission's wardBed in legacy (:358-359);
     *       here {@link WardBedClaim#occupyBed} is called after the admission activation.</li>
     * </ol>
     *
     * <p>Runs {@code BEFORE_COMMIT} — inside the billing transaction. JPA dirty-checking
     * will flush all status changes to the database when the outer transaction commits.
     *
     * @param event the bill-settled event (carries billUid and settledAt — no patientUid)
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBillSettled(BillSettledEvent event) {
        String billUid = event.billUid();
        log.debug("AdmissionSettlementListener: handling BillSettledEvent for billUid={}", billUid);

        admissionBedRepository.findByPatientBillUid(billUid).ifPresent((AdmissionBed bed) -> {

            // Resolve the admission and activate it if still PENDING
            // PatientBillResource.java:354-360
            admissionRepository.findByUid(bed.getAdmissionUid()).ifPresent(admission -> {
                if (admission.getStatus() == AdmissionStatus.PENDING) {
                    admission.activate();
                    auditRecorder.record("inpatient.Admission", admission.getUid(),
                            AuditAction.UPDATE, null);
                    log.debug("AdmissionSettlementListener: Admission {} activated IN-PROCESS "
                            + "(billUid={})", admission.getUid(), billUid);
                }
            });

            // Sign out IN_PROCESS consultations for the patient (PatientBillResource.java:353-364).
            // CASH path: only IN_PROCESS (not PENDING) — narrower than the insurance no-top-up
            // path which also includes PENDING (PatientServiceImpl.java:1951-1958).
            // actorUsername = null: system-initiated, no JWT actor in this transaction.
            TxAuditContext sysCtx = new TxAuditContext(null, event.settledAt(), null);
            consultationSignOut.signOutInProcessConsultations(bed.getPatientUid(), sysCtx);

            // Flip the physical bed to OCCUPIED (PatientBillResource.java:358-359)
            wardBedClaim.occupyBed(bed.getWardBedUid());
            log.debug("AdmissionSettlementListener: bed {} occupied (billUid={})",
                    bed.getWardBedUid(), billUid);
        });
    }
}
