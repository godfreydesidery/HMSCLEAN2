package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.application.PrescriptionPort;
import com.otapp.hmis.clinical.application.dto.IssueRequest;
import com.otapp.hmis.clinical.application.dto.PrescriptionBatchDto;
import com.otapp.hmis.clinical.application.dto.PrescriptionBatchRequest;
import com.otapp.hmis.clinical.application.dto.PrescriptionDto;
import com.otapp.hmis.clinical.application.dto.PrescriptionRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the Prescription aggregate (inc-05 C10).
 *
 * <p>All endpoints are <strong>authenticated-only</strong> (no {@code @PreAuthorize})
 * per CR-INC05-02 parity — prescription endpoints carry no additional legacy RBAC gates
 * beyond authentication (11-DECISIONS-RATIFIED.md §2).
 *
 * <p>No {@code @Transactional} on the controller — the service owns the transaction
 * boundary (ADR-0014 §5).
 *
 * <p><strong>Base paths:</strong>
 * <ul>
 *   <li>{@code /api/v1/clinical/consultations}     — consultation-scoped prescribe</li>
 *   <li>{@code /api/v1/clinical/non-consultations} — non-consultation-scoped prescribe</li>
 *   <li>{@code /api/v1/clinical/prescriptions}     — lifecycle + worklist + by-patient + batches</li>
 * </ul>
 *
 * <p><strong>Endpoint surface (13 endpoints):</strong>
 * <ul>
 *   <li>{@code POST   /consultations/uid/{uid}/prescriptions}              → 201 (prescribe on consultation)</li>
 *   <li>{@code GET    /consultations/uid/{uid}/prescriptions}              → 200 (list by consultation)</li>
 *   <li>{@code POST   /non-consultations/uid/{uid}/prescriptions}          → 201 (prescribe on walk-in)</li>
 *   <li>{@code GET    /prescriptions/uid/{uid}}                            → 200 (get by uid)</li>
 *   <li>{@code POST   /prescriptions/uid/{uid}/issue}                      → 200 (dispense NOT-GIVEN→GIVEN)</li>
 *   <li>{@code DELETE /prescriptions/uid/{uid}}                            → 204 (delete NOT-GIVEN)</li>
 *   <li>{@code GET    /prescriptions/worklist}                             → 200 (pharmacy worklist)</li>
 *   <li>{@code GET    /prescriptions}                                      → 200 (by-patient)</li>
 *   <li>{@code POST   /prescriptions/uid/{uid}/batches}                    → 201 (add batch)</li>
 *   <li>{@code GET    /prescriptions/uid/{uid}/batches}                    → 200 (list batches)</li>
 * </ul>
 *
 * <p><strong>DEFERRED:</strong>
 * patient_prescription_charts write path — admissions module not yet built.
 * (PatientServiceImpl.java:2564-2577; chart requires admission IN-PROCESS + nurse uid.)
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>save_prescription: PatientServiceImpl.java (save_prescription)</li>
 *   <li>issueMedicine: PatientResource.java:3217-3245</li>
 *   <li>Pharmacy worklist: PatientResource.java (dispense queue)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionPort prescriptionService;
    private final BusinessDayService businessDayService;

    // =========================================================================
    // Prescribe — consultation path
    // POST /api/v1/clinical/consultations/uid/{uid}/prescriptions
    // =========================================================================

    /**
     * Prescribe a medicine on an existing consultation (outpatient path).
     *
     * <p>Guards: consultation exists (404); medicine exists (404 "Medicine not found");
     * no duplicate medicine for this consultation (422 "Duplicate medicine is not allowed
     * for this encounter"). Creates a PatientBill via billing charge (MEDICINE kind, qty=prescribed qty).
     * Returns NOT-GIVEN prescription with balance=qty, issued=0, alerts=[].
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/consultations/uid/{uid}/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public PrescriptionDto prescribeForConsultation(
            @PathVariable("uid") String consultationUid,
            @Valid @RequestBody PrescriptionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.prescribeForConsultation(consultationUid, request,
                ctxFrom(jwt));
    }

    /**
     * List all prescriptions for a consultation, ordered by creation time ascending.
     *
     * <p>Returns an empty list if the consultation has no prescriptions.
     * Returns 404 if the consultation uid is unknown.
     * Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/consultations/uid/{uid}/prescriptions")
    public List<PrescriptionDto> listByConsultation(
            @PathVariable("uid") String consultationUid,
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.listByConsultation(consultationUid);
    }

    // =========================================================================
    // Prescribe — non-consultation (walk-in/OUTSIDER) path
    // POST /api/v1/clinical/non-consultations/uid/{uid}/prescriptions
    // =========================================================================

    /**
     * Prescribe a medicine on an existing non-consultation (OUTSIDER/walk-in path).
     *
     * <p>Guards: non-consultation exists (404); medicine exists (404 "Medicine not found");
     * no duplicate medicine for this non-consultation (422) — CR-INC05-05 corrected check
     * (NOT the legacy NPE path). Creates a PatientBill via billing charge.
     * Returns NOT-GIVEN prescription.
     *
     * <p>Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/non-consultations/uid/{uid}/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public PrescriptionDto prescribeForNonConsultation(
            @PathVariable("uid") String nonConsultationUid,
            @Valid @RequestBody PrescriptionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.prescribeForNonConsultation(nonConsultationUid, request,
                ctxFrom(jwt));
    }

    // =========================================================================
    // Get by uid
    // GET /api/v1/clinical/prescriptions/uid/{uid}
    // =========================================================================

    /**
     * Get a prescription by ULID.
     *
     * <p>Returns 404 if the uid is unknown. No id in response (ADR-0014 §1).
     * Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/prescriptions/uid/{uid}")
    public PrescriptionDto getByUid(
            @PathVariable("uid") String prescriptionUid,
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.getByUid(prescriptionUid);
    }

    // =========================================================================
    // Dispense (issueMedicine) — NOT-GIVEN → GIVEN
    // POST /api/v1/clinical/prescriptions/uid/{uid}/issue
    // =========================================================================

    /**
     * Dispense a prescription: NOT-GIVEN → GIVEN.
     *
     * <p>Guards (PatientResource.java:3217-3245):
     * <ul>
     *   <li>Status must be NOT-GIVEN (else 422 "not a pending prescription").</li>
     *   <li>issued must be > 0 (422 "Invalid issue value").</li>
     *   <li>issued must not exceed balance (422 "Invalid issue value").</li>
     *   <li>issued must equal the full prescribed qty (422 "You can only issue the prescribed qty").</li>
     * </ul>
     * STOCK check DEFERRED (pharmacy module).
     * On success: issued=qty, balance=0, issuePharmacyUid set, status GIVEN, approved_* stamped.
     * Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/prescriptions/uid/{uid}/issue")
    public PrescriptionDto issueMedicine(
            @PathVariable("uid") String prescriptionUid,
            @Valid @RequestBody IssueRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.issueMedicine(prescriptionUid, request, ctxFrom(jwt));
    }

    // =========================================================================
    // Delete
    // DELETE /api/v1/clinical/prescriptions/uid/{uid}
    // =========================================================================

    /**
     * Hard-delete a NOT-GIVEN prescription. Only allowed when status == NOT-GIVEN (else 422).
     * Credit-note for already-PAID bills is DEFERRED (see PrescriptionService javadoc).
     * Authenticated-only (CR-INC05-02).
     */
    @DeleteMapping("/prescriptions/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("uid") String prescriptionUid,
            @AuthenticationPrincipal Jwt jwt) {
        prescriptionService.delete(prescriptionUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Pharmacy worklist
    // GET /api/v1/clinical/prescriptions/worklist
    // =========================================================================

    /**
     * Pharmacy dispense worklist — all NOT-GIVEN prescriptions, FIFO order.
     *
     * <p>Returns all NOT-GIVEN prescriptions (pharmacist validates payment before
     * dispensing physically). Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/prescriptions/worklist")
    public List<PrescriptionDto> pharmacyWorklist(
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.pharmacyWorklist();
    }

    // =========================================================================
    // By-patient query
    // GET /api/v1/clinical/prescriptions?patientUid=...
    // =========================================================================

    /**
     * All prescriptions for a patient, ordered newest first.
     *
     * <p>Returns an empty list if the patient has no prescriptions.
     * Authenticated-only (CR-INC05-02).
     *
     * @param patientUid MANDATORY patient ULID
     */
    @GetMapping("/prescriptions")
    public List<PrescriptionDto> byPatient(
            @RequestParam("patientUid") String patientUid,
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.byPatient(patientUid);
    }

    // =========================================================================
    // Prescription batches
    // POST /api/v1/clinical/prescriptions/uid/{uid}/batches
    // GET  /api/v1/clinical/prescriptions/uid/{uid}/batches
    // =========================================================================

    /**
     * Add a batch traceability record to a prescription.
     * Authenticated-only (CR-INC05-02).
     */
    @PostMapping("/prescriptions/uid/{uid}/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public PrescriptionBatchDto addBatch(
            @PathVariable("uid") String prescriptionUid,
            @Valid @RequestBody PrescriptionBatchRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.addBatch(prescriptionUid, request, ctxFrom(jwt));
    }

    /**
     * List all batch records for a prescription, ordered by creation time ascending.
     * Authenticated-only (CR-INC05-02).
     */
    @GetMapping("/prescriptions/uid/{uid}/batches")
    public List<PrescriptionBatchDto> listBatches(
            @PathVariable("uid") String prescriptionUid,
            @AuthenticationPrincipal Jwt jwt) {
        return prescriptionService.listBatches(prescriptionUid);
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
