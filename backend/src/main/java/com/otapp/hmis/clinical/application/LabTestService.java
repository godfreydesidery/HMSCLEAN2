package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.api.SettlementPolicy;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.application.dto.LabTestAttachmentDto;
import com.otapp.hmis.clinical.application.dto.LabTestAttachmentRequest;
import com.otapp.hmis.clinical.application.dto.LabTestDto;
import com.otapp.hmis.clinical.application.dto.LabTestOrderRequest;
import com.otapp.hmis.clinical.application.dto.LabTestRejectRequest;
import com.otapp.hmis.clinical.application.dto.LabTestReportRequest;
import com.otapp.hmis.clinical.application.dto.LabTestResultRequest;
import com.otapp.hmis.clinical.application.dto.LabTestVerifyRequest;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.LabTest;
import com.otapp.hmis.clinical.domain.LabTestAttachment;
import com.otapp.hmis.clinical.domain.LabTestAttachmentRepository;
import com.otapp.hmis.clinical.domain.LabTestRepository;
import com.otapp.hmis.clinical.domain.LabTestStatus;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.masterdata.lookup.LabTestTypeLookup;
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
 * Service implementing the LabTest aggregate lifecycle (inc-05 C7).
 *
 * <p><strong>State machine (CR-INC05-01 — no bill re-check at accept/verify):</strong>
 * <ul>
 *   <li>order (PENDING): guards → exactly-one-encounter, duplicate-guard, labTestType exists;
 *       creates billing charge via {@link BillingCommands#recordClinicalCharge}; sets local
 *       {@code settled} flag from the ChargeResult.</li>
 *   <li>accept (PENDING|REJECTED → ACCEPTED): NO bill check (CR-INC05-01 parity).</li>
 *   <li>reject (PENDING|ACCEPTED → REJECTED): clears accept_* fields.</li>
 *   <li>collect (ACCEPTED → COLLECTED).</li>
 *   <li>verify (COLLECTED → VERIFIED): writes result/level/testRange/unit.</li>
 *   <li>hold (ACCEPTED → PENDING): stamps held_* then reverts.</li>
 *   <li>saveResult (COLLECTED only): updates result text, no status change.</li>
 *   <li>addReport (COLLECTED only): updates report text, no status change.</li>
 *   <li>delete (PENDING only): hard-delete; credit-note DEFERRED.</li>
 * </ul>
 *
 * <p><strong>Settlement flag (local projection, CR-INC05-01):</strong>
 * The {@code settled} flag is set at order time from the {@link ChargeResult}:
 * <ul>
 *   <li>{@code true}  — ChargeResult.status IN (COVERED, VERIFIED, NONE)</li>
 *   <li>{@code false} — ChargeResult.status == UNPAID (CASH-OPD / CASH-OUTSIDER)</li>
 * </ul>
 * The clinical module NEVER reads billing bill status post-hoc (ADR-0008 §6, CR-INC05-01).
 * The cash-PAID→settled=true propagation is DEFERRED (same pattern as Consultation).
 *
 * <p><strong>DEFERRED — delete credit-note seam:</strong>
 * When a PENDING lab test is deleted, if the patient already PAID the bill (bill status == PAID),
 * a credit-note must be raised in the billing module. The billing module does not yet publish a
 * cancel/credit-note command via {@code billing.api}. Until that seam is published:
 * the lab test IS hard-deleted when PENDING (correct parity for the common case — unpaid);
 * the credit-note for already-PAID bills is NOT raised. A TODO marks this gap. The operational
 * risk is low: CASH labs are rarely paid before the specimen is even collected.
 *
 * <p><strong>DEFERRED — admission lab path:</strong>
 * The {@code admissionUid} path is not implemented. Deferred to the Inpatient/Nursing increment.
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §2).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Order create:              PatientServiceImpl.java:790-849</li>
 *   <li>Walk-in get-or-create:     PatientServiceImpl.java:790-806</li>
 *   <li>Duplicate guard:           PatientServiceImpl.java:790-806 (same-type-same-encounter)</li>
 *   <li>Accept/reject/collect:     PatientResource.java:3947-3980</li>
 *   <li>Verify (writes result):    PatientResource.java:3965-3980</li>
 *   <li>Hold (revert):             PatientResource.java:3960 (hold → PENDING)</li>
 *   <li>Delete (PENDING only):     PatientResource.java (deleteById on PENDING check)</li>
 *   <li>Max 5 attachments:         PatientServiceImpl.java:2828-2830</li>
 *   <li>COLLECTED attach gate:     PatientServiceImpl.java:2832-2834</li>
 *   <li>VERIFIED download gate:    PatientResource.java:6021</li>
 *   <li>Worklist:                  PatientResource.java:3668-3717</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class LabTestService implements LabTestPort {

    private final LabTestRepository           labTestRepository;
    private final LabTestAttachmentRepository attachmentRepository;
    private final ConsultationRepository      consultationRepository;
    private final NonConsultationRepository   nonConsultationRepository;
    private final LabTestTypeLookup           labTestTypeLookup;
    private final BillingCommands             billingCommands;
    private final AuditRecorder               auditRecorder;
    private final LabTestMapper               labTestMapper;

    private static final String AUDIT_ENTITY      = "clinical.LabTest";
    /** Legacy credit-note reference for a deleted lab test (PatientResource.java:2936). */
    private static final String REF_CANCEL_LAB_TEST = "Canceled lab test";
    private static final String AUDIT_ATTACHMENT  = "clinical.LabTestAttachment";

    // =========================================================================
    // Order creation — consultation path
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guards:
     * <ol>
     *   <li>Consultation must exist (404).</li>
     *   <li>LabTestType must exist in masterdata (404 "Lab test type not found").</li>
     *   <li>No duplicate type on this consultation (422 verbatim legacy message).</li>
     * </ol>
     *
     * <p>Billing: ChargeRequest built from consultation context (patientUid, planUid,
     * membershipNo, paymentMode). Settlement flag set from ChargeResult status.
     */
    @Override
    @Transactional
    public LabTestDto orderForConsultation(String consultationUid, LabTestOrderRequest request,
                                           TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        requireLabTestTypeExists(request.labTestTypeUid());
        guardNoDuplicateOnConsultation(consultation, request.labTestTypeUid());

        // Build and execute billing charge
        PaymentMode paymentMode = toPaymentMode(consultation.getPaymentMode().name());
        ChargeRequest chargeRequest = new ChargeRequest(
                consultation.getPatientUid(),
                consultation.getInsurancePlanUid(),
                consultation.getMembershipNo(),
                ServiceKind.LAB_TEST,
                request.labTestTypeUid(),
                BigDecimal.ONE,
                paymentMode,
                false, // outpatient
                false  // not a follow-up
        );
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);

        // Derive local settled flag from ChargeResult (CR-INC05-01, ADR-0022 D4)
        boolean settled = isSettledFromCharge(chargeResult, paymentMode, false);

        Instant now = Instant.now();
        LabTest lt = LabTest.forConsultation(
                consultation,
                request.labTestTypeUid(),
                chargeResult.billUid(),
                settled,
                paymentMode.name(),
                consultation.getMembershipNo(),
                consultation.getInsurancePlanUid(),
                request.diagnosisTypeUid(),
                request.clinicianUserUid(),
                ctx.actorUsername(),
                ctx.dayUid(),
                now);

        LabTest saved = labTestRepository.save(lt);
        auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        return labTestMapper.toDto(saved);
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
     *   <li>LabTestType must exist in masterdata (404 "Lab test type not found").</li>
     *   <li>No duplicate type on this non-consultation (422 verbatim legacy message).</li>
     * </ol>
     *
     * <p>Billing: ChargeRequest built from non-consultation context + request fields
     * (patientUid from request, paymentType from non-consultation or request override).
     */
    @Override
    @Transactional
    public LabTestDto orderForNonConsultation(String nonConsultationUid,
                                              LabTestOrderRequest request,
                                              TxAuditContext ctx) {
        NonConsultation nonConsultation = requireNonConsultation(nonConsultationUid);
        requireLabTestTypeExists(request.labTestTypeUid());
        guardNoDuplicateOnNonConsultation(nonConsultation, request.labTestTypeUid());

        // For non-consultation: patientUid comes from the nonConsultation entity (set at creation)
        String patientUid = nonConsultation.getPatientUid();

        // Payment context: use the non-consultation's payment type (set at walk-in creation)
        // Override with request fields if provided (for OUTSIDER with known payment mode)
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
                ServiceKind.LAB_TEST,
                request.labTestTypeUid(),
                BigDecimal.ONE,
                paymentMode,
                false, // outsider = not inpatient
                false
        );
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);

        boolean settled = isSettledFromCharge(chargeResult, paymentMode, false);

        Instant now = Instant.now();
        LabTest lt = LabTest.forNonConsultation(
                nonConsultation,
                patientUid,
                request.labTestTypeUid(),
                chargeResult.billUid(),
                settled,
                paymentMode.name(),
                membershipNo,
                insurancePlanUid,
                request.diagnosisTypeUid(),
                request.clinicianUserUid(),
                ctx.actorUsername(),
                ctx.dayUid(),
                now);

        LabTest saved = labTestRepository.save(lt);
        auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        return labTestMapper.toDto(saved);
    }

    // =========================================================================
    // Lifecycle transitions
    // =========================================================================

    /**
     * Accept: PENDING | REJECTED → ACCEPTED.
     *
     * <p>NO bill re-check (CR-INC05-01 parity). Guards: status must be PENDING or REJECTED.
     * Verbatim message on wrong status: "Lab test cannot be accepted at this stage"
     * (PatientResource.java:3947-3953 parity — accept guard).
     */
    @Override
    @Transactional
    public LabTestDto accept(String labTestUid, TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);

        if (lt.getStatus() != LabTestStatus.PENDING && lt.getStatus() != LabTestStatus.REJECTED) {
            throw new InvalidPatientOperationException(
                    "Lab test cannot be accepted at this stage");
        }

        lt.accept(ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, lt.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return labTestMapper.toDto(lt);
    }

    /**
     * Reject: PENDING | ACCEPTED → REJECTED.
     *
     * <p>Guards: status must be PENDING or ACCEPTED.
     * Verbatim message on wrong status: "Lab test cannot be rejected at this stage"
     * (PatientResource.java:3953-3960 parity — reject guard).
     */
    @Override
    @Transactional
    public LabTestDto reject(String labTestUid, LabTestRejectRequest request, TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);

        if (lt.getStatus() != LabTestStatus.PENDING && lt.getStatus() != LabTestStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Lab test cannot be rejected at this stage");
        }

        lt.reject(request.rejectComment(), ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, lt.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return labTestMapper.toDto(lt);
    }

    /**
     * Collect: ACCEPTED → COLLECTED.
     *
     * <p>Guard: status must be ACCEPTED.
     * Verbatim message: "Please accept the lab test first"
     * (PatientResource.java:3960-3965 parity — M16 accept-before-complete intent).
     */
    @Override
    @Transactional
    public LabTestDto collect(String labTestUid, TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);

        if (lt.getStatus() != LabTestStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Please accept the lab test first");
        }

        lt.collect(ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, lt.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return labTestMapper.toDto(lt);
    }

    /**
     * Verify: COLLECTED → VERIFIED. Writes result/level/testRange/unit.
     *
     * <p>Guard: status must be COLLECTED.
     * Verbatim message: "Please collect the lab test first"
     * (PatientResource.java:3965-3980 parity — M16 collect-before-verify intent).
     */
    @Override
    @Transactional
    public LabTestDto verify(String labTestUid, LabTestVerifyRequest request, TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);

        if (lt.getStatus() != LabTestStatus.COLLECTED) {
            throw new InvalidPatientOperationException(
                    "Please collect the lab test first");
        }

        lt.verify(request.result(), request.level(), request.testRange(), request.unit(),
                ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, lt.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return labTestMapper.toDto(lt);
    }

    /**
     * Hold (revert): ACCEPTED → PENDING.
     *
     * <p>Guard: status must be ACCEPTED.
     * Message: "Lab test must be accepted to hold"
     * (PatientResource.java parity — hold is only meaningful from ACCEPTED).
     */
    @Override
    @Transactional
    public LabTestDto hold(String labTestUid, TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);

        if (lt.getStatus() != LabTestStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Lab test must be accepted to hold");
        }

        lt.hold(ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, lt.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return labTestMapper.toDto(lt);
    }

    // =========================================================================
    // Result / report edits (no status change)
    // =========================================================================

    /**
     * Save result text without status change. Allowed only when status == COLLECTED.
     * Message: "Please collect the lab test first" (status guard parity).
     */
    @Override
    @Transactional
    public LabTestDto saveResult(String labTestUid, LabTestResultRequest request,
                                 TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);

        if (lt.getStatus() != LabTestStatus.COLLECTED) {
            throw new InvalidPatientOperationException(
                    "Please collect the lab test first");
        }

        lt.saveResult(request.result());
        auditRecorder.record(AUDIT_ENTITY, lt.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return labTestMapper.toDto(lt);
    }

    /**
     * Add/update report text without status change. Allowed only when status == COLLECTED.
     * Message: "Please collect the lab test first" (status guard parity).
     */
    @Override
    @Transactional
    public LabTestDto addReport(String labTestUid, LabTestReportRequest request,
                                TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);

        if (lt.getStatus() != LabTestStatus.COLLECTED) {
            throw new InvalidPatientOperationException(
                    "Please collect the lab test first");
        }

        lt.addReport(request.report());
        auditRecorder.record(AUDIT_ENTITY, lt.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return labTestMapper.toDto(lt);
    }

    // =========================================================================
    // Delete
    // =========================================================================

    /**
     * Hard-delete a PENDING lab test order, reversing its bill (inc-06A C1, ITEM1).
     *
     * <p>Guard: status must be PENDING (else 422, legacy verbatim
     * "Could not delete, only a PENDING lab test can be deleted",
     * PatientResource.java:2917-2919).
     *
     * <p><strong>Bill reversal (credit-note seam — now wired):</strong>
     * Before the order row is removed, {@link BillingCommands#cancelCharge} is invoked in the
     * SAME transaction (propagation REQUIRED) with the legacy reference label
     * {@value #REF_CANCEL_LAB_TEST}. The published {@code billing.api} command reproduces the
     * legacy reversal: soft-cancel the bill (→ CANCELED), and ONLY when a RECEIVED payment
     * existed, refund it (RECEIVED → REFUNDED) and raise a PENDING {@code PatientCreditNote}
     * for the full bill amount; the invoice detail is detached and the parent invoice deleted
     * only when empty (the CR-10 fix — the legacy {@code j=j++} always-delete bug is NOT
     * reproduced). The legacy hard-delete of the bill/payment rows is NOT reproduced (the
     * ratified soft-flag standard supersedes it). The clinical ORDER row is still hard-deleted
     * (matches legacy for the order entity). Legacy: PatientResource.java:2912-2965.
     *
     * @param labTestUid the ULID of the lab test to delete
     * @param ctx        transaction audit context
     */
    @Override
    @Transactional
    public void delete(String labTestUid, TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);

        if (lt.getStatus() != LabTestStatus.PENDING) {
            throw new InvalidPatientOperationException(
                    "Could not delete, only a PENDING lab test can be deleted");
        }

        // Reverse the bill (soft-cancel + RECEIVED→credit-note) BEFORE deleting the order row,
        // inside this transaction (ITEM1; legacy PatientResource.java:2922-2961).
        billingCommands.cancelCharge(lt.getPatientBillUid(), REF_CANCEL_LAB_TEST, ctx);

        labTestRepository.delete(lt);
        auditRecorder.record(AUDIT_ENTITY, labTestUid, AuditAction.DELETE, ctx.actorUsername());
    }

    // =========================================================================
    // Attachments
    // =========================================================================

    /**
     * Add an attachment to a lab test.
     *
     * <p>Guards (PatientServiceImpl.java:2828-2834):
     * <ol>
     *   <li>Lab test must exist (404).</li>
     *   <li>Status must be COLLECTED (422 "Lab test must be collected before adding attachments").</li>
     *   <li>Attachment count must be less than 5 (422 "Maximum of 5 attachments allowed per lab test").</li>
     * </ol>
     */
    @Override
    @Transactional
    public LabTestAttachmentDto addAttachment(String labTestUid, LabTestAttachmentRequest request,
                                              TxAuditContext ctx) {
        LabTest lt = requireLabTest(labTestUid);
        long count = attachmentRepository.countByLabTest(lt);

        if (!lt.canAttach(count)) {
            // Legacy order (PatientServiceImpl.java:2829,2833): status check first, then cap.
            if (lt.getStatus() != LabTestStatus.COLLECTED) {
                throw new InvalidPatientOperationException(
                        "Can only attach for collected tests");
            }
            throw new InvalidPatientOperationException(
                    "Can not add more than 5 attachments");
        }

        LabTestAttachment attachment = LabTestAttachment.create(lt, request.name(),
                request.fileName());
        LabTestAttachment saved = attachmentRepository.save(attachment);
        auditRecorder.record(AUDIT_ATTACHMENT, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        return labTestMapper.toAttachmentDto(saved);
    }

    /**
     * List all attachments for a lab test.
     *
     * <p>Note: the download-gated-on-VERIFIED rule (PatientResource.java:6021) is enforced
     * at the controller layer — the service returns all attachments regardless of status,
     * but the controller documentation notes the download gate.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LabTestAttachmentDto> listAttachments(String labTestUid) {
        LabTest lt = requireLabTest(labTestUid);
        return labTestMapper.toAttachmentDtoList(
                attachmentRepository.findByLabTestOrderByCreatedAtAsc(lt));
    }

    /**
     * Delete an attachment.
     *
     * <p>Guard: blocked when parent lab test status == VERIFIED (order is finalized).
     * Message: "Cannot delete attachment from a verified lab test"
     * (PatientResource.java:6021 parity — VERIFIED gate).
     */
    @Override
    @Transactional
    public void deleteAttachment(String attachmentUid, TxAuditContext ctx) {
        LabTestAttachment attachment = attachmentRepository.findByUid(attachmentUid)
                .orElseThrow(() -> new NotFoundException(
                        "Lab test attachment not found: " + attachmentUid));

        LabTest lt = attachment.getLabTest();
        if (!lt.canDeleteAttachment()) {
            throw new InvalidPatientOperationException(
                    "Cannot delete attachment from a verified lab test");
        }

        attachmentRepository.delete(attachment);
        auditRecorder.record(AUDIT_ATTACHMENT, attachmentUid, AuditAction.DELETE,
                ctx.actorUsername());
    }

    // =========================================================================
    // Queries
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public LabTestDto getByUid(String labTestUid) {
        return labTestMapper.toDto(requireLabTest(labTestUid));
    }

    /**
     * Lab department worklist — settled orders in actionable statuses {PENDING, ACCEPTED, COLLECTED}.
     *
     * <p>The settled flag replaces reading billing bill status (CR-INC05-01, ADR-0022 D4).
     * Legacy: PatientResource.java:3668-3717 filtered by bill status PAID|COVERED.
     *
     * @param statusFilter optional single-status filter (null = all actionable statuses)
     */
    @Override
    @Transactional(readOnly = true)
    public List<LabTestDto> worklist(LabTestStatus statusFilter) {
        List<LabTest> labTests;
        if (statusFilter != null) {
            labTests = labTestRepository.findBySettledAndStatusOrderByCreatedAtAsc(
                    true, statusFilter);
        } else {
            labTests = labTestRepository.findBySettledAndStatusInOrderByCreatedAtAsc(
                    true, List.of(LabTestStatus.PENDING, LabTestStatus.ACCEPTED,
                            LabTestStatus.COLLECTED));
        }
        return labTestMapper.toDtoList(labTests);
    }

    /**
     * All lab tests for a consultation, ordered by creation time ascending.
     *
     * <p>Returns an empty list if the consultation has no lab test orders.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LabTestDto> listForConsultation(String consultationUid) {
        Consultation consultation = requireConsultation(consultationUid);
        return labTestMapper.toDtoList(
                labTestRepository.findByConsultationOrderByCreatedAtAsc(consultation));
    }

    /**
     * All lab tests for a patient, optionally filtered by status.
     *
     * @param patientUid   the patient ULID
     * @param statusFilter optional status filter (null = all statuses)
     */
    @Override
    @Transactional(readOnly = true)
    public List<LabTestDto> byPatient(String patientUid, LabTestStatus statusFilter) {
        List<LabTest> labTests;
        if (statusFilter != null) {
            labTests = labTestRepository.findByPatientUidAndStatusOrderByCreatedAtDesc(
                    patientUid, statusFilter);
        } else {
            labTests = labTestRepository.findByPatientUidOrderByCreatedAtDesc(patientUid);
        }
        return labTestMapper.toDtoList(labTests);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private LabTest requireLabTest(String uid) {
        return labTestRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Lab test not found: " + uid));
    }

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + uid));
    }

    private NonConsultation requireNonConsultation(String uid) {
        return nonConsultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("NonConsultation not found: " + uid));
    }

    /**
     * Verify the labTestTypeUid resolves in the masterdata catalog.
     * Verbatim-adjacent legacy error: "Lab test type not found"
     * (mirroring PatientResource.java:1659 "Diagnosis type not found" pattern).
     */
    private void requireLabTestTypeExists(String labTestTypeUid) {
        if (!labTestTypeLookup.existsByUid(labTestTypeUid)) {
            throw new NotFoundException("Lab test type not found");
        }
    }

    /**
     * Duplicate guard — consultation path.
     * Verbatim legacy message: "Duplicate lab test type is not allowed for this encounter"
     * (PatientServiceImpl.java:790-806 parity — same-type-same-encounter reject).
     */
    private void guardNoDuplicateOnConsultation(Consultation consultation,
                                                 String labTestTypeUid) {
        if (labTestRepository.existsByConsultationAndLabTestTypeUid(
                consultation, labTestTypeUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate lab test type is not allowed for this encounter");
        }
    }

    /**
     * Duplicate guard — non-consultation path.
     * Same verbatim message.
     */
    private void guardNoDuplicateOnNonConsultation(NonConsultation nonConsultation,
                                                    String labTestTypeUid) {
        if (labTestRepository.existsByNonConsultationAndLabTestTypeUid(
                nonConsultation, labTestTypeUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate lab test type is not allowed for this encounter");
        }
    }

    /**
     * Derive the local settled flag from the billing ChargeResult and payment context.
     *
     * <p>Rule (CR-INC05-01, ADR-0022 D4):
     * <ul>
     *   <li>COVERED → settled=true (insurance covered — no cash prepayment needed)</li>
     *   <li>VERIFIED → settled=true (inpatient cash — settles at discharge)</li>
     *   <li>NONE → settled=true (follow-up — no charge, auto-pass)</li>
     *   <li>UNPAID → settled=false (CASH-OPD / CASH-OUTSIDER — prepayment required)</li>
     *   <li>PAID → settled=true (bill was already paid — auto-pass)</li>
     * </ul>
     *
     * <p>Alternatively: settled = !SettlementPolicy.requiresPrepayment(paymentMode, inpatient, false)
     * for INSURANCE/inpatient, and chargeResult.status != UNPAID for CASH.
     *
     * @param chargeResult the billing engine result
     * @param paymentMode  the effective payment mode
     * @param inpatient    whether the patient is currently admitted
     * @return true if the order is considered settled at time of creation
     */
    private static boolean isSettledFromCharge(ChargeResult chargeResult, PaymentMode paymentMode,
                                               boolean inpatient) {
        // Use SettlementPolicy to determine if prepayment is required at all
        if (!SettlementPolicy.requiresPrepayment(paymentMode, inpatient, false)) {
            // INSURANCE or inpatient — no prepayment needed → settled=true
            return true;
        }
        // CASH-OPD: the bill is UNPAID → settled=false
        // The cash-PAID→settled=true propagation is deferred (event seam)
        return chargeResult.status() != BillStatus.UNPAID;
    }

    /**
     * Convert a payment type string to {@link PaymentMode}.
     *
     * <p>NonConsultation stores paymentType as a String (CASH / INSURANCE / '').
     * Default empty string → CASH (most conservative: requires prepayment).
     */
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
