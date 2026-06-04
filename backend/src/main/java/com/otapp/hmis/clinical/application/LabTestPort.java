package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.LabTestAttachmentDto;
import com.otapp.hmis.clinical.application.dto.LabTestAttachmentRequest;
import com.otapp.hmis.clinical.application.dto.LabTestDto;
import com.otapp.hmis.clinical.application.dto.LabTestOrderRequest;
import com.otapp.hmis.clinical.application.dto.LabTestRejectRequest;
import com.otapp.hmis.clinical.application.dto.LabTestReportRequest;
import com.otapp.hmis.clinical.application.dto.LabTestResultRequest;
import com.otapp.hmis.clinical.application.dto.LabTestVerifyRequest;
import com.otapp.hmis.clinical.domain.LabTestStatus;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Public intra-module boundary for the LabTest aggregate lifecycle (inc-05 C7).
 *
 * <p>The web layer ({@link com.otapp.hmis.clinical.web.LabTestController}) cannot reference
 * the package-private {@link LabTestService} directly. This interface is the only public type
 * in {@code clinical.application} that the controller may depend on for lab test operations.
 *
 * <p>Mirrors the pattern of {@link DiagnosisPort} / {@link ConsultationLifecyclePort}.
 */
public interface LabTestPort {

    // -------------------------------------------------------------------------
    // Order creation
    // -------------------------------------------------------------------------

    /**
     * Order a lab test on a consultation (outpatient path).
     *
     * <p>Guards: consultation exists; patient is OUTPATIENT (status IN_PROCESS guard deferred to
     * clinical-ops spec — the consultation must be in an active state per legacy parity);
     * labTestType exists; no duplicate type on this consultation.
     * Creates billing charge (kind=LAB_TEST, serviceUid=labTestTypeUid).
     * Returns PENDING order with settled flag set from the charge result.
     *
     * @param consultationUid the ULID of the owning consultation
     * @param request         order details
     * @param ctx             transaction audit context
     * @return the created LabTestDto (status=PENDING)
     */
    LabTestDto orderForConsultation(String consultationUid, LabTestOrderRequest request,
                                    TxAuditContext ctx);

    /**
     * Order a lab test on a non-consultation (OUTSIDER/walk-in path).
     *
     * <p>Guards: non-consultation exists and is IN_PROCESS; labTestType exists; no duplicate
     * type on this non-consultation. Uses WalkInService.getOrCreateInProcess for OUTSIDER
     * walk-in creation. Creates billing charge.
     *
     * @param nonConsultationUid the ULID of the owning non-consultation
     * @param request            order details (must include patientUid for billing)
     * @param ctx                transaction audit context
     * @return the created LabTestDto (status=PENDING)
     */
    LabTestDto orderForNonConsultation(String nonConsultationUid, LabTestOrderRequest request,
                                       TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    /** Accept: PENDING|REJECTED → ACCEPTED. Guard enforced in service. */
    LabTestDto accept(String labTestUid, TxAuditContext ctx);

    /** Reject: PENDING|ACCEPTED → REJECTED. Guard enforced in service. */
    LabTestDto reject(String labTestUid, LabTestRejectRequest request, TxAuditContext ctx);

    /**
     * Edit the rejection comment on an already-REJECTED order (inc-06A C3 / ITEM3,
     * legacy save_reason_for_rejection). Guard: status must be REJECTED, enforced in service.
     */
    LabTestDto saveRejectComment(String labTestUid, LabTestRejectRequest request, TxAuditContext ctx);

    /** Collect: ACCEPTED → COLLECTED. Guard enforced in service. */
    LabTestDto collect(String labTestUid, TxAuditContext ctx);

    /** Verify: COLLECTED → VERIFIED. Writes result fields. Guard enforced in service. */
    LabTestDto verify(String labTestUid, LabTestVerifyRequest request, TxAuditContext ctx);

    /** Hold (revert): ACCEPTED → PENDING. Guard enforced in service. */
    LabTestDto hold(String labTestUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Result / report edits (no status change)
    // -------------------------------------------------------------------------

    /** Save result text (status must be COLLECTED). */
    LabTestDto saveResult(String labTestUid, LabTestResultRequest request, TxAuditContext ctx);

    /** Add/update report text (status must be COLLECTED). */
    LabTestDto addReport(String labTestUid, LabTestReportRequest request, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Hard-delete a PENDING lab test order.
     *
     * <p>Only allowed when status == PENDING (else 422).
     * Credit-note for paid bills: DEFERRED (see LabTestService javadoc).
     *
     * @param labTestUid the ULID of the lab test to delete
     * @param ctx        transaction audit context
     */
    void delete(String labTestUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Attachments
    // -------------------------------------------------------------------------

    /** Add an attachment (status must be COLLECTED; max 5 per test). */
    LabTestAttachmentDto addAttachment(String labTestUid, LabTestAttachmentRequest request,
                                       TxAuditContext ctx);

    /** List attachments for a lab test. */
    List<LabTestAttachmentDto> listAttachments(String labTestUid);

    /** Delete an attachment (blocked when parent status == VERIFIED). */
    void deleteAttachment(String attachmentUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** Get a lab test by ULID. */
    LabTestDto getByUid(String labTestUid);

    /**
     * Lab department worklist — settled orders in actionable statuses.
     *
     * @param statusFilter optional status filter (null = all actionable statuses)
     * @return the worklist
     */
    List<LabTestDto> worklist(LabTestStatus statusFilter);

    /**
     * All lab tests for a patient (by uid), optionally filtered by status.
     *
     * @param patientUid   the patient ULID
     * @param statusFilter optional status filter (null = all statuses)
     * @return lab tests for this patient
     */
    List<LabTestDto> byPatient(String patientUid, LabTestStatus statusFilter);

    /**
     * All lab tests for a consultation, ordered by creation time ascending.
     *
     * <p>Returns an empty list if the consultation has no lab test orders.
     * Returns 404 if the consultation uid is unknown.
     *
     * @param consultationUid the ULID of the consultation
     * @return lab tests for this consultation, oldest first
     */
    List<LabTestDto> listForConsultation(String consultationUid);
}
