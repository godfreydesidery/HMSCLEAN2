package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.BillingQueries;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.api.SettlementPolicy;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.application.dto.RadiologyAttachmentDto;
import com.otapp.hmis.clinical.application.dto.RadiologyAttachmentRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyDto;
import com.otapp.hmis.clinical.application.dto.RadiologyOrderRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyRejectRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyReportRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyResultRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyVerifyRequest;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.Radiology;
import com.otapp.hmis.clinical.domain.RadiologyAttachment;
import com.otapp.hmis.clinical.domain.RadiologyAttachmentRepository;
import com.otapp.hmis.clinical.domain.RadiologyRepository;
import com.otapp.hmis.clinical.domain.RadiologyStatus;
import com.otapp.hmis.masterdata.lookup.RadiologyTypeLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import com.otapp.hmis.shared.storage.AttachmentStorageProperties;
import com.otapp.hmis.shared.storage.FileStoragePort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementing the Radiology aggregate lifecycle (inc-05 C8).
 *
 * <p><strong>State machine (radiology-specific deltas from C7 LabTest):</strong>
 * <ul>
 *   <li>order (PENDING): guards → exactly-one-encounter, duplicate-guard, radiologyType exists;
 *       creates billing charge via {@link BillingCommands#recordClinicalCharge} with kind=RADIOLOGY;
 *       sets local {@code settled} flag from the ChargeResult.</li>
 *   <li>accept (PENDING|REJECTED → ACCEPTED): NO bill check (CR-INC05-01 parity).
 *       <strong>Does NOT clear rejectComment</strong> — radiology asymmetry vs LabTest.</li>
 *   <li>reject (PENDING|ACCEPTED → REJECTED): clears accept_* fields.</li>
 *   <li><strong>NO collect step</strong> (CR-INC05-14 — dead endpoint). COLLECTED is a
 *       dead state retained for data fidelity only.</li>
 *   <li>verify (<strong>ACCEPTED → VERIFIED</strong>): writes result/report/inline-blob.
 *       Guard: status must be ACCEPTED (not COLLECTED — PatientResource.java:4280-4281).
 *       Verbatim message: "Please accept the radiology order first".</li>
 *   <li>hold (ACCEPTED → PENDING): stamps held_* then reverts.</li>
 *   <li>saveResult (<strong>ACCEPTED only</strong>): updates result text, no status change.
 *       (PatientResource.java:4305-4306 — radiology edits when ACCEPTED, not COLLECTED).</li>
 *   <li>delete (PENDING only): hard-delete; credit-note DEFERRED.</li>
 *   <li>addAttachment (<strong>ACCEPTED gate</strong>): max 5 named file attachments;
 *       attach only when ACCEPTED (PatientServiceImpl.java:2931-2933).</li>
 * </ul>
 *
 * <p><strong>Reject asymmetry (verified finding):</strong>
 * {@code accept()} does NOT clear {@code rejectComment}. This is a deliberate legacy
 * asymmetry vs LabTest. Reproduced verbatim per exact-process mandate.
 *
 * <p><strong>Settlement flag (local projection, CR-INC05-01):</strong>
 * The {@code settled} flag is set at order time from the {@link ChargeResult}:
 * <ul>
 *   <li>{@code true}  — ChargeResult.status IN (COVERED, VERIFIED, NONE)</li>
 *   <li>{@code false} — ChargeResult.status == UNPAID (CASH-OPD / CASH-OUTSIDER)</li>
 * </ul>
 * The cash-PAID→settled=true propagation is DEFERRED (same pattern as LabTest).
 *
 * <p><strong>DEFERRED — delete credit-note seam:</strong>
 * Same as LabTestService — no credit-note raised on delete for already-PAID bills.
 *
 * <p><strong>DEFERRED — admission radiology path:</strong>
 * The {@code admissionUid} path is not implemented. Deferred to the Inpatient/Nursing increment.
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §2).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Order create:              PatientServiceImpl.java</li>
 *   <li>Accept/reject/verify:      PatientResource.java:4280-4292</li>
 *   <li>verify from ACCEPTED:      PatientResource.java:4280-4281</li>
 *   <li>saveResult ACCEPTED gate:  PatientResource.java:4305-4306</li>
 *   <li>Hold (revert):             hold_radiology pattern</li>
 *   <li>Max 5 attachments:         PatientServiceImpl.java:2928-2930</li>
 *   <li>ACCEPTED attach gate:      PatientServiceImpl.java:2931-2933</li>
 *   <li>VERIFIED download gate:    PatientResource.java:6154</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class RadiologyService implements RadiologyPort {

    private final RadiologyRepository           radiologyRepository;
    private final RadiologyAttachmentRepository attachmentRepository;
    private final ConsultationRepository        consultationRepository;
    private final NonConsultationRepository     nonConsultationRepository;
    private final RadiologyTypeLookup           radiologyTypeLookup;
    private final BillingCommands               billingCommands;
    private final BillingQueries                billingQueries;
    private final AuditRecorder                 auditRecorder;
    private final RadiologyMapper               radiologyMapper;
    /** Local-disk file storage (inc-06A C7 / ITEM5). */
    private final FileStoragePort               fileStoragePort;
    /** Max file size + base path config (inc-06A C7 / ITEM5). */
    private final AttachmentStorageProperties   storageProperties;

    private static final String AUDIT_ENTITY     = "clinical.Radiology";
    /** Legacy credit-note reference for a deleted radiology (PatientResource.java:3436). */
    private static final String REF_CANCEL_RADIOLOGY = "Canceled radiology";
    private static final String AUDIT_ATTACHMENT = "clinical.RadiologyAttachment";
    /** Storage prefix for radiology attachments (inc-06A C7; approved deviation from legacy scheme). */
    private static final String STORAGE_PREFIX   = "RAD";

    // =========================================================================
    // Order creation — consultation path
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guards:
     * <ol>
     *   <li>Consultation must exist (404).</li>
     *   <li>RadiologyType must exist in masterdata (404 "Radiology type not found").</li>
     *   <li>No duplicate type on this consultation (422 verbatim legacy message).</li>
     * </ol>
     */
    @Override
    @Transactional
    public RadiologyDto orderForConsultation(String consultationUid,
                                              RadiologyOrderRequest request,
                                              TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        requireRadiologyTypeExists(request.radiologyTypeUid());
        guardNoDuplicateOnConsultation(consultation, request.radiologyTypeUid());

        PaymentMode paymentMode = toPaymentMode(consultation.getPaymentMode().name());
        ChargeRequest chargeRequest = new ChargeRequest(
                consultation.getPatientUid(),
                consultation.getInsurancePlanUid(),
                consultation.getMembershipNo(),
                ServiceKind.RADIOLOGY,
                request.radiologyTypeUid(),
                BigDecimal.ONE,
                paymentMode,
                false,
                false,
                null,  // billItem override — none (CR-07-Q13; inpatient consumable path only)
                null,  // description override — none
                null   // admissionUid — null for outpatient radiology charges (inc-07 07a)
        );
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);

        boolean settled = isSettledFromCharge(chargeResult, paymentMode, false);

        Instant now = Instant.now();
        Radiology r = Radiology.forConsultation(
                consultation,
                request.radiologyTypeUid(),
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

        Radiology saved = radiologyRepository.save(r);
        auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return radiologyMapper.toDto(saved);
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
     *   <li>RadiologyType must exist in masterdata (404 "Radiology type not found").</li>
     *   <li>No duplicate type on this non-consultation (422).</li>
     * </ol>
     */
    @Override
    @Transactional
    public RadiologyDto orderForNonConsultation(String nonConsultationUid,
                                                 RadiologyOrderRequest request,
                                                 TxAuditContext ctx) {
        NonConsultation nonConsultation = requireNonConsultation(nonConsultationUid);
        requireRadiologyTypeExists(request.radiologyTypeUid());
        guardNoDuplicateOnNonConsultation(nonConsultation, request.radiologyTypeUid());

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
                ServiceKind.RADIOLOGY,
                request.radiologyTypeUid(),
                BigDecimal.ONE,
                paymentMode,
                false,
                false,
                null,  // billItem override — none (CR-07-Q13; inpatient consumable path only)
                null,  // description override — none
                null   // admissionUid — null for outpatient radiology charges (inc-07 07a)
        );
        ChargeResult chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);

        boolean settled = isSettledFromCharge(chargeResult, paymentMode, false);

        Instant now = Instant.now();
        Radiology r = Radiology.forNonConsultation(
                nonConsultation,
                patientUid,
                request.radiologyTypeUid(),
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

        Radiology saved = radiologyRepository.save(r);
        auditRecorder.record(AUDIT_ENTITY, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return radiologyMapper.toDto(saved);
    }

    // =========================================================================
    // Lifecycle transitions
    // =========================================================================

    /**
     * Accept: PENDING | REJECTED → ACCEPTED.
     *
     * <p>NO bill re-check (CR-INC05-01 parity).
     * <p><strong>Does NOT clear rejectComment</strong> — radiology asymmetry vs LabTest.
     * Guard: status must be PENDING or REJECTED (else 422).
     * Message: "Radiology order cannot be accepted at this stage".
     */
    @Override
    @Transactional
    public RadiologyDto accept(String radiologyUid, TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        if (r.getStatus() != RadiologyStatus.PENDING && r.getStatus() != RadiologyStatus.REJECTED) {
            throw new InvalidPatientOperationException(
                    "Radiology order cannot be accepted at this stage");
        }

        r.accept(ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, r.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return radiologyMapper.toDto(r);
    }

    /**
     * Reject: PENDING | ACCEPTED → REJECTED.
     *
     * <p>Guard: status must be PENDING or ACCEPTED (else 422).
     * Message: "Radiology order cannot be rejected at this stage".
     */
    @Override
    @Transactional
    public RadiologyDto reject(String radiologyUid, RadiologyRejectRequest request,
                               TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        if (r.getStatus() != RadiologyStatus.PENDING && r.getStatus() != RadiologyStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Radiology order cannot be rejected at this stage");
        }

        r.reject(request.rejectComment(), ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, r.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return radiologyMapper.toDto(r);
    }

    /**
     * Edit the rejection comment on an already-REJECTED radiology order (inc-06A C3 / ITEM3).
     *
     * <p>Reproduces legacy {@code save_reason_for_rejection} (PatientResource.java:2018-2032):
     * re-callable post-rejection edit that sets ONLY {@code rejectComment}, no status change.
     *
     * <p>Guard: status must be REJECTED, else 422 verbatim
     * "Could not save. Only allowed for rejected tests". No null/blank validation.
     */
    @Override
    @Transactional
    public RadiologyDto saveRejectComment(String radiologyUid, RadiologyRejectRequest request,
                                          TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        if (r.getStatus() != RadiologyStatus.REJECTED) {
            throw new InvalidPatientOperationException(
                    "Could not save. Only allowed for rejected tests");
        }

        r.updateRejectComment(request.rejectComment());
        auditRecorder.record(AUDIT_ENTITY, r.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return radiologyMapper.toDto(r);
    }

    /**
     * Verify: ACCEPTED → VERIFIED. Writes result/report/inline-attachment-blob.
     *
     * <p><strong>Active path: ACCEPTED → VERIFIED DIRECTLY</strong> (PatientResource.java:4280-4281).
     * NO collect step (CR-INC05-14). Guard: status must be ACCEPTED.
     * Message: "Please accept the radiology order first" (status guard parity).
     */
    @Override
    @Transactional
    public RadiologyDto verify(String radiologyUid, RadiologyVerifyRequest request,
                               TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        if (r.getStatus() != RadiologyStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Please accept the radiology order first");
        }

        r.verify(request.result(), request.report(), request.attachment(),
                ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, r.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return radiologyMapper.toDto(r);
    }

    /**
     * Hold (revert): ACCEPTED → PENDING.
     *
     * <p>Guard: status must be ACCEPTED.
     * Message: "Radiology order must be accepted to hold".
     */
    @Override
    @Transactional
    public RadiologyDto hold(String radiologyUid, TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        if (r.getStatus() != RadiologyStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Radiology order must be accepted to hold");
        }

        r.hold(ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, r.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return radiologyMapper.toDto(r);
    }

    // =========================================================================
    // Result edit (no status change) — ACCEPTED gate
    // =========================================================================

    /**
     * Save result text without status change. Allowed only when status == ACCEPTED.
     *
     * <p>Radiology edits when ACCEPTED (PatientResource.java:4305-4306 parity).
     * This is DIFFERENT from LabTest which requires COLLECTED.
     * Message: "Please accept the radiology order first".
     */
    @Override
    @Transactional
    public RadiologyDto saveResult(String radiologyUid, RadiologyResultRequest request,
                                   TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        if (r.getStatus() != RadiologyStatus.ACCEPTED) {
            throw new InvalidPatientOperationException(
                    "Please accept the radiology order first");
        }

        r.saveResult(request.result());
        auditRecorder.record(AUDIT_ENTITY, r.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return radiologyMapper.toDto(r);
    }

    /**
     * Stand-alone add/update of the radiologist report (inc-06A C5 / ITEM2).
     *
     * <p>Reproduces legacy {@code radiologies/add_report} (PatientResource.java:3183-3197): writes
     * ONLY the {@code report} field, gated on the BILL status ({@code PAID|COVERED|VERIFIED}) and
     * INDEPENDENT of order status — verbatim 422 "Could not add report. Payment not verified"
     * otherwise. As-built radiology previously wrote the report only inline at verify; this adds
     * the dedicated bill-gated endpoint to match legacy.
     *
     * <p><strong>Post-VERIFIED amendment:</strong> per the ratified ITEM4 policy (audited-amend,
     * C6), a post-VERIFIED change must go through the dedicated {@code amendReport} path (added in
     * C6). This endpoint blocks a VERIFIED-order overwrite so the verified report is not silently
     * mutated; until C6 lands a VERIFIED order's report is immutable via this path.
     */
    @Override
    @Transactional
    public RadiologyDto addReport(String radiologyUid, RadiologyReportRequest request,
                                  TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        // Bill-gate (legacy parity): report write requires a settled bill, read live (C4 seam).
        requireBillPaidCoveredOrVerified(r.getPatientBillUid());

        // Post-VERIFIED writes are routed to the audited amend path (C6 / ITEM4 ratified policy).
        if (r.getStatus() == RadiologyStatus.VERIFIED) {
            throw new InvalidPatientOperationException(
                    "Could not add report. A verified report can only be amended via the amend path");
        }

        r.addReport(request.report());
        auditRecorder.record(AUDIT_ENTITY, r.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return radiologyMapper.toDto(r);
    }

    /**
     * Bill-gate for report writes (inc-06A C5, ADR-0008 §6 scoped relaxation): the bill must be
     * {@code PAID}, {@code COVERED}, or {@code VERIFIED} (legacy add_report gate). Reads the LIVE
     * bill status via {@link BillingQueries} — the order-time local {@code settled} flag is
     * insufficient because a bill paid after order creation still shows {@code settled=false}.
     *
     * @throws InvalidPatientOperationException (422) verbatim "Could not add report. Payment not verified"
     */
    private void requireBillPaidCoveredOrVerified(String billUid) {
        BillStatus status = billingQueries.getBillStatus(billUid);
        if (status != BillStatus.PAID && status != BillStatus.COVERED
                && status != BillStatus.VERIFIED) {
            throw new InvalidPatientOperationException(
                    "Could not add report. Payment not verified");
        }
    }

    /**
     * Amend the report of a VERIFIED radiology order via the audited-amend path (inc-06A C6 / ITEM4).
     *
     * <p>Ratified policy (vs the legacy silent post-VERIFIED overwrite): retains the prior narrative
     * and stamps the amend audit triplet. Same bill-gate as {@link #addReport}. Guard: status must
     * be VERIFIED, else 422 "Could not amend report. Radiology is not verified".
     */
    @Override
    @Transactional
    public RadiologyDto amendReport(String radiologyUid, RadiologyReportRequest request,
                                    TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        requireBillPaidCoveredOrVerified(r.getPatientBillUid());

        if (r.getStatus() != RadiologyStatus.VERIFIED) {
            throw new InvalidPatientOperationException(
                    "Could not amend report. Radiology is not verified");
        }

        r.amendReport(request.report(), ctx.actorUsername(), ctx.dayUid(), Instant.now());
        auditRecorder.record(AUDIT_ENTITY, r.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return radiologyMapper.toDto(r);
    }

    // =========================================================================
    // Delete
    // =========================================================================

    /**
     * Hard-delete a PENDING radiology order.
     *
     * <p>Guard: status must be PENDING (else 422 "Only a pending radiology order can be deleted").
     *
     * <p><strong>Bill reversal (credit-note seam — now wired, inc-06A C1, ITEM1):</strong>
     * Before the order row is removed, {@link BillingCommands#cancelCharge} is invoked in the
     * SAME transaction with the legacy reference label {@value #REF_CANCEL_RADIOLOGY}: soft-cancel
     * the bill (→ CANCELED), and ONLY when a RECEIVED payment existed, refund it and raise a
     * PENDING credit-note for the full bill amount (CR-10 fix applied — parent invoice deleted
     * only when empty; the legacy hard-delete of bill/payment is NOT reproduced). The clinical
     * ORDER row is still hard-deleted. Legacy: PatientResource.java:3418-3471.
     */
    @Override
    @Transactional
    public void delete(String radiologyUid, TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);

        if (r.getStatus() != RadiologyStatus.PENDING) {
            throw new InvalidPatientOperationException(
                    "Could not delete, only a PENDING radiology can be deleted");
        }

        // Reverse the bill BEFORE deleting the order row (ITEM1; legacy 3431-3467).
        billingCommands.cancelCharge(r.getPatientBillUid(), REF_CANCEL_RADIOLOGY, ctx);

        radiologyRepository.delete(r);
        auditRecorder.record(AUDIT_ENTITY, radiologyUid, AuditAction.DELETE, ctx.actorUsername());
    }

    // =========================================================================
    // Attachments
    // =========================================================================

    /**
     * Add a named file attachment (JSON path — original endpoint; keeps existing tests green).
     *
     * <p>Guards (PatientServiceImpl.java:2928-2933):
     * <ol>
     *   <li>Radiology order must exist (404).</li>
     *   <li>Status must be ACCEPTED (422 "Can only attach for accepted tests").</li>
     *   <li>Attachment count must be less than 5 (422 "Can not add more than 5 attachments").</li>
     * </ol>
     */
    @Override
    @Transactional
    public RadiologyAttachmentDto addAttachment(String radiologyUid,
                                                RadiologyAttachmentRequest request,
                                                TxAuditContext ctx) {
        Radiology r = requireRadiology(radiologyUid);
        long count = attachmentRepository.countByRadiology(r);

        if (!r.canAttach(count)) {
            if (r.getStatus() != RadiologyStatus.ACCEPTED) {
                throw new InvalidPatientOperationException(
                        "Can only attach for accepted tests");
            }
            throw new InvalidPatientOperationException(
                    "Can not add more than 5 attachments");
        }

        RadiologyAttachment attachment = RadiologyAttachment.create(r, request.name(),
                request.fileName());
        RadiologyAttachment saved = attachmentRepository.save(attachment);
        auditRecorder.record(AUDIT_ATTACHMENT, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return radiologyMapper.toAttachmentDto(saved);
    }

    /**
     * Upload a file attachment via multipart (inc-06A C7 / ITEM5).
     *
     * <p><strong>Guard order (legacy-parity):</strong>
     * <ol>
     *   <li>Size cap: {@code bytes.length > maxFileSizeBytes} → 422 verbatim
     *       "File exceeds maximum file size allowed"
     *       (PatientServiceImpl.java:2940-2942).</li>
     *   <li>Status/count gate via {@code canAttach} — same messages as {@link #addAttachment}.
     *       ACCEPTED gate for radiology (PatientServiceImpl.java:2931-2933).</li>
     * </ol>
     *
     * <p><strong>On success:</strong> stores bytes via {@link FileStoragePort} (prefix "RAD"),
     * persists the row with the generated opaque storage filename, audits CREATE, returns DTO.
     *
     * <p>Legacy citations: PatientServiceImpl.java:2922-2996 (upload), 2940-2942 (cap),
     * 2928-2933 (status/count gate).
     */
    @Override
    @Transactional
    public RadiologyAttachmentDto uploadAttachment(String radiologyUid, byte[] bytes,
                                                    String originalFilename, String name,
                                                    TxAuditContext ctx) {
        // Guard order = legacy sequence (PatientServiceImpl.java:2926-2942; review F2):
        // (1) existence (404) → (2) count==5 → (3) status (ACCEPTED) → (4) size cap. Count
        // precedes status so the legacy message wins on overlapping-error inputs.
        Radiology r = requireRadiology(radiologyUid);                         // (1) 404
        long count = attachmentRepository.countByRadiology(r);

        if (count >= Radiology.MAX_ATTACHMENTS) {                            // (2) count
            throw new InvalidPatientOperationException(
                    "Can not add more than 5 attachments");
        }
        if (r.getStatus() != RadiologyStatus.ACCEPTED) {                    // (3) status
            throw new InvalidPatientOperationException(
                    "Can only attach for accepted tests");
        }
        if (bytes.length > storageProperties.maxFileSizeBytes()) {          // (4) size cap
            throw new InvalidPatientOperationException(
                    "File exceeds maximum file size allowed");
        }

        // Store bytes — generates opaque storage filename.
        String storageName = fileStoragePort.store(bytes, originalFilename,
                STORAGE_PREFIX, r.getUid());

        RadiologyAttachment attachment = RadiologyAttachment.create(r, name, storageName);
        RadiologyAttachment saved = attachmentRepository.save(attachment);
        auditRecorder.record(AUDIT_ATTACHMENT, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return radiologyMapper.toAttachmentDto(saved);
    }

    /**
     * Download the bytes of a radiology attachment (inc-06A C7 / ITEM5).
     *
     * <p>Guard: parent radiology order must be VERIFIED (PatientResource.java:6154) —
     * else 422 "Could not download. Radiology is not verified".
     *
     * <p>Reads bytes via {@link FileStoragePort#read}; a missing file throws
     * {@link com.otapp.hmis.shared.error.NotFoundException} (→ 404) from the storage layer.
     */
    @Override
    @Transactional(readOnly = true)
    public FileDownload downloadAttachment(String attachmentUid) {
        RadiologyAttachment attachment = attachmentRepository.findByUid(attachmentUid)
                .orElseThrow(() -> new NotFoundException(
                        "Radiology attachment not found: " + attachmentUid));

        Radiology r = attachment.getRadiology();
        if (r.getStatus() != RadiologyStatus.VERIFIED) {
            throw new InvalidPatientOperationException(
                    "Could not download. Radiology is not verified");
        }

        byte[] bytes = fileStoragePort.read(attachment.getFileName());
        return new FileDownload(attachment.getFileName(), bytes);
    }

    /**
     * List all named attachments for a radiology order.
     *
     * <p>Note: the download-gated-on-VERIFIED rule (PatientResource.java:6154) is enforced
     * at the controller layer — the service returns all attachments regardless of status.
     */
    @Override
    @Transactional(readOnly = true)
    public List<RadiologyAttachmentDto> listAttachments(String radiologyUid) {
        Radiology r = requireRadiology(radiologyUid);
        return radiologyMapper.toAttachmentDtoList(
                attachmentRepository.findByRadiologyOrderByCreatedAtAsc(r));
    }

    /**
     * Delete a named attachment.
     *
     * <p>Guard: blocked when parent radiology order status == VERIFIED (order is finalized).
     * Verbatim legacy message: "Could not delete. Radiology already verified"
     * (PatientResource.java:6154-6156).
     *
     * <p><strong>Approved deviation:</strong> after the row is deleted, the backing file is
     * also removed from disk via {@link FileStoragePort#delete} (best-effort — a missing file
     * is ignored). The legacy does NOT unlink the disk file. Deviation is
     * security-architect-approved; see {@link FileStoragePort#delete}.
     */
    @Override
    @Transactional
    public void deleteAttachment(String attachmentUid, TxAuditContext ctx) {
        RadiologyAttachment attachment = attachmentRepository.findByUid(attachmentUid)
                .orElseThrow(() -> new NotFoundException(
                        "Radiology attachment not found: " + attachmentUid));

        Radiology r = attachment.getRadiology();
        if (!r.canDeleteAttachment()) {
            // Verbatim legacy message (PatientResource.java:6154-6156).
            throw new InvalidPatientOperationException(
                    "Could not delete. Radiology already verified");
        }

        String fileName = attachment.getFileName();
        attachmentRepository.delete(attachment);
        auditRecorder.record(AUDIT_ATTACHMENT, attachmentUid, AuditAction.DELETE,
                ctx.actorUsername());

        // Best-effort disk unlink (approved deviation — prevents orphaned files).
        fileStoragePort.delete(fileName);
    }

    // =========================================================================
    // Queries
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public RadiologyDto getByUid(String radiologyUid) {
        return radiologyMapper.toDto(requireRadiology(radiologyUid));
    }

    /**
     * Radiology department worklist — settled orders in actionable statuses {PENDING, ACCEPTED}.
     * COLLECTED is a dead state and is excluded from the worklist.
     *
     * <p>The settled flag replaces reading billing bill status (CR-INC05-01, ADR-0022 D4).
     *
     * @param statusFilter optional single-status filter (null = all actionable statuses)
     */
    @Override
    @Transactional(readOnly = true)
    public List<RadiologyDto> worklist(RadiologyStatus statusFilter) {
        List<Radiology> radiologies;
        if (statusFilter != null) {
            radiologies = radiologyRepository.findBySettledAndStatusOrderByCreatedAtAsc(
                    true, statusFilter);
        } else {
            radiologies = radiologyRepository.findBySettledAndStatusInOrderByCreatedAtAsc(
                    true, List.of(RadiologyStatus.PENDING, RadiologyStatus.ACCEPTED));
        }
        return radiologyMapper.toDtoList(radiologies);
    }

    /**
     * All radiology orders for a consultation, ordered by creation time ascending.
     */
    @Override
    @Transactional(readOnly = true)
    public List<RadiologyDto> listForConsultation(String consultationUid) {
        Consultation consultation = requireConsultation(consultationUid);
        return radiologyMapper.toDtoList(
                radiologyRepository.findByConsultationOrderByCreatedAtAsc(consultation));
    }

    /**
     * All radiology orders for a patient, optionally filtered by status.
     */
    @Override
    @Transactional(readOnly = true)
    public List<RadiologyDto> byPatient(String patientUid, RadiologyStatus statusFilter) {
        List<Radiology> radiologies;
        if (statusFilter != null) {
            radiologies = radiologyRepository.findByPatientUidAndStatusOrderByCreatedAtDesc(
                    patientUid, statusFilter);
        } else {
            radiologies = radiologyRepository.findByPatientUidOrderByCreatedAtDesc(patientUid);
        }
        return radiologyMapper.toDtoList(radiologies);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Radiology requireRadiology(String uid) {
        return radiologyRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Radiology order not found: " + uid));
    }

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + uid));
    }

    private NonConsultation requireNonConsultation(String uid) {
        return nonConsultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("NonConsultation not found: " + uid));
    }

    private void requireRadiologyTypeExists(String radiologyTypeUid) {
        if (!radiologyTypeLookup.existsByUid(radiologyTypeUid)) {
            throw new NotFoundException("Radiology type not found");
        }
    }

    private void guardNoDuplicateOnConsultation(Consultation consultation,
                                                 String radiologyTypeUid) {
        if (radiologyRepository.existsByConsultationAndRadiologyTypeUid(
                consultation, radiologyTypeUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate radiology type is not allowed for this encounter");
        }
    }

    private void guardNoDuplicateOnNonConsultation(NonConsultation nonConsultation,
                                                    String radiologyTypeUid) {
        if (radiologyRepository.existsByNonConsultationAndRadiologyTypeUid(
                nonConsultation, radiologyTypeUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate radiology type is not allowed for this encounter");
        }
    }

    /**
     * Derive the local settled flag from the billing ChargeResult and payment context.
     * Identical logic to LabTestService (CR-INC05-01, ADR-0022 D4).
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
