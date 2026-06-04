package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.RadiologyAttachmentDto;
import com.otapp.hmis.clinical.application.dto.RadiologyAttachmentRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyDto;
import com.otapp.hmis.clinical.application.dto.RadiologyOrderRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyRejectRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyReportRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyResultRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyVerifyRequest;
import com.otapp.hmis.clinical.domain.RadiologyStatus;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Public intra-module boundary for the Radiology aggregate lifecycle (inc-05 C8).
 *
 * <p>The web layer ({@link com.otapp.hmis.clinical.web.RadiologyController}) cannot reference
 * the package-private {@link RadiologyService} directly. This interface is the only public type
 * in {@code clinical.application} that the controller may depend on for radiology operations.
 *
 * <p>Mirrors the pattern of {@link LabTestPort} (C7) with the radiology-specific deltas:
 * <ul>
 *   <li>NO collect() method — COLLECTED is a dead state (CR-INC05-14).</li>
 *   <li>verify() transitions ACCEPTED → VERIFIED directly (PatientResource.java:4280-4281).</li>
 *   <li>saveResult() allowed when ACCEPTED (not COLLECTED — PatientResource.java:4305-4306).</li>
 *   <li>addAttachment() allowed when ACCEPTED (not COLLECTED — PatientServiceImpl.java:2931-2933).</li>
 *   <li>reject does NOT clear rejectComment on subsequent accept (asymmetry vs lab).</li>
 * </ul>
 */
public interface RadiologyPort {

    // -------------------------------------------------------------------------
    // Order creation
    // -------------------------------------------------------------------------

    /**
     * Order a radiology examination on a consultation (outpatient path).
     *
     * <p>Guards: consultation exists; patient is OUTPATIENT; radiologyType exists; no duplicate
     * type on this consultation. Creates billing charge (kind=RADIOLOGY, serviceUid=radiologyTypeUid).
     * Returns PENDING order with settled flag set from the charge result.
     *
     * @param consultationUid the ULID of the owning consultation
     * @param request         order details
     * @param ctx             transaction audit context
     * @return the created RadiologyDto (status=PENDING)
     */
    RadiologyDto orderForConsultation(String consultationUid, RadiologyOrderRequest request,
                                      TxAuditContext ctx);

