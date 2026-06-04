package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.api.SettlementPolicy;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.application.dto.ProcedureDto;
import com.otapp.hmis.clinical.application.dto.ProcedureNoteRequest;
import com.otapp.hmis.clinical.application.dto.ProcedureOrderRequest;
import com.otapp.hmis.clinical.application.dto.ProcedureUpdateRequest;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.Procedure;
import com.otapp.hmis.clinical.domain.ProcedureRepository;
import com.otapp.hmis.clinical.domain.ProcedureStatus;
import com.otapp.hmis.masterdata.lookup.ProcedureTypeLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementing the Procedure aggregate lifecycle (inc-05 C9).
 *
 * <p><strong>State machine (Procedure-specific — SIMPLER than LabTest/Radiology):</strong>
 * <ul>
 *   <li>order (PENDING): guards → exactly-one-encounter, duplicate-guard, procedureType exists;
 *       creates billing charge via {@link BillingCommands#recordClinicalCharge} with kind=PROCEDURE;
 *       sets local {@code settled} flag from the ChargeResult. Stamps ordered_* audit.</li>
 *   <li>accept (PENDING|REJECTED → ACCEPTED): NO bill check (CR-INC05-01 parity).
 *       REJECTED is unreachable at runtime (no reject endpoint) but the guard is reproduced.</li>
 *   <li><strong>add_note (ACCEPTED → VERIFIED): THE DISTINCTIVE SETTLEMENT GATE.</strong>
 *       Requires status==ACCEPTED AND note non-blank AND settled==true.
 *       If not settled → 422 "Could not add procedure note. Payment not verified"
 *       (PatientResource.java:3408-3414). This is the ONE in-method bill gate in the order family.</li>
 *   <li>update (ACCEPTED, no status change): edit note/fields when status==ACCEPTED.
 *       Does NOT require settled — only status==ACCEPTED (PatientResource.java:4060-4061).</li>
 *   <li>delete (PENDING only): hard-delete; credit-note DEFERRED.</li>
 * </ul>
 *
 * <p><strong>NO approve, NO reject, NO hold, NO collect endpoints.</strong>
 * The planning-doc M14 "approve" step is FABRICATED. The held_* columns are VESTIGIAL.
 * There is no reject_procedure endpoint in the legacy system.
 *
 * <p><strong>Settlement flag (local projection, CR-INC05-01):</strong>
 * Set at order time from the {@link ChargeResult}:
 * <ul>
 *   <li>{@code true}  — ChargeResult.status IN (COVERED, VERIFIED, NONE)</li>
 *   <li>{@code false} — ChargeResult.status == UNPAID (CASH-OPD / CASH-OUTSIDER)</li>
 * </ul>
 * Re-checked at add_note time (the distinctive procedure gate).
 * The cash-PAID→settled=true propagation is DEFERRED (same pattern as LabTest/Radiology).
 *
 * <p><strong>Worklist (outpatient + outsider only):</strong>
 * No inpatient procedure worklist exists in the legacy system. The worklist filters by
 * settled=true AND status IN (PENDING, ACCEPTED).
 *
 * <p><strong>By-patient (CR-INC05-15 DEFERRED):</strong>
 * The legacy by-patient query excludes admission-scoped procedures. Not an issue in C9
 * since no admission procedures exist yet.
 *
 * <p><strong>DEFERRED — delete credit-note seam:</strong>
 * Same deferral as LabTestService / RadiologyService — no credit-note raised on delete
 * for already-PAID bills.
 *
 * <p><strong>DEFERRED — admission procedure path:</strong>
 * The {@code admissionUid} path is not implemented. Deferred to Inpatient/Nursing increment.
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §2).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Order create:           PatientServiceImpl.java (save_procedure)</li>
 *   <li>Accept:                 PatientResource.java (accept_procedure)</li>
 *   <li>add_note + gate:        PatientResource.java:3408-3414</li>
 *   <li>update_procedure:       PatientResource.java:4060-4061</li>
 *   <li>Worklist:               PatientResource.java (PENDING/ACCEPTED filter + settled)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class ProcedureService implements ProcedurePort {

    private final ProcedureRepository       procedureRepository;
    private final ConsultationRepository    consultationRepository;
    private final NonConsultationRepository nonConsultationRepository;
    private final ProcedureTypeLookup       procedureTypeLookup;
    private final BillingCommands           billingCommands;
    private final AuditRecorder             auditRecorder;
    private final ProcedureMapper           procedureMapper;

    private static final String AUDIT_ENTITY = "clinical.Procedure";
    /** Legacy credit-note reference for a deleted procedure (PatientResource.java:3503). */
    private static final String REF_CANCEL_PROCEDURE = "Canceled procedure";

    // =========================================================================
    // Order creation — consultation path
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guards:
     * <ol>
     *   <li>Consultation must exist (404).</li>
     *   <li>ProcedureType must exist in masterdata (404 "Procedure type not found").</li>
     *   <li>No duplicate type on this consultation (422 verbatim).</li>
     * </ol>
     */
    @Override
    @Transactional
    public ProcedureDto orderForConsultation(String consultationUid,
                                              ProcedureOrderRequest request,
                                              TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        requireProcedureTypeExists(request.procedureTypeUid());
        guardNoDuplicateOnConsultation(consultation, request.procedureTypeUid());

        PaymentMode paymentMode = toPaymentMode(consultation.getPaymentMode().name());
        ChargeRequest chargeRequest = new ChargeRequest(
                consultation.getPatientUid(),
                consultation.getInsurancePlanUid(),
                consultation.getMembershipNo(),
                ServiceKind.PROCEDURE,
                request.procedureTypeUid(),
                BigDecimal.ONE,
                paymentMode,
                false,
                false
        );
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);

        boolean settled = isSettledFromCharge(chargeResult, paymentMode, false);

        Instant now = Instant.now();
        Procedure p = Procedure.forConsultation(
                consultation,
                request.procedureTypeUid(),
                chargeResult.billUid(),
                settled,
                paymentMode.name(),
                consultation.getMembershipNo(),
                consultation.getInsurancePlanUid(),
                request.diagnosisTypeUid(),
                request.clinicianUserUid(),
                request.theatreUid(),
                ctx.actorUsername(),
                ctx.dayUid(),
                now);

        Procedure saved = procedureRepository.save(p);
        auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return procedureMapper.toDto(saved);
    }

    // =========================================================================
    // Order creation — non-consultation (OUTSIDER/walk-in) path
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guards:
     * <ol>
     *   <li>NonConsultation must exist (404).</li>
     *   <li>ProcedureType must exist in masterdata (404 "Procedure type not found").</li>
     *   <li>No duplicate type on this non-consultation (422).</li>
     * </ol>
     */
    @Override
    @Transactional
    public ProcedureDto orderForNonConsultation(String nonConsultationUid,
                                                 ProcedureOrderRequest request,
                                                 TxAuditContext ctx) {
        NonConsultation nonConsultation = requireNonConsultation(nonConsultationUid);
        requireProcedureTypeExists(request.procedureTypeUid());
        guardNoDuplicateOnNonConsultation(nonConsultation, request.procedureTypeUid());

        String patientUid = nonConsultation.getPatientUid();

        String paymentTypeStr = request.paymentType() != null && !request.paymentType().isBlank()
                ? request.paymentType()
                : nonConsultation.getPaymentType();
        String membershipNo = request.membershipNo() != null
                ? request.membershipNo()
                : nonConsultation.getMembershipNo();
        String insurancePlanUid = request.insurancePlanUid() != null
                ? request.insurancePlanUid()
                : nonConsultation.getInsurancePlanUid();

        PaymentMode paymentMode = toPaymentMode(paymentTypeStr);

        ChargeRequest chargeRequest = new ChargeRequest(
                patientUid,
                insurancePlanUid,
                membershipNo,
                ServiceKind.PROCEDURE,
                request.procedureTypeUid(),
                BigDecimal.ONE,
                paymentMode,
                false,
                false
        );
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);

        boolean settled = isSettledFromCharge(chargeResult, paymentMode, false);

        Instant now = Instant.now();
        Procedure p = Procedure.forNonConsultation(
                nonConsultation,
                patientUid,
                request.procedureTypeUid(),
                chargeResult.billUid(),
                settled,
                paymentMode.name(),
                membershipNo,
                insurancePlanUid,
                request.diagnosisTypeUid(),
                request.clinicianUserUid(),
                request.theatreUid(),
                ctx.actorUsername(),
                ctx.dayUid(),
                now);

        Procedure saved = procedureRepository.save(p);
        auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return procedureMapper.toDto(saved);
    }

    // =========================================================================
    // Lifecycle transitions
    // =========================================================================

    /**
     * Accept: PENDING | REJECTED → ACCEPTED.
     *
     * <p>NO bill re-check (CR-INC05-01 parity — settlement only checked at add_note time).
     * REJECTED → ACCEPTED guard reproduced verbatim (though REJECTED is unreachable at runtime).
     * Guard: status must be PENDING or REJECTED (else 422).
     * Message: "Procedure order cannot be accepted at this stage".
     */
    @Override
    @Transactional
    public ProcedureDto accept(String procedureUid, TxAuditContext ctx) {
        Procedure p = requireProcedure(procedureUid);

        if (p.getStatus() != ProcedureStatus.PENDING && p.getStatus() != ProcedureStatus.REJECTED) {
            throw new InvalidPatientOperationException(
                    "Procedure order cannot be accepted at this stage");
        }

        p.accept(ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, p.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return procedureMapper.toDto(p);
    }

    /**
     * Add procedure note and transition to VERIFIED: ACCEPTED → VERIFIED.
     *
     * <p><strong>THE DISTINCTIVE SETTLEMENT GATE (PatientResource.java:3408-3414):</strong>
     * <ol>
     *   <li>Status must be ACCEPTED (else 422 "Please accept the procedure first").</li>
     *   <li>Note must be non-blank (enforced by Bean Validation on {@link ProcedureNoteRequest}).</li>
     *   <li>settled must be true (else 422 "Could not add procedure note. Payment not verified").</li>
     * </ol>
     * The settlement check is delegated to {@link Procedure#addNote} — the domain method owns this gate.
     */
    @Override
    @Transactional
    public ProcedureDto addNote(String procedureUid, ProcedureNoteRequest request,
                                TxAuditContext ctx) {
        Procedure p = requireProcedure(procedureUid);

        if (p.getStatus() != ProcedureStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Please accept the procedure first");
        }

        // Domain method enforces the settlement gate and throws if not settled.
        // Note non-blank is guaranteed by @NotBlank on ProcedureNoteRequest.note.
        p.addNote(request.note(), ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, p.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return procedureMapper.toDto(p);
    }

    // =========================================================================
    // Update (no status change) — ACCEPTED gate
    // =========================================================================

    /**
     * Update procedure fields without status change. Allowed only when status == ACCEPTED.
     *
     * <p>Guard: status must be ACCEPTED (else 422 "Procedure order must be accepted to update").
     * Does NOT require settled — update does not gate on the bill (PatientResource.java:4060-4061).
     */
    @Override
    @Transactional
    public ProcedureDto update(String procedureUid, ProcedureUpdateRequest request,
                               TxAuditContext ctx) {
        Procedure p = requireProcedure(procedureUid);

        if (p.getStatus() != ProcedureStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Procedure order must be accepted to update");
        }

        p.update(request.note(), request.procDate(), request.procTime(),
                request.hours(), request.minutes(), request.diagnosis());
        auditRecorder.record(AUDIT_ENTITY, p.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return procedureMapper.toDto(p);
    }

    // =========================================================================
    // Delete
    // =========================================================================

    /**
     * Hard-delete a PENDING procedure order.
     *
     * <p>Guard: status must be PENDING (else 422 "Only a pending procedure order can be deleted").
     *
     * <p><strong>DEFERRED — credit-note seam:</strong>
     * <p><strong>Bill reversal (credit-note seam — now wired, inc-06A C1, ITEM1):</strong>
     * Before the order row is removed, {@link BillingCommands#cancelCharge} is invoked in the
     * SAME transaction with the legacy reference label {@value #REF_CANCEL_PROCEDURE}: soft-cancel
     * the bill (→ CANCELED), and ONLY when a RECEIVED payment existed, refund it and raise a
     * PENDING credit-note for the full bill amount (CR-10 fix applied; legacy hard-delete of
     * bill/payment NOT reproduced). The clinical ORDER row is still hard-deleted. Legacy:
     * PatientResource.java:3473-3537.
     */
    @Override
    @Transactional
    public void delete(String procedureUid, TxAuditContext ctx) {
        Procedure p = requireProcedure(procedureUid);

        if (p.getStatus() != ProcedureStatus.PENDING) {
            throw new InvalidPatientOperationException(
                    "Could not delete, only a PENDING procedure can be deleted");
        }

        // Reverse the bill BEFORE deleting the order row (ITEM1; legacy 3486-3535).
        billingCommands.cancelCharge(p.getPatientBillUid(), REF_CANCEL_PROCEDURE, ctx);

        procedureRepository.delete(p);
        auditRecorder.record(AUDIT_ENTITY, procedureUid, AuditAction.DELETE, ctx.actorUsername());
    }

    // =========================================================================
    // Queries
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public ProcedureDto getByUid(String procedureUid) {
        return procedureMapper.toDto(requireProcedure(procedureUid));
    }

    /**
     * Procedure worklist — settled orders in actionable statuses {PENDING, ACCEPTED}.
     *
     * <p>Outpatient + outsider only (no inpatient procedure worklist in the legacy system).
     * The settled flag replaces reading billing bill status (CR-INC05-01, ADR-0022 D4).
     *
     * @param statusFilter optional single-status filter (null = all actionable statuses)
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProcedureDto> worklist(ProcedureStatus statusFilter) {
        List<Procedure> procedures;
        if (statusFilter != null) {
            procedures = procedureRepository.findBySettledAndStatusOrderByCreatedAtAsc(
                    true, statusFilter);
        } else {
            procedures = procedureRepository.findBySettledAndStatusInOrderByCreatedAtAsc(
                    true, List.of(ProcedureStatus.PENDING, ProcedureStatus.ACCEPTED));
        }
        return procedureMapper.toDtoList(procedures);
    }

    /**
     * All procedure orders for a patient (by uid), optionally filtered by status.
     *
     * <p><strong>DEFERRED (CR-INC05-15):</strong> The legacy by-patient query omits
     * admission-scoped procedures. No admission procedures exist in C9, so this returns all
     * procedures for the patient.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProcedureDto> byPatient(String patientUid, ProcedureStatus statusFilter) {
        List<Procedure> procedures;
        if (statusFilter != null) {
            procedures = procedureRepository.findByPatientUidAndStatusOrderByCreatedAtDesc(
                    patientUid, statusFilter);
        } else {
            procedures = procedureRepository.findByPatientUidOrderByCreatedAtDesc(patientUid);
        }
        return procedureMapper.toDtoList(procedures);
    }

    /**
     * All procedure orders for a consultation, ordered by creation time ascending.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProcedureDto> listByConsultation(String consultationUid) {
        Consultation consultation = requireConsultation(consultationUid);
        return procedureMapper.toDtoList(
                procedureRepository.findByConsultationOrderByCreatedAtAsc(consultation));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Procedure requireProcedure(String uid) {
        return procedureRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Procedure order not found: " + uid));
    }

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + uid));
    }

    private NonConsultation requireNonConsultation(String uid) {
        return nonConsultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("NonConsultation not found: " + uid));
    }

    private void requireProcedureTypeExists(String procedureTypeUid) {
        if (!procedureTypeLookup.existsByUid(procedureTypeUid)) {
            throw new NotFoundException("Procedure type not found");
        }
    }

    private void guardNoDuplicateOnConsultation(Consultation consultation,
                                                 String procedureTypeUid) {
        if (procedureRepository.existsByConsultationAndProcedureTypeUid(
                consultation, procedureTypeUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate procedure type is not allowed for this encounter");
        }
    }

    private void guardNoDuplicateOnNonConsultation(NonConsultation nonConsultation,
                                                    String procedureTypeUid) {
        if (procedureRepository.existsByNonConsultationAndProcedureTypeUid(
                nonConsultation, procedureTypeUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate procedure type is not allowed for this encounter");
        }
    }

    /**
     * Derive the local settled flag from the billing ChargeResult and payment context.
     * Identical logic to LabTestService / RadiologyService (CR-INC05-01, ADR-0022 D4).
     */
    private static boolean isSettledFromCharge(ChargeResult chargeResult, PaymentMode paymentMode,
                                               boolean inpatient) {
        if (!SettlementPolicy.requiresPrepayment(paymentMode, inpatient, false)) {
            return true;
        }
        return chargeResult.status() != BillStatus.UNPAID;
    }

    private static PaymentMode toPaymentMode(String paymentTypeStr) {
        if (paymentTypeStr == null || paymentTypeStr.isBlank()) {
            return PaymentMode.CASH;
        }
        try {
            return PaymentMode.valueOf(paymentTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PaymentMode.CASH;
        }
    }
}
