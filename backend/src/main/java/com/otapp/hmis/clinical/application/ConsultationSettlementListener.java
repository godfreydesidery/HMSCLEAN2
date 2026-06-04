package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.LabTestRepository;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.clinical.domain.ProcedureRepository;
import com.otapp.hmis.clinical.domain.RadiologyRepository;
import com.otapp.hmis.shared.event.BillSettledEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Clinical-side listener for the billing settlement seam (inc-05 §5, ADR-0022 D5).
 *
 * <p>When a CASH bill transitions to PAID, the billing module publishes a
 * {@link BillSettledEvent}. This listener handles that event in the SAME transaction
 * ({@code BEFORE_COMMIT}) and flips the local {@code settled} flag on whichever clinical row
 * references that bill uid — ensuring the clinical worklist/open gate sees a settled order
 * without the clinical module ever calling back into billing.
 *
 * <p><strong>Cross-module event seam design (ADR-0022 D5 — no cycle):</strong>
 * <ul>
 *   <li>The event type ({@link BillSettledEvent}) lives in {@code shared.event} — the
 *       {@code shared} module is OPEN (ADR-0014 §1), so this listener imports only
 *       {@code shared} types from outside the clinical module.</li>
 *   <li>The {@code clinical} module already depends on {@code billing::api} (for
 *       {@code PaymentMode}, {@code SettlementPolicy}, etc.) via its declared
 *       {@code allowedDependencies}. Consuming this event adds NO new inter-module compile edge
 *       because the event is in {@code shared.event}, not in {@code billing}.</li>
 *   <li>The {@code billing} module imports nothing from {@code clinical}. No billing→clinical
 *       edge is created by the publisher side either.</li>
 *   <li>Therefore no cycle is introduced and {@code ApplicationModules.verify()} stays green.</li>
 * </ul>
 *
 * <p><strong>Transaction phase — {@code BEFORE_COMMIT}:</strong>
 * This listener runs INSIDE the billing module's {@code @Transactional} method (the
 * {@code PaymentService.recordPayment} call) immediately before it commits. The clinical
 * {@code settled} flag mutations are therefore atomic with the billing PAID transition:
 * both commit together or both roll back together. This is the required atomicity for the
 * cash-PAID→settled=true seam (CR-05, inc-05 §5).
 *
 * <p><strong>Why not Spring Modulith's {@code @ApplicationModuleListener}?</strong>
 * Same rationale as {@link com.otapp.hmis.registration.application.PatientClosureListener}:
 * the {@code event_publication} table is not bootstrapped in this project. Plain
 * {@code @TransactionalEventListener} achieves the same-transaction atomicity without that
 * infrastructure.
 *
 * <p><strong>Which clinical entity does a bill map to?</strong>
 * Each clinical charge creates exactly one {@code PatientBill} — a one-to-one cardinality
 * (ADR-0022 D2). The bill uid is a stable foreign key stored on every clinical order entity.
 * A given bill uid will match AT MOST ONE row across all five clinical entity types.
 * This listener queries each of the five repositories by bill uid and calls
 * {@code markSettled()} on any match found. In practice exactly one repository will match.
 *
 * <p><strong>Failure mode:</strong>
 * If the bill uid does not match any clinical row (e.g. a registration fee bill that has no
 * associated clinical order), the listener silently completes — no entities are mutated and
 * no exception is thrown. The billing PAID transition still commits successfully. This is the
 * correct behaviour: not every bill has a downstream clinical entity (registration fees,
 * ward charges, etc. have no clinical-local projection).
 *
 * <p><strong>PHI note:</strong>
 * The bill uid is a ULID (not a patient name, diagnosis, or financial identifier). It is safe
 * to include in structured log messages at DEBUG level.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>CR-05 settlement gate: PatientBillResource.java:305-307 + PatientResource.java:886</li>
 *   <li>Consultation.settled local flag: domain/Consultation.java (ADR-0022 D2/D4, inc-05 §5)</li>
 *   <li>LabTest.settled: domain/LabTest.java (CR-INC05-01, V33)</li>
 *   <li>Radiology.settled: domain/Radiology.java (CR-INC05-01, V34)</li>
 *   <li>Procedure.settled: domain/Procedure.java (CR-INC05-01, V35)</li>
 *   <li>Prescription.settled: domain/Prescription.java (CR-INC05-01, V36)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ConsultationSettlementListener {

    private static final Logger log = LoggerFactory.getLogger(ConsultationSettlementListener.class);

    private final ConsultationRepository consultationRepository;
    private final LabTestRepository labTestRepository;
    private final RadiologyRepository radiologyRepository;
    private final ProcedureRepository procedureRepository;
    private final PrescriptionRepository prescriptionRepository;

    /**
     * Handle a {@link BillSettledEvent}: flip the local {@code settled} flag on whichever
     * clinical row references the bill uid.
     *
     * <p>Runs with {@code BEFORE_COMMIT} — inside the billing transaction. JPA dirty-checking
     * will flush the flag mutation to the database when the outer transaction commits. No
     * explicit {@code save()} is needed provided the entity is managed (i.e. loaded via
     * {@code findByPatientBillUid} within this same transaction context, which it is because
     * the listener participates in the caller's transaction).
     *
     * @param event the bill-settled event carrying the bill uid and settlement instant
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBillSettled(BillSettledEvent event) {
        String billUid = event.billUid();
        log.debug("ConsultationSettlementListener: handling BillSettledEvent for billUid={}", billUid);

        // Attempt to match each of the 5 clinical entity types by patientBillUid.
        // At most one will match (one-to-one cardinality per ADR-0022 D2).
        // Querying all five is safe: the unmatched repos return Optional.empty() cheaply.

        consultationRepository.findByPatientBillUid(billUid).ifPresent(consultation -> {
            consultation.markSettled();
            log.debug("ConsultationSettlementListener: Consultation {} marked settled (billUid={})",
                    consultation.getUid(), billUid);
        });

        labTestRepository.findByPatientBillUid(billUid).ifPresent(labTest -> {
            labTest.markSettled();
            log.debug("ConsultationSettlementListener: LabTest {} marked settled (billUid={})",
                    labTest.getUid(), billUid);
        });

        radiologyRepository.findByPatientBillUid(billUid).ifPresent(radiology -> {
            radiology.markSettled();
            log.debug("ConsultationSettlementListener: Radiology {} marked settled (billUid={})",
                    radiology.getUid(), billUid);
        });

        procedureRepository.findByPatientBillUid(billUid).ifPresent(procedure -> {
            procedure.markSettled();
            log.debug("ConsultationSettlementListener: Procedure {} marked settled (billUid={})",
                    procedure.getUid(), billUid);
        });

        prescriptionRepository.findByPatientBillUid(billUid).ifPresent(prescription -> {
            prescription.markSettled();
            log.debug("ConsultationSettlementListener: Prescription {} marked settled (billUid={})",
                    prescription.getUid(), billUid);
        });
    }
}