    /**
     * Order a radiology examination on a non-consultation (OUTSIDER/walk-in path).
     *
     * <p>Guards: non-consultation exists and is IN_PROCESS; radiologyType exists; no duplicate
     * type on this non-consultation. Creates billing charge.
     *
     * @param nonConsultationUid the ULID of the owning non-consultation
     * @param request            order details
     * @param ctx                transaction audit context
     * @return the created RadiologyDto (status=PENDING)
     */
    RadiologyDto orderForNonConsultation(String nonConsultationUid, RadiologyOrderRequest request,
                                         TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    /** Accept: PENDING|REJECTED → ACCEPTED. Guard enforced in service. */
    RadiologyDto accept(String radiologyUid, TxAuditContext ctx);

    /** Reject: PENDING|ACCEPTED → REJECTED. Guard enforced in service. */
    RadiologyDto reject(String radiologyUid, RadiologyRejectRequest request, TxAuditContext ctx);

    /**
     * Edit the rejection comment on an already-REJECTED order (inc-06A C3 / ITEM3,
     * legacy save_reason_for_rejection). Guard: status must be REJECTED, enforced in service.
     */
    RadiologyDto saveRejectComment(String radiologyUid, RadiologyRejectRequest request,
                                   TxAuditContext ctx);

    /**
     * Verify: ACCEPTED → VERIFIED. Writes result/report/attachment blob.
     *
     * <p>Active path goes ACCEPTED → VERIFIED DIRECTLY (PatientResource.java:4280-4281).
     * NO collect step (CR-INC05-14). Guard enforced in service.
     */
    RadiologyDto verify(String radiologyUid, RadiologyVerifyRequest request, TxAuditContext ctx);

    /** Hold (revert): ACCEPTED → PENDING. Guard enforced in service. */
    RadiologyDto hold(String radiologyUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Result edit (no status change) — ACCEPTED gate
    // -------------------------------------------------------------------------

    /**
     * Save result text (status must be ACCEPTED — PatientResource.java:4305-4306 parity).
     * Radiology result edit is allowed when ACCEPTED, unlike lab (COLLECTED).
     */
    RadiologyDto saveResult(String radiologyUid, RadiologyResultRequest request,
                            TxAuditContext ctx);

    /**
     * Add/update the radiologist report (inc-06A C5 / ITEM2, legacy radiologies/add_report).
     * Gated on the BILL status ({@code PAID|COVERED|VERIFIED}), independent of order status.
     * Guard + bill-gate enforced in the service.
     */
    RadiologyDto addReport(String radiologyUid, RadiologyReportRequest request,
                           TxAuditContext ctx);

    /**
     * Amend a VERIFIED report (inc-06A C6 / ITEM4 audited-amend). Retains the prior narrative and
     * stamps the amend audit triplet. Guard: status==VERIFIED + bill-gate, enforced in service.
     */
    RadiologyDto amendReport(String radiologyUid, RadiologyReportRequest request,
                             TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Hard-delete a PENDING radiology order.
     *
     * <p>Only allowed when status == PENDING (else 422).
     * Credit-note for paid bills: DEFERRED.
     *
     * @param radiologyUid the ULID of the radiology order to delete
     * @param ctx          transaction audit context
     */
    void delete(String radiologyUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Attachments
    // -------------------------------------------------------------------------

    /**
     * Add a named file attachment (status must be ACCEPTED; max 5 per order).
     * ACCEPTED gate (PatientServiceImpl.java:2931-2933) — different from lab COLLECTED gate.
     */
    RadiologyAttachmentDto addAttachment(String radiologyUid, RadiologyAttachmentRequest request,
                                         TxAuditContext ctx);

    /**
     * Upload a file attachment to a radiology order (multipart path, inc-06A C7 / ITEM5).
     *
     * <p>Guard order (legacy-parity): (1) size cap → 422 "File exceeds maximum file size allowed";
     * (2) status/count gate via {@code canAttach} → 422 verbatim messages. On success: stores
     * bytes via {@link com.otapp.hmis.shared.storage.FileStoragePort}, persists the row with
     * the generated storage filename, audit CREATE, returns 201 DTO.
     *
     * <p>ACCEPTED gate for radiology — PatientServiceImpl.java:2922-2996.
     *
     * @param radiologyUid     owning radiology order ULID
     * @param bytes            raw file bytes extracted at the controller layer
     * @param originalFilename client-supplied filename (used to derive extension only)
     * @param name             optional display name for the attachment
     * @param ctx              transaction audit context
     * @return the created RadiologyAttachmentDto (fileName is the opaque storage key)
     */
    RadiologyAttachmentDto uploadAttachment(String radiologyUid, byte[] bytes,
                                            String originalFilename, String name,
                                            TxAuditContext ctx);

    /**
     * Download the bytes of a radiology attachment (VERIFIED-gate, inc-06A C7 / ITEM5).
     *
     * <p>Guard: parent radiology order must be VERIFIED (PatientResource.java:6154) —
     * else 422 "Could not download. Radiology is not verified".
     *
     * @param attachmentUid the ULID of the attachment to download
     * @return a {@link FileDownload} record with the storage filename and bytes
     */
    FileDownload downloadAttachment(String attachmentUid);

    /** List named attachments for a radiology order. */
    List<RadiologyAttachmentDto> listAttachments(String radiologyUid);

    /** Delete a named attachment (blocked when parent status == VERIFIED). */
    void deleteAttachment(String attachmentUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** Get a radiology order by ULID. */
    RadiologyDto getByUid(String radiologyUid);

    /**
     * Radiology department worklist — settled orders in actionable statuses {PENDING, ACCEPTED}.
     *
     * @param statusFilter optional status filter (null = all actionable statuses)
     * @return the worklist
     */
    List<RadiologyDto> worklist(RadiologyStatus statusFilter);

    /**
     * All radiology orders for a patient (by uid), optionally filtered by status.
     *
     * @param patientUid   the patient ULID
     * @param statusFilter optional status filter (null = all statuses)
     * @return radiology orders for this patient
     */
    List<RadiologyDto> byPatient(String patientUid, RadiologyStatus statusFilter);

    /**
     * All radiology orders for a consultation, ordered by creation time ascending.
     *
     * @param consultationUid the ULID of the consultation
     * @return radiology orders for this consultation, oldest first
     */
    List<RadiologyDto> listForConsultation(String consultationUid);
}
