package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.application.FileDownload;
import com.otapp.hmis.clinical.application.RadiologyPort;
import com.otapp.hmis.clinical.application.dto.RadiologyAttachmentDto;
import com.otapp.hmis.clinical.application.dto.RadiologyAttachmentRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyDto;
import com.otapp.hmis.clinical.application.dto.RadiologyOrderRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyRejectRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyReportRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyResultRequest;
import com.otapp.hmis.clinical.application.dto.RadiologyVerifyRequest;
import com.otapp.hmis.clinical.domain.RadiologyStatus;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for the Radiology aggregate (inc-05 C8).
 *
 * <p>All endpoints are <strong>authenticated-only</strong> (no {@code @PreAuthorize})
 * per CR-INC05-02 parity — radiology endpoints carry no additional legacy RBAC gates
 * beyond authentication.
 *
 * <p>No {@code @Transactional} on the controller — the service owns the transaction
 * boundary (ADR-0014 §5).
 *
 * <p><strong>Base paths:</strong>
 * <ul>
 *   <li>{@code /api/v1/clinical/consultations}     — consultation-scoped order creation</li>
 *   <li>{@code /api/v1/clinical/non-consultations} — non-consultation-scoped order creation</li>
 *   <li>{@code /api/v1/clinical/radiologies}       — radiology lifecycle + worklist + by-patient</li>
 * </ul>
 *
 * <p><strong>Endpoint surface (16 endpoints):</strong>
 * <ul>
 *   <li>{@code POST   /consultations/uid/{uid}/radiologies}              → 201 (order on consultation)</li>
 *   <li>{@code GET    /consultations/uid/{uid}/radiologies}              → 200 (list by consultation)</li>
 *   <li>{@code POST   /non-consultations/uid/{uid}/radiologies}          → 201 (order on walk-in)</li>
 *   <li>{@code GET    /radiologies/uid/{uid}}                            → 200 (get by uid)</li>
 *   <li>{@code POST   /radiologies/uid/{uid}/accept}                     → 200 (accept)</li>
 *   <li>{@code POST   /radiologies/uid/{uid}/reject}                     → 200 (reject)</li>
 *   <li>{@code POST   /radiologies/uid/{uid}/verify}                     → 200 (verify ACCEPTED→VERIFIED)</li>
 *   <li>{@code POST   /radiologies/uid/{uid}/hold}                       → 200 (hold/revert)</li>
 *   <li>{@code PUT    /radiologies/uid/{uid}/result}                     → 200 (save result, ACCEPTED)</li>
 *   <li>{@code DELETE /radiologies/uid/{uid}}                            → 204 (delete PENDING)</li>
 *   <li>{@code POST   /radiologies/uid/{uid}/attachments}                → 201 (add attachment)</li>
 *   <li>{@code GET    /radiologies/uid/{uid}/attachments}                → 200 (list attachments)</li>
 *   <li>{@code DELETE /radiologies/attachments/uid/{attachmentUid}}      → 204 (delete attachment)</li>
 *   <li>{@code GET    /radiologies/worklist}                             → 200 (dept worklist)</li>
 *   <li>{@code GET    /radiologies}                                      → 200 (by-patient query)</li>
 * </ul>
 *
 * <p><strong>NO collect endpoint</strong> — COLLECTED is a dead state (CR-INC05-14).
 * The legacy {@code collect_radiology111} endpoint is malformed/dead and is NOT exposed here.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Lifecycle: PatientResource.java:4280-4292</li>
 *   <li>verify from ACCEPTED: PatientResource.java:4280-4281</li>
 *   <li>Dead collect: PatientResource.java:4317, CR-INC05-14</li>
 *   <li>Attachments: PatientServiceImpl.java:2928-2933; PatientResource.java:6154</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class RadiologyController {

    private final RadiologyPort      radiologyService;
    private final BusinessDayService businessDayService;

    // =========================================================================
    // Order creation — consultation path
    // POST /api/v1/clinical/consultations/uid/{uid}/radiologies
    // =========================================================================

    /**
     * Order a radiology examination on an existing consultation (outpatient path).
     *
     * <p>Guards: consultation exists (404); radiologyType exists (404 "Radiology type not found");
     * no duplicate type for this consultation (422 "Duplicate radiology type is not allowed for
     * this encounter"). Creates a PatientBill via billing charge. Returns PENDING order.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/consultations/uid/{uid}/radiologies")
    @ResponseStatus(HttpStatus.CREATED)
    public RadiologyDto orderForConsultation(
            @PathVariable("uid") String consultationUid,
            @Valid @RequestBody RadiologyOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.orderForConsultation(consultationUid, request, ctxFrom(jwt));
    }

    /**
     * List all radiology orders for a consultation (ordered by creation time ascending).
     *
     * <p>Returns an empty list if the consultation has no radiology orders.
     * Returns 404 if the consultation uid is unknown.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/consultations/uid/{uid}/radiologies")
    public List<RadiologyDto> listForConsultation(
            @PathVariable("uid") String consultationUid,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.listForConsultation(consultationUid);
    }

    // =========================================================================
    // Order creation — non-consultation (walk-in/OUTSIDER) path
    // POST /api/v1/clinical/non-consultations/uid/{uid}/radiologies
    // =========================================================================

    /**
     * Order a radiology examination on an existing non-consultation (OUTSIDER/walk-in path).
     *
     * <p>Guards: non-consultation exists (404); radiologyType exists (404); no duplicate type
     * for this non-consultation (422). Creates a PatientBill via billing charge. Returns PENDING.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/non-consultations/uid/{uid}/radiologies")
    @ResponseStatus(HttpStatus.CREATED)
    public RadiologyDto orderForNonConsultation(
            @PathVariable("uid") String nonConsultationUid,
            @Valid @RequestBody RadiologyOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.orderForNonConsultation(nonConsultationUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Get by uid
    // GET /api/v1/clinical/radiologies/uid/{uid}
    // =========================================================================

    /**
     * Get a radiology order by ULID.
     *
     * <p>Returns 404 if the uid is unknown. No id in response (ADR-0014 §1).
     * Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/radiologies/uid/{uid}")
    public RadiologyDto getByUid(
            @PathVariable("uid") String radiologyUid,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.getByUid(radiologyUid);
    }

    // =========================================================================
    // Lifecycle transitions
    // POST /api/v1/clinical/radiologies/uid/{uid}/<transition>
    // =========================================================================

    /**
     * Accept the radiology order: PENDING | REJECTED → ACCEPTED.
     *
     * <p>Guard: status must be PENDING or REJECTED (else 422).
     * NO bill re-check (CR-INC05-01 parity).
     * NOTE: rejectComment is NOT cleared on accept (radiology asymmetry vs lab).
     * Authenticated-only.
     */
    @PostMapping("/radiologies/uid/{uid}/accept")
    public RadiologyDto accept(
            @PathVariable("uid") String radiologyUid,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.accept(radiologyUid, ctxFrom(jwt));
    }

    /**
     * Reject the radiology order: PENDING | ACCEPTED → REJECTED.
     *
     * <p>Guard: status must be PENDING or ACCEPTED (else 422).
     * Sets rejectComment; clears accept fields. Authenticated-only.
     */
    @PostMapping("/radiologies/uid/{uid}/reject")
    public RadiologyDto reject(
            @PathVariable("uid") String radiologyUid,
            @RequestBody(required = false) RadiologyRejectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        RadiologyRejectRequest req = request != null ? request : new RadiologyRejectRequest(null);
        return radiologyService.reject(radiologyUid, req, ctxFrom(jwt));
    }

    /**
     * Edit the rejection comment on an already-REJECTED radiology order (inc-06A C3 / ITEM3,
     * legacy save_reason_for_rejection). Re-callable; sets only rejectComment.
     * Guard: status must be REJECTED (else 422 "Could not save. Only allowed for rejected tests").
     * Authenticated-only.
     */
    @PostMapping("/radiologies/uid/{uid}/reject-comment")
    public RadiologyDto saveRejectComment(
            @PathVariable("uid") String radiologyUid,
            @RequestBody(required = false) RadiologyRejectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        RadiologyRejectRequest req = request != null ? request : new RadiologyRejectRequest(null);
        return radiologyService.saveRejectComment(radiologyUid, req, ctxFrom(jwt));
    }

    /**
     * Verify result: ACCEPTED → VERIFIED DIRECTLY. Writes result/report/inline-blob from body.
     *
     * <p><strong>Active path goes ACCEPTED → VERIFIED with NO collect step (CR-INC05-14).</strong>
     * Guard: status must be ACCEPTED (else 422 "Please accept the radiology order first").
     * Authenticated-only.
     */
    @PostMapping("/radiologies/uid/{uid}/verify")
    public RadiologyDto verify(
            @PathVariable("uid") String radiologyUid,
            @RequestBody(required = false) RadiologyVerifyRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        RadiologyVerifyRequest req = request != null ? request
                : new RadiologyVerifyRequest(null, null, null);
        return radiologyService.verify(radiologyUid, req, ctxFrom(jwt));
    }

    /**
     * Hold (revert): ACCEPTED → PENDING. Stamps held_* audit triplet.
     *
     * <p>Guard: status must be ACCEPTED (else 422 "Radiology order must be accepted to hold").
     * Authenticated-only.
     */
    @PostMapping("/radiologies/uid/{uid}/hold")
    public RadiologyDto hold(
            @PathVariable("uid") String radiologyUid,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.hold(radiologyUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Result edit (no status change) — ACCEPTED gate
    // PUT /api/v1/clinical/radiologies/uid/{uid}/result
    // =========================================================================

    /**
     * Save result text without status change. Status must be ACCEPTED.
     *
     * <p>Radiology edits when ACCEPTED (PatientResource.java:4305-4306) — different from
     * lab which requires COLLECTED. Authenticated-only.
     */
    @PutMapping("/radiologies/uid/{uid}/result")
    public RadiologyDto saveResult(
            @PathVariable("uid") String radiologyUid,
            @RequestBody RadiologyResultRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.saveResult(radiologyUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Stand-alone bill-gated add_report (inc-06A C5 / ITEM2)
    // POST /api/v1/clinical/radiologies/uid/{uid}/add-report
    // =========================================================================

    /**
     * Add/update the radiologist report (legacy radiologies/add_report). Gated on the BILL status
     * ({@code PAID|COVERED|VERIFIED}), independent of order status — 422
     * "Could not add report. Payment not verified" otherwise. Authenticated-only.
     */
    @PostMapping("/radiologies/uid/{uid}/add-report")
    public RadiologyDto addReport(
            @PathVariable("uid") String radiologyUid,
            @RequestBody RadiologyReportRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.addReport(radiologyUid, request, ctxFrom(jwt));
    }

    /**
     * Amend a VERIFIED radiology report via the audited-amend path (inc-06A C6 / ITEM4).
     * Retains the prior narrative + stamps the amend audit triplet. Same bill-gate as add-report;
     * guard: status must be VERIFIED. Authenticated-only.
     */
    @PostMapping("/radiologies/uid/{uid}/amend-report")
    public RadiologyDto amendReport(
            @PathVariable("uid") String radiologyUid,
            @RequestBody RadiologyReportRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.amendReport(radiologyUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Delete
    // DELETE /api/v1/clinical/radiologies/uid/{uid}
    // =========================================================================

    /**
     * Hard-delete a PENDING radiology order. Only allowed when status == PENDING (else 422).
     * Credit-note for already-PAID bills is DEFERRED. Authenticated-only.
     */
    @DeleteMapping("/radiologies/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("uid") String radiologyUid,
            @AuthenticationPrincipal Jwt jwt) {
        radiologyService.delete(radiologyUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Attachments
    // POST   /api/v1/clinical/radiologies/uid/{uid}/attachments
    // GET    /api/v1/clinical/radiologies/uid/{uid}/attachments
    // DELETE /api/v1/clinical/radiologies/attachments/uid/{attachmentUid}
    // =========================================================================

    /**
     * Add a named file attachment to a radiology order.
     *
     * <p>Guards: status must be ACCEPTED (422 "Radiology order must be accepted before adding
     * attachments" — NOTE: ACCEPTED gate, not COLLECTED like lab); max 5 attachments (422).
     * Authenticated-only.
     */
    @PostMapping("/radiologies/uid/{uid}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public RadiologyAttachmentDto addAttachment(
            @PathVariable("uid") String radiologyUid,
            @Valid @RequestBody RadiologyAttachmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.addAttachment(radiologyUid, request, ctxFrom(jwt));
    }

    /**
     * List named attachments for a radiology order.
     *
     * <p>Note: download/view is gated on VERIFIED (PatientResource.java:6154) — documented here
     * but the endpoint returns the list regardless; the client enforces the display gate.
     * Authenticated-only.
     */
    @GetMapping("/radiologies/uid/{uid}/attachments")
    public List<RadiologyAttachmentDto> listAttachments(
            @PathVariable("uid") String radiologyUid,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.listAttachments(radiologyUid);
    }

    /**
     * Delete a named attachment.
     *
     * <p>Guard: blocked when parent status == VERIFIED (422 "Cannot delete attachment from a
     * verified radiology order"). Authenticated-only.
     */
    @DeleteMapping("/radiologies/attachments/uid/{attachmentUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(
            @PathVariable("attachmentUid") String attachmentUid,
            @AuthenticationPrincipal Jwt jwt) {
        radiologyService.deleteAttachment(attachmentUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Attachment file storage (inc-06A C7 / ITEM5) — multipart upload + inline download
    // POST /api/v1/clinical/radiologies/uid/{uid}/attachments/upload
    // GET  /api/v1/clinical/radiologies/attachments/uid/{attachmentUid}/download
    // =========================================================================

    /**
     * Upload a file attachment (multipart). Guards: size cap (10 MiB → 422
     * "File exceeds maximum file size allowed"), then the ACCEPTED status + max-5 gate.
     * Bytes stored on local disk; the row holds the opaque storage filename. Authenticated-only.
     */
    @PostMapping(path = "/radiologies/uid/{uid}/attachments/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public RadiologyAttachmentDto uploadAttachment(
            @PathVariable("uid") String radiologyUid,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @AuthenticationPrincipal Jwt jwt) throws IOException {
        return radiologyService.uploadAttachment(
                radiologyUid, file.getBytes(), file.getOriginalFilename(), name, ctxFrom(jwt));
    }

    /**
     * Download an attachment's bytes inline. Guard: parent radiology must be VERIFIED
     * (else 422 "Could not download. Radiology is not verified"). Authenticated-only.
     */
    @GetMapping("/radiologies/attachments/uid/{attachmentUid}/download")
    public ResponseEntity<byte[]> downloadAttachment(
            @PathVariable("attachmentUid") String attachmentUid,
            @AuthenticationPrincipal Jwt jwt) {
        FileDownload dl = radiologyService.downloadAttachment(attachmentUid);
        MediaType contentType = MediaTypeFactory.getMediaType(dl.fileName())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(dl.fileName()).build();
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(dl.bytes());
    }

    // =========================================================================
    // Worklist
    // GET /api/v1/clinical/radiologies/worklist
    // =========================================================================

    /**
     * Radiology department worklist — settled orders in actionable statuses {PENDING, ACCEPTED}.
     *
     * <p>The settled filter (local flag) replaces reading billing bill status (CR-INC05-01).
     * COLLECTED is excluded — it is a dead state (CR-INC05-14).
     * Optional {@code status} query param to filter to a single status.
     * Authenticated-only.
     *
     * @param status optional status filter (PENDING / ACCEPTED)
     */
    @GetMapping("/radiologies/worklist")
    public List<RadiologyDto> worklist(
            @RequestParam(name = "status", required = false) RadiologyStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.worklist(status);
    }

    // =========================================================================
    // By-patient query
    // GET /api/v1/clinical/radiologies?patientUid=...&status=...
    // =========================================================================

    /**
     * All radiology orders for a patient, optionally filtered by status.
     *
     * <p>Returns an empty list if the patient has no radiology orders.
     * Ordered newest first. Authenticated-only.
     *
     * @param patientUid MANDATORY patient ULID
     * @param status     optional status filter
     */
    @GetMapping("/radiologies")
    public List<RadiologyDto> byPatient(
            @RequestParam("patientUid") String patientUid,
            @RequestParam(name = "status", required = false) RadiologyStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        return radiologyService.byPatient(patientUid, status);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Build the per-operation audit context at the controller edge (ADR-0008 §3). */
    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(),
                jwt.getSubject());
    }
}
