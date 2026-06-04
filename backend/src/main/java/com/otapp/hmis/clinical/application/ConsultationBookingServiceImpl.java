package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.BookConsultationCommand;
import com.otapp.hmis.clinical.api.ConsultationBookingService;
import com.otapp.hmis.clinical.api.ConsultationDto;
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
 * <p>The audit entity-type string {@code "clinical.Consultation"} is the stable identifier
 * used in the audit trail. It moved from {@code "registration.Consultation"} (inc-03)
 * to {@code "clinical.Consultation"} with the ownership transfer (ADR-0022 D6). All NEW
 * consultations created after the V29 migration have this entity type; the inc-03 test rows
 * may still carry {@code "registration.Consultation"} in the audit_logs table — they are not
 * backfilled (audit records are append-only per ADR-0007).
 *
 * <p>Legacy citation: PatientServiceImpl.java:494-511 (consultation creation in do_consultation).
 */
@Service
@RequiredArgsConstructor
class ConsultationBookingServiceImpl implements ConsultationBookingService {

    /** Stable audit entity-type for Consultation after ownership transfer (ADR-0007, ADR-0022). */
    static final String AUDIT_ENTITY_CONSULTATION = "clinical.Consultation";

    private final ConsultationRepository consultationRepository;
    private final AuditRecorder auditRecorder;
    private final ConsultationMapper consultationMapper;

    /**
     * {@inheritDoc}
     *
     * <p>Runs in the caller's transaction (MANDATORY propagation — the registration
     * {@code sendToDoctor} transaction is the owner; ADR-0008 §4).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsultationDto book(BookConsultationCommand cmd, TxAuditContext ctx) {

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
