package com.otapp.hmis.clinical.web;

import com.otapp.hmis.clinical.application.ClinicalDocumentationPort;
import com.otapp.hmis.clinical.application.ClinicalDocumentationPort.LoadResult;
import com.otapp.hmis.clinical.application.dto.ClinicalNoteDto;
import com.otapp.hmis.clinical.application.dto.GeneralExaminationDto;
import com.otapp.hmis.clinical.application.dto.GeneralExaminationRequest;
import com.otapp.hmis.clinical.application.dto.PatientVitalDto;
import com.otapp.hmis.clinical.application.dto.SoapNoteRequest;
import com.otapp.hmis.clinical.application.dto.VitalsRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for clinical documentation: SOAP notes, general examinations,
 * and nurse vitals staging (inc-05 C5, ADR-0022).
 *
 * <p>All endpoints are <strong>authenticated-only</strong> (no {@code @PreAuthorize})
 * per CR-INC05-02 parity — clinical documentation endpoints carry no additional legacy
 * RBAC gates beyond authentication (11-DECISIONS-RATIFIED.md §2).
 *
 * <p>No {@code @Transactional} on the controller — the service owns the boundary
 * (ADR-0014 §5).
 *
 * <p>Side-effecting GET endpoints (CR-INC05-06 — REPRODUCE faithfully):
 * <ul>
 *   <li>{@code GET /consultations/uid/{uid}/clinical-note} — auto-creates an empty note
 *       on first call; returns HTTP 201 if created, 200 if pre-existing.</li>
 *   <li>{@code GET /consultations/uid/{uid}/general-examination} — same auto-create.</li>
 *   <li>{@code GET /non-consultations/uid/{uid}/general-examination} — same auto-create.</li>
 *   <li>{@code GET /consultations/uid/{uid}/vitals} — auto-creates EMPTY PatientVital.</li>
 * </ul>
 *
 * <p><strong>DEFERRED — non-consultation clinical-note endpoints:</strong>
 * The legacy has NO non-consultation clinical-note loader
 * ({@code load_clinical_note_by_non_consultation_id} is absent from PatientResource.java).
 * No {@code /non-consultations/uid/{uid}/clinical-note} endpoint is exposed (faithful
 * omission). If symmetry is required in future, this requires a deliberate change request.
 *
 * <p><strong>DEFERRED — admission paths:</strong>
 * The admission encounter type is not built in C5. The {@code admissionUid} column exists
 * in the schema and entities but no admission-scoped endpoints are exposed here.
 *
 * <p>Base path: {@code /api/v1/clinical}
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>saveCG: PatientResource.java:1469-1598</li>
 *   <li>load_clinical_note_by_consultation_id: PatientResource.java</li>
 *   <li>load_general_examination_by_consultation_id: PatientResource.java</li>
 *   <li>load_general_examination_by_non_consultation_id: PatientResource.java</li>
 *   <li>load_patient_vitals_by_consultation_id: PatientResource.java:1298-1307</li>
 *   <li>request_patient_vitals_by_consultation_id: PatientResource.java:1340</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class ClinicalDocumentationController {

    private final ClinicalDocumentationPort documentationService;
    private final BusinessDayService         businessDayService;

    // =========================================================================
    // ClinicalNote — consultation path only (no non-consultation note endpoint — DEFERRED)
    // =========================================================================

    /**
     * Upsert the SOAP clinical note for a consultation.
     *
     * <p>If a note already exists for the consultation, its fields are OVERWRITTEN.
     * If none exists, a new note is created.
     * Returns HTTP 200 (upsert — consistent with the legacy saveCG).
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid     the consultation ULID
     * @param request the SOAP field values (all nullable)
     * @param jwt     the authenticated principal
     * @return the saved ClinicalNoteDto
     */
    @PostMapping("/consultations/uid/{uid}/clinical-note")
    public ClinicalNoteDto saveClinicalNote(
            @PathVariable("uid") String uid,
            @RequestBody SoapNoteRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return documentationService.saveClinicalNote(uid, request, ctxFrom(jwt));
    }

    /**
     * Load-or-create the SOAP clinical note for a consultation (side-effecting GET, CR-INC05-06).
     *
     * <p>HTTP 201 if the note was just created (first call); HTTP 200 if it pre-existed.
     * The legacy UI relies on the auto-creation of a blank note row on GET.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the (possibly newly-created) ClinicalNoteDto
     */
    @GetMapping("/consultations/uid/{uid}/clinical-note")
    public ResponseEntity<ClinicalNoteDto> loadOrCreateClinicalNote(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        LoadResult<ClinicalNoteDto> result =
                documentationService.loadOrCreateClinicalNote(uid, ctxFrom(jwt));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.dto());
    }

    // =========================================================================
    // GeneralExamination — consultation path
    // =========================================================================

    /**
     * Upsert the general examination for a consultation.
     *
     * <p>All vital fields are free-text String; no numeric validation or BMI/BSA computation
     * is performed server-side (CR-INC05-13).
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid     the consultation ULID
     * @param request the vital-sign field values (all nullable)
     * @param jwt     the authenticated principal
     * @return the saved GeneralExaminationDto
     */
    @PostMapping("/consultations/uid/{uid}/general-examination")
    public GeneralExaminationDto saveGeneralExamination(
            @PathVariable("uid") String uid,
            @RequestBody GeneralExaminationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return documentationService.saveGeneralExamination(uid, request, ctxFrom(jwt));
    }

    /**
     * Load-or-create the general examination for a consultation (side-effecting GET, CR-INC05-06).
     *
     * <p>HTTP 201 if just created; HTTP 200 if pre-existing.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the (possibly newly-created) GeneralExaminationDto
     */
    @GetMapping("/consultations/uid/{uid}/general-examination")
    public ResponseEntity<GeneralExaminationDto> loadOrCreateGeneralExaminationByConsultation(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        LoadResult<GeneralExaminationDto> result =
                documentationService.loadOrCreateGeneralExaminationByConsultation(uid, ctxFrom(jwt));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.dto());
    }

    // =========================================================================
    // GeneralExamination — non-consultation path (walk-in)
    // =========================================================================

    /**
     * Upsert the general examination for a non-consultation (walk-in encounter).
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid     the non-consultation ULID
     * @param request the vital-sign field values (all nullable)
     * @param jwt     the authenticated principal
     * @return the saved GeneralExaminationDto
     */
    @PostMapping("/non-consultations/uid/{uid}/general-examination")
    public GeneralExaminationDto saveGeneralExaminationForNonConsultation(
            @PathVariable("uid") String uid,
            @RequestBody GeneralExaminationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return documentationService.saveGeneralExaminationForNonConsultation(uid, request,
                ctxFrom(jwt));
    }

    /**
     * Load-or-create the general examination for a non-consultation (side-effecting GET,
     * CR-INC05-06).
     *
     * <p>HTTP 201 if just created; HTTP 200 if pre-existing.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid the non-consultation ULID
     * @param jwt the authenticated principal
     * @return the (possibly newly-created) GeneralExaminationDto
     */
    @GetMapping("/non-consultations/uid/{uid}/general-examination")
    public ResponseEntity<GeneralExaminationDto> loadOrCreateGeneralExaminationByNonConsultation(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        LoadResult<GeneralExaminationDto> result =
                documentationService.loadOrCreateGeneralExaminationByNonConsultation(uid,
                        ctxFrom(jwt));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.dto());
    }

    // =========================================================================
    // PatientVital staging flow
    // =========================================================================

    /**
     * Load-or-create the PatientVital for a consultation (side-effecting GET, CR-INC05-06).
     *
     * <p>HTTP 201 if an EMPTY row was just created; HTTP 200 if it pre-existed.
     * Reproduces PatientResource.java:1298-1307 faithfully.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the (possibly newly-created) PatientVitalDto with status=EMPTY
     */
    @GetMapping("/consultations/uid/{uid}/vitals")
    public ResponseEntity<PatientVitalDto> loadOrCreateVital(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        LoadResult<PatientVitalDto> result =
                documentationService.loadOrCreateVital(uid, ctxFrom(jwt));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.dto());
    }

    /**
     * Nurse submits vital-sign readings for a consultation: EMPTY/SUBMITTED → SUBMITTED.
     *
     * <p>The nurse fills in the vital fields and POSTs them. The PatientVital row for the
     * consultation is updated with the submitted values and its status transitions to SUBMITTED.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid     the consultation ULID
     * @param request the vital-sign readings from the nurse (all fields nullable)
     * @param jwt     the authenticated principal
     * @return the updated PatientVitalDto with status=SUBMITTED
     */
    @PostMapping("/consultations/uid/{uid}/vitals")
    public PatientVitalDto submitVital(
            @PathVariable("uid") String uid,
            @RequestBody VitalsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return documentationService.submitVital(uid, request, ctxFrom(jwt));
    }

    /**
     * Doctor requests the submitted vitals for a consultation.
     *
     * <p>Copies vital fields from the SUBMITTED PatientVital into the consultation's
     * GeneralExamination (upsert); sets the PatientVital to ARCHIVED.
     *
     * <p>Guard: PatientVital must be SUBMITTED; else 422 with verbatim legacy message
     * {@code "Vitals already requested or not submitted"}.
     *
     * <p>Authenticated-only (CR-INC05-02).
     *
     * @param uid the consultation ULID
     * @param jwt the authenticated principal
     * @return the upserted GeneralExaminationDto with copied vital fields
     */
    @PostMapping("/consultations/uid/{uid}/vitals/request")
    public GeneralExaminationDto requestVital(
            @PathVariable("uid") String uid,
            @AuthenticationPrincipal Jwt jwt) {
        return documentationService.requestVital(uid, ctxFrom(jwt));
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
