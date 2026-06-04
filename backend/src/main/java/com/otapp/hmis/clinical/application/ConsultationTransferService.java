package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.ConsultationTransferCompletion;
import com.otapp.hmis.clinical.api.ConsultationTransferDto;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.clinical.domain.ConsultationTransfer;
import com.otapp.hmis.clinical.domain.ConsultationTransferRepository;
import com.otapp.hmis.clinical.domain.ConsultationTransferStatus;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle service for the {@link ConsultationTransfer} aggregate (ADR-0022 D4, inc-05 C3).
 *
 * <p>Implements the three transfer transitions:
 * <ol>
 *   <li><strong>RAISE</strong> — validates guards, transitions source consultation TRANSFERED,
 *       creates PENDING transfer (both in one tx).</li>
 *   <li><strong>CANCEL</strong> — transitions transfer CANCELED, source consultation back to
 *       IN_PROCESS. If source not TRANSFERED: silent no-op (legacy parity).</li>
 *   <li><strong>COMPLETE</strong> (seam) — when the patient is re-booked to the destination
 *       clinic, verify destination matches and mark transfer COMPLETED; called by
 *       {@code ConsultationBookingServiceImpl.book} via {@link ConsultationTransferCompletion}
 *       with propagation MANDATORY.</li>
 * </ol>
 *
 * <p><strong>DEFERRED STUB — no-PENDING-child-orders guard (C7-C10):</strong>
 * The legacy raise guard (c) checks that the source consultation has no PENDING
 * LabTest/Radiology/Procedure/Prescription. Those entities do not exist in inc-05 C3
 * (they arrive in C7-C10). This guard is documented as a TODO stub below and MUST be
 * implemented once those repositories are available.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>RAISE:    PatientServiceImpl.java:2756-2808 (createConsultationTransfer)</li>
 *   <li>CANCEL:   PatientServiceImpl.java:2810-2830 (cancelConsultationTransfer)</li>
 *   <li>COMPLETE: PatientServiceImpl.java:431-435 (doConsultation pending-transfer seam)</li>
 *   <li>QUEUE:    PatientResource.java:599 (get_consultation_transfers)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class ConsultationTransferService
        implements ConsultationTransferPort, ConsultationTransferCompletion {

    private static final String AUDIT_ENTITY_TRANSFER = "clinical.ConsultationTransfer";
    private static final String AUDIT_ENTITY_CONSULTATION = "clinical.Consultation";

    private final ConsultationRepository consultationRepository;
    private final ConsultationTransferRepository transferRepository;
    private final AuditRecorder auditRecorder;
    private final ConsultationTransferMapper transferMapper;

    // -------------------------------------------------------------------------
    // RAISE — POST /uid/{uid}/transfer
    // -------------------------------------------------------------------------

    /**
     * Raise a transfer: source consultation IN_PROCESS → TRANSFERED; create PENDING transfer.
     *
     * <p>Guards (verbatim legacy messages):
     * <ol>
     *   <li>(a) Source consultation status MUST == IN_PROCESS; else 422
     *       "Can not transfer. Not an active consultation"
     *       (PatientServiceImpl.java:2756-2758).</li>
     *   <li>(b) No other PENDING transfer for the patient (patientUid); else 422
     *       (PatientServiceImpl.java:2764-2767). Backstopped by the partial-unique index
     *       {@code uq_consultation_transfers_one_pending_per_patient}.</li>
     *   <li>(c) No PENDING LabTest/Radiology/Procedure/Prescription on this consultation.
     *       <strong>DEFERRED STUB</strong> — those entities arrive in C7-C10. Documented
     *       as TODO below; this guard must be wired once those repositories exist.</li>
     *   <li>(d) Destination clinic uid != source consultation's clinic uid; else 422
     *       "Cannot transfer to the same clinic"
     *       (CR-INC05-04 resolved-by-design: string .equals() comparison — the legacy
     *       {@code ==} on boxed Long was a bug we do NOT reproduce).</li>
     * </ol>
     *
     * <p>On success: source consultation → TRANSFERED (save), THEN create PENDING transfer
     * (save) — both in ONE @Transactional unit.
     *
     * @param consultationUid     source consultation ULID
     * @param destinationClinicUid destination clinic loose uid
     * @param reason              free-text rationale (nullable)
     * @param ctx                 transaction audit context
     * @return the created ConsultationTransferDto
     */
    @Override
    @Transactional
    public ConsultationTransferDto raise(String consultationUid,
                                          String destinationClinicUid,
                                          String reason,
                                          TxAuditContext ctx) {
        Consultation source = requireConsultation(consultationUid);

        // Guard (a): source must be IN_PROCESS (PatientServiceImpl.java:2756-2758, verbatim msg)
        if (source.getStatus() != ConsultationStatus.IN_PROCESS) {
            throw new InvalidPatientOperationException(
                    "Can not transfer. Not an active consultation");
        }

        // Guard (b): no existing PENDING transfer for the patient
        // (PatientServiceImpl.java:2764-2767; partial-unique index backstops this)
        if (transferRepository.existsByPatientUidAndStatus(
                source.getPatientUid(), ConsultationTransferStatus.PENDING)) {
            throw new InvalidPatientOperationException(
                    "Can not transfer. Patient already has a pending transfer");
        }

        // Guard (c): DEFERRED STUB — no-PENDING-child-orders guard (C7-C10)
        // TODO: once LabTestRepository, RadiologyRepository, ProcedureRepository,
        //       PrescriptionRepository arrive in C7-C10, add:
        //       if (labTestRepository.existsByConsultationAndStatus(source, PENDING)
        //           || radiologyRepository.existsByConsultationAndStatus(source, PENDING)
        //           || procedureRepository.existsByConsultationAndStatus(source, PENDING)
        //           || prescriptionRepository.existsByConsultationAndStatus(source, NOT_GIVEN)) {
        //           throw new InvalidPatientOperationException("Can not transfer. ...");
        //       }
        // Until C7-C10 are implemented, this guard is absent (documented gap).

        // Guard (d): destination != source clinic (CR-INC05-04 string .equals(), not == on Long)
        if (destinationClinicUid.equals(source.getClinicUid())) {
            throw new InvalidPatientOperationException(
                    "Cannot transfer to the same clinic");
        }

        // Transition source consultation to TRANSFERED, then save
        // (PatientServiceImpl.java:2808 — set status TRANSFERED before saving the transfer)
        source.markTransferred();
        consultationRepository.save(source);
        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, source.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());

        // Create PENDING transfer
        ConsultationTransfer transfer = new ConsultationTransfer(
                source,
                source.getPatientUid(),
                destinationClinicUid,
                reason,
                ctx.dayUid());
        transferRepository.save(transfer);
        auditRecorder.record(AUDIT_ENTITY_TRANSFER, transfer.getUid(),
                AuditAction.CREATE, ctx.actorUsername());

        return transferMapper.toDto(transfer);
    }

    // -------------------------------------------------------------------------
    // CANCEL — POST /uid/{uid}/cancel-transfer
    // -------------------------------------------------------------------------

    /**
     * Cancel a PENDING transfer for a consultation: transfer → CANCELED, source → IN_PROCESS.
     *
     * <p>Guards:
     * <ol>
     *   <li>Source consultation must be TRANSFERED; else SILENT NO-OP return
     *       (legacy parity — PatientServiceImpl.java:2810-2830: the cancel block is inside an
     *       {@code if (status == TRANSFERED)} guard; non-TRANSFERED simply falls through
     *       without error). Return null (controller handles: returns 200 with no body or
     *       the unchanged transfer if found).</li>
     *   <li>A PENDING transfer for the consultation must exist (looked up by
     *       findByConsultationAndStatus). Legacy inner re-check after scoped query is
     *       DEAD CODE — not reproduced.</li>
     * </ol>
     *
     * <p>On success: transfer → CANCELED; source consultation → IN_PROCESS (NOT PENDING —
     * reverts to the pre-transfer active state, PatientServiceImpl.java:2824-2826).
     *
     * @param consultationUid source consultation ULID
     * @param ctx             transaction audit context
     * @return the updated ConsultationTransferDto, or null if silent no-op
     */
    @Override
    @Transactional
    public ConsultationTransferDto cancelByConsultation(String consultationUid,
                                                         TxAuditContext ctx) {
        Consultation source = requireConsultation(consultationUid);

        // If source not TRANSFERED: silent no-op (legacy parity — PatientServiceImpl.java:2810)
        if (source.getStatus() != ConsultationStatus.TRANSFERED) {
            return null;
        }

        Optional<ConsultationTransfer> transferOpt = transferRepository
                .findByConsultationAndStatus(source, ConsultationTransferStatus.PENDING);

        if (transferOpt.isEmpty()) {
            // No PENDING transfer found for this TRANSFERED consultation — no-op
            return null;
        }

        ConsultationTransfer transfer = transferOpt.get();

        // Cancel the transfer
        transfer.cancel();
        transferRepository.save(transfer);
        auditRecorder.record(AUDIT_ENTITY_TRANSFER, transfer.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());

        // Revert source consultation to IN_PROCESS (PatientServiceImpl.java:2824-2826)
        source.revertToInProcess();
        consultationRepository.save(source);
        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, source.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());

        return transferMapper.toDto(transfer);
    }

    // -------------------------------------------------------------------------
    // LIST PENDING — GET /transfers?status=PENDING
    // -------------------------------------------------------------------------

    /**
     * System-wide pending-transfer queue — ALL PENDING transfers, no scope filters.
     *
     * <p>Reproduces legacy {@code findAllByStatus("PENDING")} unscoped by patient/clinic/clinician
     * (PatientResource.java:599). This is the reception/triage queue.
     *
     * @return all PENDING transfers
     */
    @Override
    @Transactional(readOnly = true)
    public List<ConsultationTransferDto> listPending() {
        return transferRepository.findAllByStatus(ConsultationTransferStatus.PENDING)
                .stream()
                .map(transferMapper::toDto)
                .toList();
    }

    // -------------------------------------------------------------------------
    // COMPLETE (seam) — ConsultationTransferCompletion::completePendingTransferOnRebook
    // -------------------------------------------------------------------------

    /**
     * Called by {@code ConsultationBookingServiceImpl.book} when a patient is re-booked.
     *
     * <p>If a PENDING transfer exists for the patient:
     * <ul>
     *   <li>If {@code targetClinicUid} matches {@code transfer.destinationClinicUid} →
     *       mark transfer COMPLETED and return.</li>
     *   <li>If {@code targetClinicUid} does NOT match → throw 422 with the verbatim legacy
     *       message naming the required destination clinic
     *       (PatientServiceImpl.java:431-435).</li>
     * </ul>
     *
     * <p>Runs with propagation MANDATORY — must run inside the booking transaction started
     * by registration's sendToDoctor (ADR-0022 D3, D5). The transfer completion is atomic
     * with the new consultation creation.
     *
     * @param patientUid      loose uid of the patient being re-booked
     * @param targetClinicUid loose uid of the target clinic for the new consultation
     * @param actorUsername   username for audit attribution
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void completePendingTransferOnRebook(String patientUid,
                                                 String targetClinicUid,
                                                 String actorUsername) {
        Optional<ConsultationTransfer> pendingOpt = transferRepository
                .findByPatientUidAndStatus(patientUid, ConsultationTransferStatus.PENDING);

        if (pendingOpt.isEmpty()) {
            // No pending transfer — normal rebook, no seam action needed
            return;
        }

        ConsultationTransfer pending = pendingOpt.get();

        // Guard: target clinic must match the transfer's destination
        // (PatientServiceImpl.java:431-435, verbatim message naming the required clinic)
        if (!pending.getDestinationClinicUid().equals(targetClinicUid)) {
            throw new InvalidPatientOperationException(
                    "Please send the patient to the specified clinic: "
                            + pending.getDestinationClinicUid());
        }

        // Mark transfer COMPLETED — atomic with the new consultation being booked
        pending.complete();
        transferRepository.save(pending);
        auditRecorder.record(AUDIT_ENTITY_TRANSFER, pending.getUid(),
                AuditAction.UPDATE, actorUsername);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + uid));
    }
}
