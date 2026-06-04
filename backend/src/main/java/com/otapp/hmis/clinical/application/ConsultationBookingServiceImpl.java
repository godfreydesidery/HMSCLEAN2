package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.BookConsultationCommand;
import com.otapp.hmis.clinical.api.ConsultationBookingService;
import com.otapp.hmis.clinical.api.ConsultationDto;
import com.otapp.hmis.clinical.api.ConsultationTransferCompletion;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link ConsultationBookingService} (ADR-0022 D3/D6, package-private).
 *
 * <p>Persists the {@link Consultation} aggregate and records its CREATE audit within the
 * caller's transaction (propagation MANDATORY — must run inside registration's @Transactional
 * boundary; ADR-0008 §4: no async, no REQUIRES_NEW).
 *
 * <p>The booking pre-pass {@code settled} flag is supplied by the caller
 * ({@code registration.PatientRegistrationProcess.sendToDoctor}) via
 * {@link BookConsultationCommand#settled}:
 * <ul>
 *   <li>{@code true}  — INSURANCE/COVERED or follow-up NONE (auto-pass)</li>
 *   <li>{@code false} — CASH-OPD (must pay before {@code open_consultation})</li>
 * </ul>
 *
 * <p><strong>Transfer-completion seam (inc-05 C3):</strong>
 * Before persisting the new consultation, {@link ConsultationTransferCompletion#completePendingTransferOnRebook}
 * is called. If a PENDING transfer exists for the patient and the target clinic matches the
 * transfer's destination, the transfer is marked COMPLETED in the same transaction. If the
 * target clinic does NOT match, an {@link com.otapp.hmis.shared.error.InvalidPatientOperationException}
 * (422) is thrown, aborting the booking (PatientServiceImpl.java:431-435 — the completion seam).
 * The seam runs with MANDATORY propagation — same tx as the caller.
 *
 * <p>The audit entity-type string {@code "clinical.Consultation"} is the stable identifier
 * used in the audit trail. It moved from {@code "registration.Consultation"} (inc-03)
 * to {@code "clinical.Consultation"} with the ownership transfer (ADR-0022 D6). All NEW
 * consultations created after the V29 migration have this entity type; the inc-03 test rows
 * may still carry {@code "registration.Consultation"} in the audit_logs table — they are not
 * backfilled (audit records are append-only per ADR-0007).
 *
 * <p>Legacy citation: PatientServiceImpl.java:425-511 (do_consultation / consultation creation).
 */
@Service
@RequiredArgsConstructor
class ConsultationBookingServiceImpl implements ConsultationBookingService {

    /** Stable audit entity-type for Consultation after ownership transfer (ADR-0007, ADR-0022). */
    static final String AUDIT_ENTITY_CONSULTATION = "clinical.Consultation";

    private final ConsultationRepository consultationRepository;
    private final AuditRecorder auditRecorder;
    private final ConsultationMapper consultationMapper;
    private final ConsultationTransferCompletion transferCompletion;

    /**
     * {@inheritDoc}
     *
     * <p>Runs in the caller's transaction (MANDATORY propagation — the registration
     * {@code sendToDoctor} transaction is the owner; ADR-0008 §4).
     *
     * <p>Transfer-completion seam: before creating the new consultation, checks for a PENDING
     * transfer for the patient. If one exists and the target clinic matches the transfer's
     * destination, the transfer is marked COMPLETED in the same transaction. If the clinic
     * does NOT match, throws 422 to abort the booking (PatientServiceImpl.java:431-435).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsultationDto book(BookConsultationCommand cmd, TxAuditContext ctx) {

        // Transfer-completion seam (inc-05 C3, PatientServiceImpl.java:431-435).
        // If a PENDING transfer exists for this patient, the target clinic MUST match its
        // destination — else 422. On match: transfer is marked COMPLETED atomically here.
        // If no pending transfer: no-op.
        transferCompletion.completePendingTransferOnRebook(
                cmd.patientUid(),
                cmd.clinicUid(),
                ctx.actorUsername());

        Consultation consultation = new Consultation(
                cmd.patientUid(),
                cmd.visitUid(),
                cmd.clinicUid(),
                cmd.clinicianUserUid(),
                cmd.patientBillUid(),
                cmd.paymentMode(),
                cmd.followUp(),
                cmd.settled(),
                cmd.membershipNo(),
                cmd.insurancePlanUid(),
                cmd.businessDayUid());

        consultationRepository.save(consultation);

        // Audit the Consultation CREATE in the same transaction (ADR-0007)
        auditRecorder.record(AUDIT_ENTITY_CONSULTATION, consultation.getUid(),
                AuditAction.CREATE, ctx.actorUsername());

        return consultationMapper.toDto(consultation);
    }
}
