package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.ConsultationSignOut;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link ConsultationSignOut} — package-private (ADR-0014 §2).
 *
 * <p>Signs out open OPD consultations on inpatient admission. This seam is called by the
 * {@code inpatient} module through the {@code clinical.api.ConsultationSignOut} port.
 *
 * <p><strong>Insurance path (no-top-up activate-at-admit):</strong>
 * Reproduces {@code PatientServiceImpl.java:1951-1958} — fetches all consultations in
 * PENDING or IN_PROCESS and calls {@link Consultation#signOut()} on each, saving them.
 * The audit record mirrors {@link ClosureService#signOutConsultation}.
 *
 * <p><strong>CASH path (payment-driven activation):</strong>
 * Reproduces {@code PatientBillResource.java:353-364} — fetches only IN_PROCESS consultations
 * (the legacy uses {@code findAllByPatientAndStatus(patient, "IN-PROCESS")} — single-status,
 * not the widened PENDING+IN_PROCESS set used in the insurance path).
 *
 * <p>Both methods run with {@code Propagation.REQUIRED}: they join the caller's transaction
 * so that the sign-out and the admission state change commit atomically.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Insurance no-top-up branch: PatientServiceImpl.java:1951-1958</li>
 *   <li>Cash payment-driven branch: PatientBillResource.java:353-364</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class ConsultationSignOutImpl implements ConsultationSignOut {

    private static final Logger log = LoggerFactory.getLogger(ConsultationSignOutImpl.class);
    private static final String AUDIT_ENTITY = "clinical.Consultation";

    private final ConsultationRepository consultationRepository;
    private final AuditRecorder          auditRecorder;

    /**
     * {@inheritDoc}
     *
     * <p>Fetches PENDING + IN_PROCESS consultations for the patient and signs them all out.
     * Legacy PatientServiceImpl.java:1951-1958: status list = ["PENDING", "IN-PROCESS"].
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void signOutOpenConsultations(String patientUid, TxAuditContext ctx) {
        List<Consultation> open = consultationRepository.findAllByPatientUidAndStatusIn(
                patientUid,
                List.of(ConsultationStatus.PENDING, ConsultationStatus.IN_PROCESS));

        if (open.isEmpty()) {
            log.debug("ConsultationSignOutImpl: no open consultations for patientUid={} "
                    + "(insurance no-top-up admit path)", patientUid);
            return;
        }

        for (Consultation c : open) {
            c.signOut();
            auditRecorder.record(AUDIT_ENTITY, c.getUid(), AuditAction.UPDATE,
                    ctx.actorUsername());
        }
        log.debug("ConsultationSignOutImpl: signed out {} consultation(s) for patientUid={} "
                + "(insurance no-top-up admit path)", open.size(), patientUid);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches only IN_PROCESS consultations for the patient and signs them out.
     * Legacy PatientBillResource.java:353: status = "IN-PROCESS" (single-status — narrower than
     * the insurance path which also includes PENDING).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void signOutInProcessConsultations(String patientUid, TxAuditContext ctx) {
        List<Consultation> inProcess = consultationRepository.findAllByPatientUidAndStatusIn(
                patientUid,
                List.of(ConsultationStatus.IN_PROCESS));

        if (inProcess.isEmpty()) {
            log.debug("ConsultationSignOutImpl: no IN_PROCESS consultations for patientUid={} "
                    + "(cash payment-driven admit path)", patientUid);
            return;
        }

        for (Consultation c : inProcess) {
            c.signOut();
            auditRecorder.record(AUDIT_ENTITY, c.getUid(), AuditAction.UPDATE,
                    ctx.actorUsername());
        }
        log.debug("ConsultationSignOutImpl: signed out {} IN_PROCESS consultation(s) for "
                + "patientUid={} (cash payment-driven admit path)", inProcess.size(), patientUid);
    }
}
