package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.NonConsultationDto;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.NonConsultationStatus;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walk-in track service for the {@link NonConsultation} aggregate (inc-05 C4).
 *
 * <p>Implements two lifecycle operations:
 * <ol>
 *   <li>{@link #getOrCreateInProcess} — get-or-create an IN_PROCESS walk-in encounter for an
 *       OUTSIDER patient placing a direct lab/radiology/procedure order (no doctor, no bill gate).
 *       This is the API that the C7-C9 order-save paths will call (DEFERRED to those chunks).</li>
 *   <li>{@link #signOut} — close an IN_PROCESS walk-in encounter: IN_PROCESS → SIGNED_OUT
 *       (PatientResource.java:350).</li>
 * </ol>
 *
 * <p><strong>get-or-create behaviour (legacy parity, inc-05 C4 build-spec §2):</strong>
 * The legacy PatientServiceImpl.java:790-806 checks:
 * <ul>
 *   <li>If the patient has an existing IN_PROCESS NonConsultation → reuse it (no duplicate).</li>
 *   <li>Otherwise → create a new one IN_PROCESS.</li>
 * </ul>
 * The legacy code also has a defensive PENDING branch
 * ({@code if status==PENDING set IN-PROCESS else if IN-PROCESS reuse}) — but PENDING is never
 * written as a NonConsultation creation status; the observed written statuses are only IN-PROCESS
 * and SIGNED-OUT. This implementation reproduces the observable behaviour: get-or-create returns
 * the patient's current IN_PROCESS NonConsultation or creates a new one IN_PROCESS.
 *
 * <p><strong>DEFERRED — order-save wiring (inc-05 C4 build-spec §3):</strong>
 * The actual wiring of {@code getOrCreateInProcess} into the lab/radiology/procedure order-save
 * paths (PatientServiceImpl.java:790-806, :1033-1048, :1280-1296) is deferred to C7-C9 when
 * those order entities are built. This service is complete and ready to be called; it is exercised
 * by the C4 controller endpoints.
 *
 * <p>Package-private — not part of the module's public API surface.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Lab get-or-create:        PatientServiceImpl.java:790-806</li>
 *   <li>Radiology get-or-create:  PatientServiceImpl.java:1033-1048</li>
 *   <li>Procedure get-or-create:  PatientServiceImpl.java:1280-1296</li>
 *   <li>Sign-out:                 PatientResource.java:350</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class WalkInService implements WalkInPort {

    private final NonConsultationRepository nonConsultationRepository;
    private final AuditRecorder auditRecorder;
    private final NonConsultationMapper nonConsultationMapper;

    private static final String AUDIT_ENTITY = "clinical.NonConsultation";

    // -------------------------------------------------------------------------
    // get-or-create IN_PROCESS
    // -------------------------------------------------------------------------

    /**
     * Get or create an IN_PROCESS walk-in encounter for the patient.
     *
     * <p>If the patient already has an IN_PROCESS NonConsultation, return it (idempotent reuse —
     * no duplicate created). Otherwise create a new one IN_PROCESS.
     *
     * <p>The legacy checks for PENDING status as a defensive branch; that branch is a dead path
     * (NonConsultations are never created PENDING — they go straight to IN_PROCESS). This
     * implementation reproduces the observable behaviour only: reuse IN_PROCESS or create new.
     *
     * @param patientUid       loose uid of the patient (cross-module ref, ADR-0022 D2)
     * @param visitUid         loose uid of the associated visit
     * @param paymentType      CASH, INSURANCE, or '' (default empty)
     * @param membershipNo     insurance membership number (empty for CASH)
     * @param insurancePlanUid loose uid of the insurance plan (null for CASH)
     * @param ctx              transaction audit context
     * @return the existing or newly-created IN_PROCESS NonConsultationDto
     */
    @Override
    @Transactional
    public NonConsultationDto getOrCreateInProcess(String patientUid, String visitUid,
                                                   String paymentType, String membershipNo,
                                                   String insurancePlanUid, TxAuditContext ctx) {
        // Look for an existing IN_PROCESS encounter for this patient (reuse — no duplicate)
        return nonConsultationRepository
                .findByPatientUidAndStatus(patientUid, NonConsultationStatus.IN_PROCESS)
                .map(nonConsultationMapper::toDto)
                .orElseGet(() -> {
                    // No existing IN_PROCESS — create a new one
                    NonConsultation nc = new NonConsultation(
                            patientUid,
                            visitUid,
                            paymentType != null ? paymentType : "",
                            membershipNo != null ? membershipNo : "",
                            insurancePlanUid,
                            ctx.dayUid());
                    NonConsultation saved = nonConsultationRepository.save(nc);
                    auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                            ctx.actorUsername());
                    return nonConsultationMapper.toDto(saved);
                });
    }

    // -------------------------------------------------------------------------
    // signOut: IN_PROCESS → SIGNED_OUT
    // -------------------------------------------------------------------------

    /**
     * Sign out an IN_PROCESS walk-in encounter: IN_PROCESS → SIGNED_OUT.
     *
     * <p>Guards:
     * <ol>
     *   <li>NonConsultation must exist; else 404.</li>
     *   <li>Status must be IN_PROCESS; else 422 "Walk-in encounter is not open"
     *       (parity: PatientResource.java:350 signs out without an explicit guard message —
     *       we add a guard to prevent double sign-out or signing out an already-closed encounter).</li>
     * </ol>
     *
     * @param nonConsultationUid the ULID of the NonConsultation
     * @param ctx                transaction audit context
     * @return the updated NonConsultationDto (status = SIGNED-OUT)
     * @throws NotFoundException                if no non-consultation with the given uid exists
     * @throws InvalidPatientOperationException (422) if not IN_PROCESS
     */
    @Override
    @Transactional
    public NonConsultationDto signOut(String nonConsultationUid, TxAuditContext ctx) {
        NonConsultation nc = requireNonConsultation(nonConsultationUid);

        if (nc.getStatus() != NonConsultationStatus.IN_PROCESS) {
            throw new InvalidPatientOperationException("Walk-in encounter is not open");
        }

        nc.signOut();

        auditRecorder.record(AUDIT_ENTITY, nc.getUid(), AuditAction.UPDATE, ctx.actorUsername());

        return nonConsultationMapper.toDto(nc);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Read a single non-consultation by ULID.
     *
     * @param uid the ULID of the NonConsultation
     * @return the NonConsultationDto
     * @throws NotFoundException if no non-consultation with the given uid exists
     */
    @Override
    @Transactional(readOnly = true)
    public NonConsultationDto getByUid(String uid) {
        return nonConsultationMapper.toDto(requireNonConsultation(uid));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private NonConsultation requireNonConsultation(String uid) {
        return nonConsultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("NonConsultation not found: " + uid));
    }
}
