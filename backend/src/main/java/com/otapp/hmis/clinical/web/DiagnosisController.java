package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.application.DiagnosisPort;
import com.otapp.hmis.clinical.application.dto.DiagnosisRequest;
import com.otapp.hmis.clinical.application.dto.FinalDiagnosisDto;
import com.otapp.hmis.clinical.application.dto.WorkingDiagnosisDto;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for working and final diagnoses under a consultation (inc-05 C6).
 *
 * <p>All endpoints are <strong>authenticated-only</strong> (no {@code @PreAuthorize})
 * per CR-INC05-02 parity — diagnosis endpoints carry no additional legacy RBAC gates
 * beyond authentication (11-DECISIONS-RATIFIED.md §2).
 *
 * <p>No {@code @Transactional} on the controller — the service owns the transaction
 * boundary (ADR-0014 §5).
 *
 * <p><strong>DEFERRED — admission diagnosis paths:</strong>
 * The admission path (bound to {@code admissionUid}) is not implemented here. The
 * Inpatient/Nursing increment adds {@code POST /admissions/uid/{uid}/working-diagnoses}
 * and {@code final-diagnoses} endpoints when that module is built. The asymmetry
 * (no dup-guard on the admission path, CR-INC05-07) is preserved by the absence of a
 * DB unique constraint on (admission_uid, diagnosis_type_uid) — no schema change needed
 * when the deferred admission endpoints land.
 *
 * <p>Base path: {@code /api/v1/clinical/consultations}
 *
 * <p>Endpoint surface (6 endpoints):
 * <ul>
 *   <li>{@code POST   /consultations/uid/{uid}/working-diagnoses}     → 201</li>
 *   <li>{@code GET    /consultations/uid/{uid}/working-diagnoses}     → 200</li>
 *   <li>{@code DELETE /working-diagnoses/uid/{diagnosisUid}}         → 204</li>
 *   <li>{@code POST   /consultations/uid/{uid}/final-diagnoses}       → 201</li>
 *   <li>{@code GET    /consultations/uid/{uid}/final-diagnoses}       → 200</li>
 *   <li>{@code DELETE /final-diagnoses/uid/{diagnosisUid}}           → 204</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>saveWorkingDiagnosis: PatientResource.java:1654-1678</li>
 *   <li>saveFinalDiagnosis: PatientResource.java:1782</li>
 *   <li>delete_working_diagnosis: PatientResource.java:1917-1929</li>
 *   <li>delete_final_diagnosis: PatientResource.java:1917-1929</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class DiagnosisController {

    private final DiagnosisPort     diagnosisService;
    private final BusinessDayService businessDayService;

    // =========================================================================
    // Working diagnosis — consultation path
    // =========================================================================

    /**
     * Add a working (provisional) diagnosis to a consultation.
     *
     * <p>Guards: consultation must exist (404); diagnosisType uid must exist (404);
     * no duplicate type for this consultation (422 "Duplicate Diagnosis Types is not allowed").
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid     the consultation ULID
     * @param request the diagnosisTypeUid (mandatory) + optional description
     * @param jwt     the authenticated principal
     * @return the created WorkingDiagnosisDto (HTTP 201)
     */
    @PostMapping("/consultations/uid/{uid}/working-diagnoses")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkingDiagnosisDto addWorkingDiagnosis(
            @PathVariable("uid") String uid,
            @Valid @RequestBody DiagnosisRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return diagnosisService.addWorkingDiagnosis(uid, request, ctxFrom(jwt));
    }

    /**
     * List all working diagnoses for a consultation, ordered by creation time ascending.
     *
     * <p>Returns an empty list if the consultation has no diagnoses.
     * Returns 404 if the consultation uid is unknown.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the list of WorkingDiagnosisDto
     */
    @GetMapping("/consultations/uid/{uid}/working-diagnoses")
    public List<WorkingDiagnosisDto> listWorkingDiagnoses(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return diagnosisService.listWorkingDiagnoses(uid, ctxFrom(jwt));
    }

    /**
     * Hard-delete a working diagnosis by ULID.
     *
     * <p>Returns 204 on success. Returns 404 if the uid is not found (defensive
     * improvement over the legacy raw deleteById — documented in inc-05 C6 spec).
     * NO soft-delete, NO audit-flag. Editing = delete + re-add.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param diagnosisUid the ULID of the working diagnosis to delete
     * @param jwt          the authenticated principal
     */
    @DeleteMapping("/working-diagnoses/uid/{diagnosisUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkingDiagnosis(
            @PathVariable("diagnosisUid") String diagnosisUid,
            @AuthenticationPrincipal Jwt jwt) {
        diagnosisService.deleteWorkingDiagnosis(diagnosisUid, ctxFrom(jwt));
    }

    // =========================================================================
    // Final diagnosis — consultation path
    // =========================================================================

    /**
     * Add a final (confirmed) diagnosis to a consultation.
     *
     * <p>Guards: consultation must exist (404); diagnosisType uid must exist (404);
     * no duplicate type for this consultation in final_diagnoses (422 "Duplicate Diagnosis
     * Types is not allowed"). Note: the same diagnosisTypeUid already present in
     * working_diagnoses is still allowed here (separate tables, no cross-table constraint).
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid     the consultation ULID
     * @param request the diagnosisTypeUid (mandatory) + optional description
     * @param jwt     the authenticated principal
     * @return the created FinalDiagnosisDto (HTTP 201)
     */
    @PostMapping("/consultations/uid/{uid}/final-diagnoses")
    @ResponseStatus(HttpStatus.CREATED)
    public FinalDiagnosisDto addFinalDiagnosis(
            @PathVariable("uid") String uid,
            @Valid @RequestBody DiagnosisRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return diagnosisService.addFinalDiagnosis(uid, request, ctxFrom(jwt));
    }

    /**
     * List all final diagnoses for a consultation, ordered by creation time ascending.
     *
     * <p>Returns an empty list if the consultation has no final diagnoses.
     * Returns 404 if the consultation uid is unknown.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the list of FinalDiagnosisDto
     */
    @GetMapping("/consultations/uid/{uid}/final-diagnoses")
    public List<FinalDiagnosisDto> listFinalDiagnoses(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return diagnosisService.listFinalDiagnoses(uid, ctxFrom(jwt));
    }

    /**
     * Hard-delete a final diagnosis by ULID.
     *
     * <p>Returns 204 on success. Returns 404 if the uid is not found.
     * NO soft-delete, NO audit-flag. Editing = delete + re-add.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param diagnosisUid the ULID of the final diagnosis to delete
     * @param jwt          the authenticated principal
     */
    @DeleteMapping("/final-diagnoses/uid/{diagnosisUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFinalDiagnosis(
            @PathVariable("diagnosisUid") String diagnosisUid,
            @AuthenticationPrincipal Jwt jwt) {
        diagnosisService.deleteFinalDiagnosis(diagnosisUid, ctxFrom(jwt));
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
