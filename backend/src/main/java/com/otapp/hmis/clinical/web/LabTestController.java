package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.application.LabTestPort;
import com.otapp.hmis.clinical.application.dto.LabTestAttachmentDto;
import com.otapp.hmis.clinical.application.dto.LabTestAttachmentRequest;
import com.otapp.hmis.clinical.application.dto.LabTestDto;
import com.otapp.hmis.clinical.application.dto.LabTestOrderRequest;
import com.otapp.hmis.clinical.application.dto.LabTestRejectRequest;
import com.otapp.hmis.clinical.application.dto.LabTestReportRequest;
import com.otapp.hmis.clinical.application.dto.LabTestResultRequest;
import com.otapp.hmis.clinical.application.dto.LabTestVerifyRequest;
import com.otapp.hmis.clinical.domain.LabTestStatus;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the LabTest aggregate (inc-05 C7).
 *
 * <p>All endpoints are <strong>authenticated-only</strong> (no {@code @PreAuthorize})
 * per CR-INC05-02 parity — lab test endpoints carry no additional legacy RBAC gates
 * beyond authentication (11-DECISIONS-RATIFIED.md §2).
 *
 * <p>No {@code @Transactional} on the controller — the service owns the transaction
 * boundary (ADR-0014 §5).
 *
 * <p><strong>Base paths:</strong>
 * <ul>
 *   <li>{@code /api/v1/clinical/consultations} — consultation-scoped order creation</li>
 *   <li>{@code /api/v1/clinical/non-consultations} — non-consultation-scoped order creation</li>
 *   <li>{@code /api/v1/clinical/lab-tests} — lab test lifecycle + worklist + by-patient</li>
 * </ul>
 *
 * <p><strong>Endpoint surface (17 endpoints):</strong>
 * <ul>
 *   <li>{@code POST   /consultations/uid/{uid}/lab-tests}              → 201 (order on consultation)</li>
 *   <li>{@code POST   /non-consultations/uid/{uid}/lab-tests}          → 201 (order on walk-in)</li>
 *   <li>{@code GET    /lab-tests/uid/{uid}}                            → 200 (get by uid)</li>
 *   <li>{@code POST   /lab-tests/uid/{uid}/accept}                     → 200 (accept)</li>
 *   <li>{@code POST   /lab-tests/uid/{uid}/reject}                     → 200 (reject)</li>
 *   <li>{@code POST   /lab-tests/uid/{uid}/collect}                    → 200 (collect)</li>
 *   <li>{@code POST   /lab-tests/uid/{uid}/verify}                     → 200 (verify + write result)</li>
 *   <li>{@code POST   /lab-tests/uid/{uid}/hold}                       → 200 (hold/revert)</li>
 *   <li>{@code PUT    /lab-tests/uid/{uid}/result}                     → 200 (save result, COLLECTED)</li>
 *   <li>{@code PUT    /lab-tests/uid/{uid}/report}                     → 200 (add report, COLLECTED)</li>
 *   <li>{@code DELETE /lab-tests/uid/{uid}}                            → 204 (delete PENDING)</li>
 *   <li>{@code POST   /lab-tests/uid/{uid}/attachments}                → 201 (add attachment)</li>
 *   <li>{@code GET    /lab-tests/uid/{uid}/attachments}                → 200 (list attachments)</li>
 *   <li>{@code DELETE /lab-tests/attachments/uid/{attachmentUid}}      → 204 (delete attachment)</li>
 *   <li>{@code GET    /lab-tests/worklist}                             → 200 (dept worklist)</li>
 *   <li>{@code GET    /lab-tests}                                      → 200 (by-patient query)</li>
 *   <li>{@code GET    /consultations/uid/{uid}/lab-tests}              → 200 (by consultation)</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Order: PatientServiceImpl.java:790-849</li>
 *   <li>Lifecycle: PatientResource.java:3947-3980</li>
 *   <li>Worklist: PatientResource.java:3668-3717</li>
 *   <li>Attachments: PatientServiceImpl.java:2828-2834; PatientResource.java:6021</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class LabTestController {

    private final LabTestPort     labTestService;
    private final BusinessDayService businessDayService;

    // =========================================================================
    // Order creation — consultation path
    // POST /api/v1/clinical/consultations/uid/{uid}/lab-tests
    // =========================================================================

    /**
     * Order a lab test on an existing consultation (outpatient path).
     *
     * <p>Guards: consultation exists (404); labTestType exists (404 "Lab test type not found");
     * no duplicate type for this consultation (422 "Duplicate lab test type is not allowed for
     * this encounter"). Creates a PatientBill via billing charge. Returns PENDING order.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/consultations/uid/{uid}/lab-tests")
    @ResponseStatus(HttpStatus.CREATED)
    public LabTestDto orderForConsultation(
            @PathVariable("uid") String consultationUid,
            @Valid @RequestBody LabTestOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.orderForConsultation(consultationUid, request, ctxFrom(jwt));
    }

    /**
     * List all lab tests for a consultation (ordered by creation time ascending).
     *
     * <p>Returns an empty list if the consultation has no lab test orders.
     * Returns 404 if the consultation uid is unknown.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/consultations/uid/{uid}/lab-tests")
    public List<LabTestDto> listForConsultation(
            @PathVariable("uid") String consultationUid,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.listForConsultation(consultationUid);
    }

    // =========================================================================
    // Order creation — non-consultation (walk-in/OUTSIDER) path
    // POST /api/v1/clinical/non-consultations/uid/{uid}/lab-tests
    // =========================================================================

    /**
     * Order a lab test on an existing non-consultation (OUTSIDER/walk-in path).
     *
     * <p>Guards: non-consultation exists (404); labTestType exists (404 "Lab test type not found");
     * no duplicate type for this non-consultation (422). Creates a PatientBill via billing charge.
     * Returns PENDING order.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/non-consultations/uid/{uid}/lab-tests")
    @ResponseStatus(HttpStatus.CREATED)
    public LabTestDto orderForNonConsultation(
            @PathVariable("uid") String nonConsultationUid,
            @Valid @RequestBody LabTestOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.orderForNonConsultation(nonConsultationUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Get by uid
    // GET /api/v1/clinical/lab-tests/uid/{uid}
    // =========================================================================

    /**
     * Get a lab test by ULID.
     *
     * <p>Returns 404 if the uid is unknown. No id in response (ADR-0014 §1).
     * Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/lab-tests/uid/{uid}")
    public LabTestDto getByUid(
            @PathVariable("uid") String labTestUid,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.getByUid(labTestUid);
    }

    // =========================================================================
    // Lifecycle transitions
    // POST /api/v1/clinical/lab-tests/uid/{uid}/<transition>
    // =========================================================================

    /**
     * Accept the lab test order: PENDING | REJECTED → ACCEPTED.
     * Guard: status must be PENDING or REJECTED (else 422).
     * NO bill re-check (CR-INC05-01 parity). Authenticated-only.
     */
    @PostMapping("/lab-tests/uid/{uid}/accept")
    public LabTestDto accept(
            @PathVariable("uid") String labTestUid,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.accept(labTestUid, ctxFrom(jwt));
    }

    /**
     * Reject the lab test order: PENDING | ACCEPTED → REJECTED.
     * Guard: status must be PENDING or ACCEPTED (else 422).
     * Sets rejectComment; clears accept fields. Authenticated-only.
     */
    @PostMapping("/lab-tests/uid/{uid}/reject")
    public LabTestDto reject(
            @PathVariable("uid") String labTestUid,
            @RequestBody(required = false) LabTestRejectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        LabTestRejectRequest req = request != null ? request : new LabTestRejectRequest(null);
        return labTestService.reject(labTestUid, req, ctxFrom(jwt));
    }

    /**
     * Edit the rejection comment on an already-REJECTED lab test (inc-06A C3 / ITEM3,
     * legacy save_reason_for_rejection). Re-callable; sets only rejectComment.
     * Guard: status must be REJECTED (else 422 "Could not save. Only allowed for rejected tests").
     * Authenticated-only.
     */
    @PostMapping("/lab-tests/uid/{uid}/reject-comment")
    public LabTestDto saveRejectComment(
            @PathVariable("uid") String labTestUid,
            @RequestBody(required = false) LabTestRejectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        LabTestRejectRequest req = request != null ? request : new LabTestRejectRequest(null);
        return labTestService.saveRejectComment(labTestUid, req, ctxFrom(jwt));
    }

    /**
     * Collect specimen: ACCEPTED → COLLECTED.
     * Guard: status must be ACCEPTED (else 422 "Please accept the lab test first").
     * Authenticated-only.
     */
    @PostMapping("/lab-tests/uid/{uid}/collect")
    public LabTestDto collect(
            @PathVariable("uid") String labTestUid,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.collect(labTestUid, ctxFrom(jwt));
    }

    /**
     * Verify result: COLLECTED → VERIFIED. Writes result/level/testRange/unit from body.
     * Guard: status must be COLLECTED (else 422 "Please collect the lab test first").
     * Authenticated-only.
     */
    @PostMapping("/lab-tests/uid/{uid}/verify")
    public LabTestDto verify(
            @PathVariable("uid") String labTestUid,
            @RequestBody(required = false) LabTestVerifyRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        LabTestVerifyRequest req = request != null ? request
                : new LabTestVerifyRequest(null, null, null, null);
        return labTestService.verify(labTestUid, req, ctxFrom(jwt));
    }

    /**
     * Hold (revert): ACCEPTED → PENDING. Stamps held_* audit triplet.
     * Guard: status must be ACCEPTED (else 422 "Lab test must be accepted to hold").
     * Authenticated-only.
     */
    @PostMapping("/lab-tests/uid/{uid}/hold")
    public LabTestDto hold(
            @PathVariable("uid") String labTestUid,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.hold(labTestUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Result / report edits (no status change)
    // PUT /api/v1/clinical/lab-tests/uid/{uid}/result
    // PUT /api/v1/clinical/lab-tests/uid/{uid}/report
    // =========================================================================

    /**
     * Save result text without status change. Status must be COLLECTED.
     * Authenticated-only.
     */
    @PutMapping("/lab-tests/uid/{uid}/result")
    public LabTestDto saveResult(
            @PathVariable("uid") String labTestUid,
            @RequestBody LabTestResultRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.saveResult(labTestUid, request, ctxFrom(jwt));
    }

    /**
     * Add/update report text without status change. Status must be COLLECTED.
     * Authenticated-only.
     */
    @PutMapping("/lab-tests/uid/{uid}/report")
    public LabTestDto addReport(
            @PathVariable("uid") String labTestUid,
            @RequestBody LabTestReportRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.addReport(labTestUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Delete
    // DELETE /api/v1/clinical/lab-tests/uid/{uid}
    // =========================================================================

    /**
     * Hard-delete a PENDING lab test order. Only allowed when status == PENDING (else 422).
     * Credit-note for already-PAID bills is DEFERRED (see LabTestService javadoc).
     * Authenticated-only.
     */
    @DeleteMapping("/lab-tests/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("uid") String labTestUid,
            @AuthenticationPrincipal Jwt jwt) {
        labTestService.delete(labTestUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Attachments
    // POST   /api/v1/clinical/lab-tests/uid/{uid}/attachments
    // GET    /api/v1/clinical/lab-tests/uid/{uid}/attachments
    // DELETE /api/v1/clinical/lab-tests/attachments/uid/{attachmentUid}
    // =========================================================================

    /**
     * Add an attachment to a lab test.
     * Guards: status must be COLLECTED (422); max 5 attachments (422).
     * Authenticated-only.
     */
    @PostMapping("/lab-tests/uid/{uid}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public LabTestAttachmentDto addAttachment(
            @PathVariable("uid") String labTestUid,
            @Valid @RequestBody LabTestAttachmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.addAttachment(labTestUid, request, ctxFrom(jwt));
    }

    /**
     * List attachments for a lab test.
     * Note: download/view is gated on VERIFIED (PatientResource.java:6021) — documented here
     * but the endpoint returns the list regardless; the client enforces the display gate.
     * Authenticated-only.
     */
    @GetMapping("/lab-tests/uid/{uid}/attachments")
    public List<LabTestAttachmentDto> listAttachments(
            @PathVariable("uid") String labTestUid,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.listAttachments(labTestUid);
    }

    /**
     * Delete an attachment.
     * Guard: blocked when parent status == VERIFIED (422 "Cannot delete attachment from a
     * verified lab test"). Authenticated-only.
     */
    @DeleteMapping("/lab-tests/attachments/uid/{attachmentUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(
            @PathVariable("attachmentUid") String attachmentUid,
            @AuthenticationPrincipal Jwt jwt) {
        labTestService.deleteAttachment(attachmentUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Worklist
    // GET /api/v1/clinical/lab-tests/worklist
    // =========================================================================

    /**
     * Lab department worklist — settled orders in actionable statuses {PENDING, ACCEPTED, COLLECTED}.
     *
     * <p>The settled filter (local flag) replaces reading billing bill status (CR-INC05-01).
     * Optional {@code status} query param to filter to a single status.
     * Authenticated-only.
     *
     * @param status optional status filter (PENDING / ACCEPTED / COLLECTED)
     */
    @GetMapping("/lab-tests/worklist")
    public List<LabTestDto> worklist(
            @RequestParam(name = "status", required = false) LabTestStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.worklist(status);
    }

    // =========================================================================
    // By-patient query
    // GET /api/v1/clinical/lab-tests?patientUid=...&status=...
    // =========================================================================

    /**
     * All lab tests for a patient, optionally filtered by status.
     *
     * <p>Returns an empty list if the patient has no lab test orders.
     * Ordered newest first.
     * Authenticated-only.
     *
     * @param patientUid   MANDATORY patient ULID
     * @param status       optional status filter
     */
    @GetMapping("/lab-tests")
    public List<LabTestDto> byPatient(
            @RequestParam("patientUid") String patientUid,
            @RequestParam(name = "status", required = false) LabTestStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        return labTestService.byPatient(patientUid, status);
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
