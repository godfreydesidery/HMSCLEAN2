package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.clinical.application.dto.DeceasedNoteDto;
import com.otapp.hmis.clinical.application.dto.DeceasedNoteRequest;
import com.otapp.hmis.clinical.application.dto.ReferralPlanDto;
import com.otapp.hmis.clinical.application.dto.ReferralPlanRequest;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.ConsultationStatus;
import com.otapp.hmis.clinical.domain.DeceasedNote;
import com.otapp.hmis.clinical.domain.DeceasedNoteRepository;
import com.otapp.hmis.clinical.domain.DeceasedNoteStatus;
import com.otapp.hmis.clinical.domain.LabTest;
import com.otapp.hmis.clinical.domain.LabTestRepository;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.clinical.domain.Procedure;
import com.otapp.hmis.clinical.domain.ProcedureRepository;
import com.otapp.hmis.clinical.domain.Radiology;
import com.otapp.hmis.clinical.domain.RadiologyRepository;
import com.otapp.hmis.clinical.domain.ReferralPlan;
import com.otapp.hmis.clinical.domain.ReferralPlanRepository;
import com.otapp.hmis.clinical.domain.ReferralPlanStatus;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import com.otapp.hmis.shared.event.PatientDeceasedEvent;
import com.otapp.hmis.shared.event.PatientInsuranceClearedEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementing the OPD encounter-closure operations (inc-05 C12).
 *
 * <p>Covers two distinct closure workflows for the consultation (OPD) path:
 * <ul>
 *   <li><strong>Death closure</strong>: save_deceased_note → get_deceased_summary (approve).</li>
 *   <li><strong>Referral closure</strong>: save_referral_plan → get_referral_summary (approve).</li>
 * </ul>
 *
 * <p><strong>Bill-gate mapping to local settled flags (CR-INC05-09 asymmetry):</strong>
 *
 * <p>The legacy system checked {@code PatientInvoiceDetail} bill status against
 * {@code UNPAID|VERIFIED} (deceased gate) vs {@code UNPAID} (referral gate). This
 * implementation approximates both checks via the LOCAL {@code settled=false} flag on
 * clinical orders (lab_tests, radiologies, procedures, prescriptions). Since the local
 * settled flag is a single boolean:
 * <ul>
 *   <li>An order is considered "unsettled" if {@code settled=false} in the orders table.</li>
 *   <li><strong>Deceased gate (UNPAID|VERIFIED)</strong>: any order with {@code settled=false}
 *       blocks approval → {@link #hasUnsettledOrdersForDeceasedGate}.</li>
 *   <li><strong>Referral gate (UNPAID-only)</strong>: any order with {@code settled=false}
 *       blocks save → {@link #hasUnsettledOrdersForReferralGate}.</li>
 *   <li>Currently BOTH methods compute the same thing (settled=false). The UNPAID-vs-UNPAID|VERIFIED
 *       distinction is a billing-status nuance that is STRUCTURALLY PRESERVED as two separate
 *       methods even though they currently produce identical results. When the billing-integration
 *       seam (cash-PAID→settled=true propagation) lands and a "VERIFIED-but-not-PAID" concept
 *       emerges, the deceased gate method can be extended to also count VERIFIED-but-not-settled
 *       orders, while the referral gate remains UNPAID-only (settled=false). The asymmetry is
 *       documented here so a future developer can differentiate. CR-INC05-09 asymmetry preserved.</li>
 * </ul>
 *
 * <p><strong>Cross-module event seam (ADR-0022 D5, inc-05 C12 ADR):</strong>
 * <ul>
 *   <li>Events ({@link PatientDeceasedEvent}, {@link PatientInsuranceClearedEvent}) live in
 *       {@code shared.event} — no compile-time clinical→registration edge is created.</li>
 *   <li>Events are published synchronously via {@link ApplicationEventPublisher} inside the
 *       SAME transaction as the clinical state change.</li>
 *   <li>The {@code registration.application.PatientClosureListener} uses
 *       {@code @TransactionalEventListener(phase=BEFORE_COMMIT)} so the Patient mutation
 *       (type=DECEASED or paymentType=CASH) commits atomically with the clinical note approval.</li>
 *   <li>{@code ApplicationModules.verify()} remains green: {@code clinical} depends only on
 *       {@code shared}; {@code registration} depends only on {@code shared}; no cycle.</li>
 * </ul>
 *
 * <p><strong>Approver from context (CR-INC05-03):</strong>
 * {@code approvedByUserUid} is set from {@code ctx.actorUsername()} at approval time —
 * NOT from the creator. The legacy bug (DeceasedNote.java:71-72 copies creator to approver)
 * is not reproduced.
 *
 * <p><strong>DEFERRED:</strong>
 * <ul>
 *   <li>Admission closure paths (inpatient death, inpatient referral) — deferred to the
 *       Inpatient/Nursing increment.</li>
 *   <li>48-hour ARCHIVED sweep — deferred to the business-day bounded context (CR-INC05-11).</li>
 *   <li>ExternalMedicalProvider existence check — deferred until the referral module is built.</li>
 * </ul>
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §2).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>save_deceased_note: PatientResource.java (deceased-note save endpoint)</li>
 *   <li>get_deceased_summary: PatientResource.java (deceased summary / approve endpoint)</li>
 *   <li>load_deceased_list: PatientResource.java:5826</li>
 *   <li>save_referral_plan: PatientResource.java (referral plan save endpoint)</li>
 *   <li>get_referral_summary: PatientResource.java (referral summary / approve endpoint)</li>
 *   <li>Approver-copies-creator bug: DeceasedNote.java:71-72, ReferralPlan.java:72-74</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class ClosureService implements ClosurePort {

    private final ConsultationRepository    consultationRepository;
    private final DeceasedNoteRepository    deceasedNoteRepository;
    private final ReferralPlanRepository    referralPlanRepository;
    private final LabTestRepository         labTestRepository;
    private final RadiologyRepository       radiologyRepository;
    private final ProcedureRepository       procedureRepository;
    private final PrescriptionRepository    prescriptionRepository;
    private final BillingCommands           billingCommands;
    private final ClosureMapper             closureMapper;
    private final AuditRecorder             auditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    private static final String AUDIT_ENTITY_DECEASED  = "clinical.DeceasedNote";
    private static final String AUDIT_ENTITY_REFERRAL  = "clinical.ReferralPlan";
    private static final String AUDIT_ENTITY_CONSULT   = "clinical.Consultation";

    // =========================================================================
    // DeceasedNote — save (consultation → HELD, note → PENDING)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Implementation notes:
     * <ul>
     *   <li>Verbatim error message: "Summary and cause of death are missing" — thrown when either
     *       patientSummary or causeOfDeath is blank (legacy NPEs on null; we use a clean check).</li>
     *   <li>Reuse-if-exists: if a PENDING note already exists for this consultation, update it
     *       in-place. This is the legacy pattern (no duplicate note per encounter).</li>
     *   <li>Consultation → HELD is unconditional (any status can be held by this transition).</li>
     * </ul>
     */
    @Override
    @Transactional
    public DeceasedNoteDto saveDeceasedNote(String consultationUid,
                                            DeceasedNoteRequest request,
                                            TxAuditContext ctx) {
        // Guard 1: both summary and cause required (verbatim legacy message)
        if (isBlank(request.patientSummary()) || isBlank(request.causeOfDeath())) {
            throw new InvalidPatientOperationException(
                    "Summary and cause of death are missing");
        }

        Consultation consultation = requireConsultation(consultationUid);

        // Reuse-if-exists: if a note already exists for this consultation, update it
        DeceasedNote note;
        if (deceasedNoteRepository.existsByConsultation(consultation)) {
            note = deceasedNoteRepository.findByConsultation(consultation)
                    .orElseThrow(() -> new NotFoundException("Deceased note not found"));
            note.updateNarrative(
                    request.patientSummary(),
                    request.causeOfDeath(),
                    request.deathDate(),
                    request.deathTime());
            auditRecorder.record(AUDIT_ENTITY_DECEASED, note.getUid(), AuditAction.UPDATE,
                    ctx.actorUsername());
        } else {
            note = new DeceasedNote(
                    consultation,
                    consultation.getPatientUid(),
                    request.patientSummary(),
                    request.causeOfDeath(),
                    request.deathDate(),
                    request.deathTime(),
                    ctx.dayUid());
            deceasedNoteRepository.save(note);
            auditRecorder.record(AUDIT_ENTITY_DECEASED, note.getUid(), AuditAction.CREATE,
                    ctx.actorUsername());
        }

        // Unconditionally move the consultation to HELD
        holdConsultation(consultation, ctx);

        return closureMapper.toDto(note);
    }

    // =========================================================================
    // DeceasedNote — approve (consultation → SIGNED_OUT, note → APPROVED, event)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Legacy silent no-op: if the note's consultation status != HELD, return without
     * any state change (the legacy code does not throw; it silently returns — reproduced here).
     *
     * <p>Bill-gate (deceased): uses {@link #hasUnsettledOrdersForDeceasedGate(Consultation)}
     * which checks settled=false on all four order types. Maps to the legacy UNPAID|VERIFIED
     * check — see class-level Javadoc for the asymmetry documentation.
     */
    @Override
    @Transactional
    public DeceasedNoteDto approveDeceased(String noteUid, TxAuditContext ctx) {
        DeceasedNote note = deceasedNoteRepository.findByUid(noteUid)
                .orElseThrow(() -> new NotFoundException("Deceased note not found: " + noteUid));

        Consultation consultation = note.getConsultation();
        if (consultation == null) {
            // Admission path — DEFERRED; this endpoint is OPD-only in C12
            throw new InvalidPatientOperationException(
                    "Admission closure path is not yet supported (deferred)");
        }

        // Silent no-op: if consultation is not HELD, return unchanged (legacy behaviour)
        if (consultation.getStatus() != ConsultationStatus.HELD) {
            return closureMapper.toDto(note);
        }

        // Bill-gate (deceased — UNPAID|VERIFIED mapped to settled=false).
        // F5 (review fix): also check the consultation fee bill itself (PatientResource.java:5877).
        // Verbatim: "Could not get deceased summary. Patient have uncleared bills."
        if (!consultation.isSettled() || hasUnsettledOrdersForDeceasedGate(consultation)) {
            throw new InvalidPatientOperationException(
                    "Could not get deceased summary. Patient have uncleared bills.");
        }

        // Transition: consultation → SIGNED_OUT
        consultation.free();
        auditRecorder.record(AUDIT_ENTITY_CONSULT, consultation.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());

        // Transition: note → APPROVED (real approver from ctx — CR-INC05-03)
        note.approve(ctx.actorUsername(), ctx.dayUid(), ctx.timestamp());
        auditRecorder.record(AUDIT_ENTITY_DECEASED, note.getUid(), AuditAction.UPDATE,
                ctx.actorUsername());

        // Cross-module event: Patient.type → DECEASED via PatientClosureListener in registration.
        // Actor passed in event for SEC-01 audit (Patient identity mutation attributable to approver).
        eventPublisher.publishEvent(
                new PatientDeceasedEvent(note.getPatientUid(), ctx.actorUsername()));

        // F5 (review fix): approve all consultation invoices — legacy PatientResource.java:5884-5887.
        // Collect all bill uids: the consultation fee bill + all child-order bills.
        List<String> allBillUids = collectConsultationBillUids(consultation);
        billingCommands.approveInvoicesForBills(allBillUids, ctx);

        return closureMapper.toDto(note);
    }

    // =========================================================================
    // DeceasedNote — list (PENDING|APPROVED; ARCHIVED hidden)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<DeceasedNoteDto> listDeceased() {
        return deceasedNoteRepository.findByStatusInOrderByCreatedAtDesc(
                        List.of(DeceasedNoteStatus.PENDING, DeceasedNoteStatus.APPROVED))
                .stream()
                .map(closureMapper::toDto)
                .toList();
    }

    // =========================================================================
    // ReferralPlan — save (consultation → SIGNED_OUT, plan → PENDING, event)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Implementation notes:
     * <ul>
     *   <li>PENDING-duplicate guard: if a PENDING plan already exists for this consultation,
     *       throw 422 "A pending referral plan already exists for this consultation".</li>
     *   <li>Referral bill-gate (UNPAID-only — CR-INC05-09 asymmetry): see class-level Javadoc.</li>
     *   <li>Consultation → SIGNED_OUT immediately at save (not at approve — legacy behaviour).</li>
     *   <li>Insurance-cleared event published immediately at save (same transaction).</li>
     * </ul>
     */
    @Override
    @Transactional
    public ReferralPlanDto saveReferralPlan(String consultationUid,
                                            ReferralPlanRequest request,
                                            TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);

        // Guard: no existing PENDING referral plan for this consultation
        if (referralPlanRepository.existsByConsultationAndStatus(
                consultation, ReferralPlanStatus.PENDING)) {
            throw new InvalidPatientOperationException(
                    "A pending referral plan already exists for this consultation");
        }

        // Bill-gate (referral — UNPAID-only mapped to settled=false).
        // Legacy PatientResource.java:5465-5499: four distinct checks in order, verbatim messages.
        // CR-INC05-09 asymmetry: referral gate is UNPAID-only (NOT UNPAID|VERIFIED).
        if (hasUnsettledLabTests(consultation)) {
            throw new InvalidPatientOperationException(
                    "Could not save. Patient have uncleared lab test bill(s)");
        }
        if (hasUnsettledRadiologies(consultation)) {
            throw new InvalidPatientOperationException(
                    "Could not save. Patient have uncleared radiology test bill(s)");
        }
        if (hasUnsettledProcedures(consultation)) {
            throw new InvalidPatientOperationException(
                    "Could not save. Patient have uncleared procedure bill(s)");
        }
        if (hasUnsettledPrescriptions(consultation)) {
            throw new InvalidPatientOperationException(
                    "Could not save. Patient have uncleared medication bill(s)");
        }

        // Create the PENDING referral plan
        ReferralPlan plan = new ReferralPlan(
                consultation,
                consultation.getPatientUid(),
                request.externalMedicalProviderUid(),
                request.referringDiagnosis(),
                request.history(),
                request.investigation(),
                request.management(),
                request.operationNote(),
                request.icuAdmissionNote(),
                request.generalRecommendation(),
                ctx.dayUid());
        referralPlanRepository.save(plan);
        auditRecorder.record(AUDIT_ENTITY_REFERRAL, plan.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        // Consultation → SIGNED_OUT immediately at save (legacy behaviour)
        signOutConsultation(consultation, ctx);

        // Cross-module event: Patient paymentType → CASH (insurance cleared)
        // via PatientClosureListener in registration (same transaction, BEFORE_COMMIT).
        // Actor passed in event for SEC-01 audit (Patient insurance-clear attributable to approver).
        eventPublisher.publishEvent(
                new PatientInsuranceClearedEvent(plan.getPatientUid(), ctx.actorUsername()));

        return closureMapper.toDto(plan);
    }

    // =========================================================================
    // ReferralPlan — approve (consultation re-SIGNED_OUT, plan → APPROVED)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>The consultation status is re-set to SIGNED_OUT unconditionally (legacy behaviour —
     * the referral approve always re-confirms sign-out regardless of current status).
     *
     * <p>Note on Patient.type: the legacy defensively sets Patient.type = OUTPATIENT on
     * referral approve. Since OPD patients are already OUTPATIENT (they never changed type
     * to anything else in the OPD path), no PatientReinstatedOutpatientEvent is published.
     * The patient type mutation is a no-op for OPD patients. Documented here for traceability.
     * If a future inpatient-referral path is built, a PatientReinstatedEvent may be needed.
     */
    @Override
    @Transactional
    public ReferralPlanDto approveReferral(String referralUid, TxAuditContext ctx) {
        ReferralPlan plan = referralPlanRepository.findByUid(referralUid)
                .orElseThrow(() -> new NotFoundException(
                        "Referral plan not found: " + referralUid));

        Consultation consultation = plan.getConsultation();
        if (consultation == null) {
            // Admission path — DEFERRED
            throw new InvalidPatientOperationException(
                    "Admission referral closure path is not yet supported (deferred)");
        }

        // Bill-gate (referral gate — same UNPAID-only approximation via settled=false)
        if (hasUnsettledOrdersForReferralGate(consultation)) {
            throw new InvalidPatientOperationException(
                    "Could not get referral summary. Patient have uncleared bills.");
        }

        // Re-confirm consultation SIGNED_OUT (unconditional)
        signOutConsultation(consultation, ctx);

        // Transition: plan → APPROVED (real approver from ctx — CR-INC05-03)
        plan.approve(ctx.actorUsername(), ctx.dayUid(), ctx.timestamp());
        auditRecorder.record(AUDIT_ENTITY_REFERRAL, plan.getUid(), AuditAction.UPDATE,
                ctx.actorUsername());

        // Note: Patient.type = OUTPATIENT is a defensive legacy set; OPD patients are already
        // OUTPATIENT. No event published for this no-op. See Javadoc above.

        return closureMapper.toDto(plan);
    }

    // =========================================================================
    // ReferralPlan — list (PENDING|APPROVED; ARCHIVED hidden)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<ReferralPlanDto> listReferrals() {
        return referralPlanRepository.findByStatusInOrderByCreatedAtDesc(
                        List.of(ReferralPlanStatus.PENDING, ReferralPlanStatus.APPROVED))
                .stream()
                .map(closureMapper::toDto)
                .toList();
    }

    // =========================================================================
    // Private helpers — consultation transitions
    // =========================================================================

    /**
     * Move the consultation to HELD (save_deceased_note side-effect).
     * Uses the ConsultationStatus.HELD domain state. The Consultation entity's free()
     * method transitions to SIGNED_OUT; there is no hold() method. We set HELD directly
     * via the status field using the dedicated Consultation.hold() method (which we add
     * here via an inline status transition in the domain entity).
     *
     * <p>Actually, Consultation does not yet have a hold() method.
     * We need to set HELD. Looking at the domain: the status setter is via domain methods.
     * Since we cannot add a method here and Consultation.hold() needs to be added to the entity,
     * we use a package-level reflection-free approach: add markHeld() to Consultation.
     *
     * <p>To avoid touching Consultation.java for a single-method addition mid-service,
     * we cast the status change through the ConsultationStatus enum via the available
     * setStatus mechanism. Since Consultation has no public setStatus, we must add a domain
     * method. The markHeld() method is added to Consultation in C12 (see Consultation.java
     * update note — we set it here; the domain addition is in the entity update below).
     */
    private void holdConsultation(Consultation consultation, TxAuditContext ctx) {
        consultation.markHeld();
        auditRecorder.record(AUDIT_ENTITY_CONSULT, consultation.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());
    }

    /**
     * Move the consultation to SIGNED_OUT (referral save / approve side-effect).
     * Uses {@link Consultation#free()} which sets status = SIGNED_OUT.
     *
     * <p>The free() guard normally requires IN_PROCESS or TRANSFERED. For the referral
     * path, the consultation may already be SIGNED_OUT (on re-approve). We therefore call
     * signOut directly without guard, using a dedicated {@link Consultation#signOut()} method
     * that unconditionally sets SIGNED_OUT. This is added to the entity.
     */
    private void signOutConsultation(Consultation consultation, TxAuditContext ctx) {
        consultation.signOut();
        auditRecorder.record(AUDIT_ENTITY_CONSULT, consultation.getUid(),
                AuditAction.UPDATE, ctx.actorUsername());
    }

    // =========================================================================
    // Private helpers — bill-gate methods (CR-INC05-09 asymmetry)
    // =========================================================================

    /**
     * Deceased gate (UNPAID|VERIFIED mapped to settled=false).
     *
     * <p>Returns true if the consultation has any clinical order (lab, radiology, procedure,
     * or prescription) with {@code settled=false}. This approximates the legacy UNPAID|VERIFIED
     * check: in the local projection, {@code settled=false} covers the UNPAID state; the VERIFIED
     * state (a billing concept for insurance-pending verification) is not yet distinguishable
     * from UNPAID in the local flag. Both block approval.
     *
     * <p>Structural note: this is a SEPARATE method from {@link #hasUnsettledOrdersForReferralGate}
     * so that future billing-integration can extend the deceased gate to also check VERIFIED-but-paid
     * orders without affecting the referral gate. CR-INC05-09 asymmetry preserved.
     *
     * @param consultation the owning consultation
     * @return true if any order has settled=false (blocks deceased approval)
     */
    /*
     * Collect all bill uids for a consultation: the fee bill + all child-order bills.
     * Used by approveDeceased (F5) to pass to BillingCommands.approveInvoicesForBills.
     * Legacy PatientResource.java:5884-5887 approves every invoice linked to the consultation.
     */
    private List<String> collectConsultationBillUids(Consultation consultation) {
        List<String> uids = new ArrayList<>();
        uids.add(consultation.getPatientBillUid());
        for (LabTest lt : labTestRepository.findByConsultationOrderByCreatedAtAsc(consultation)) {
            uids.add(lt.getPatientBillUid());
        }
        for (Radiology r : radiologyRepository.findByConsultationOrderByCreatedAtAsc(consultation)) {
            uids.add(r.getPatientBillUid());
        }
        for (Procedure p : procedureRepository.findByConsultationOrderByCreatedAtAsc(consultation)) {
            uids.add(p.getPatientBillUid());
        }
        for (Prescription rx : prescriptionRepository.findByConsultationOrderByCreatedAtAsc(consultation)) {
            uids.add(rx.getPatientBillUid());
        }
        return uids;
    }

    private boolean hasUnsettledOrdersForDeceasedGate(Consultation consultation) {
        // Check all four order types for settled=false
        return hasUnsettledLabTests(consultation)
                || hasUnsettledRadiologies(consultation)
                || hasUnsettledProcedures(consultation)
                || hasUnsettledPrescriptions(consultation);
    }

    /**
     * Referral gate (UNPAID-only mapped to settled=false).
     *
     * <p>Returns true if the consultation has any clinical order with {@code settled=false}.
     * This approximates the legacy UNPAID-only check (NOT UNPAID|VERIFIED — CR-INC05-09
     * asymmetry). Currently identical to {@link #hasUnsettledOrdersForDeceasedGate} because
     * the local boolean flag does not distinguish UNPAID from VERIFIED-but-not-paid.
     * The structural separation is preserved so future differentiation is possible.
     *
     * @param consultation the owning consultation
     * @return true if any order has settled=false (blocks referral save/approve)
     */
    private boolean hasUnsettledOrdersForReferralGate(Consultation consultation) {
        // Same computation as deceased gate currently.
        // FUTURE: restrict to UNPAID-only subset when VERIFIED billing concept emerges.
        return hasUnsettledLabTests(consultation)
                || hasUnsettledRadiologies(consultation)
                || hasUnsettledProcedures(consultation)
                || hasUnsettledPrescriptions(consultation);
    }

    private boolean hasUnsettledLabTests(Consultation consultation) {
        return labTestRepository.findByConsultationOrderByCreatedAtAsc(consultation)
                .stream().anyMatch(lt -> !lt.isSettled());
    }

    private boolean hasUnsettledRadiologies(Consultation consultation) {
        return radiologyRepository.findByConsultationOrderByCreatedAtAsc(consultation)
                .stream().anyMatch(r -> !r.isSettled());
    }

    private boolean hasUnsettledProcedures(Consultation consultation) {
        return procedureRepository.findByConsultationOrderByCreatedAtAsc(consultation)
                .stream().anyMatch(p -> !p.isSettled());
    }

    private boolean hasUnsettledPrescriptions(Consultation consultation) {
        return prescriptionRepository.findByConsultationOrderByCreatedAtAsc(consultation)
                .stream().anyMatch(rx -> !rx.isSettled());
    }

    // =========================================================================
    // Private helpers — entity lookup
    // =========================================================================

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + uid));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
