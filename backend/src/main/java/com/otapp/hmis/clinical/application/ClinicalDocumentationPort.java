package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.ClinicalNoteDto;
import com.otapp.hmis.clinical.application.dto.GeneralExaminationDto;
import com.otapp.hmis.clinical.application.dto.GeneralExaminationRequest;
import com.otapp.hmis.clinical.application.dto.PatientVitalDto;
import com.otapp.hmis.clinical.application.dto.SoapNoteRequest;
import com.otapp.hmis.clinical.application.dto.VitalsRequest;
import com.otapp.hmis.shared.domain.TxAuditContext;

/**
 * Public intra-module boundary between {@code clinical.web} and {@code clinical.application}
 * for the clinical documentation operations (inc-05 C5: SOAP notes, exams, vitals).
 *
 * <p>The web layer ({@link com.otapp.hmis.clinical.web.ClinicalDocumentationController})
 * cannot reference the package-private {@link ClinicalDocumentationService} directly.
 * This interface is the only public type in {@code clinical.application} that the controller
 * may depend on for documentation operations. The implementation is package-private
 * (mirroring the {@link ConsultationLifecyclePort} / {@link WalkInPort} pattern).
 *
 * <p>Spring wires the package-private impl via component scanning.
 *
 * <p>Request records live in {@code clinical.application.dto} (not in {@code clinical.web})
 * so the port can reference them without a circular import.
 *
 * <p>All load-or-create methods return a {@link LoadResult} so the controller can
 * return HTTP 201 on first creation and HTTP 200 on subsequent reads (CR-INC05-06).
 */
public interface ClinicalDocumentationPort {

    // -------------------------------------------------------------------------
    // LoadResult wrapper
    // -------------------------------------------------------------------------

    /**
     * Thin wrapper returned by all load-or-create methods so the controller can distinguish
     * between an existing row (HTTP 200) and a newly-created one (HTTP 201), faithfully
     * reproducing the CR-INC05-06 side-effecting GET behaviour.
     *
     * @param <T>     the DTO type
     * @param dto     the DTO (never null)
     * @param created {@code true} if the row was just created; {@code false} if it pre-existed
     */
    record LoadResult<T>(T dto, boolean created) {
    }

    // -------------------------------------------------------------------------
    // ClinicalNote
    // -------------------------------------------------------------------------

    /**
     * UPSERT a SOAP clinical note for a consultation.
     *
     * <p>If a note already exists for the consultation, its fields are OVERWRITTEN in place
     * (one row per consultation — V23 partial UNIQUE, CR-INC05-07). If none exists, a new
     * row is created (PatientResource.java:1469-1598 saveCG pattern).
     *
     * @param consultationUid the ULID of the consultation
     * @param request         the SOAP field values (all nullable — no bean validation on SOAP)
     * @param ctx             transaction audit context
     * @return the saved (upserted) ClinicalNoteDto
     */
    ClinicalNoteDto saveClinicalNote(String consultationUid,
                                     SoapNoteRequest request,
                                     TxAuditContext ctx);

    /**
     * Load-or-create the SOAP note for a consultation (side-effecting GET, CR-INC05-06).
     *
     * <p>If a note already exists for the consultation, return it ({@code created=false}).
     * If none exists, AUTO-CREATE an empty persisted note and return it ({@code created=true}).
     * This reproduces the legacy {@code load_clinical_note_by_consultation_id} behaviour
     * faithfully: the first GET creates a blank note row (blank-note poll is preserved
     * because the legacy UI relies on it — 11-DECISIONS-RATIFIED §1 CR-INC05-06).
     *
     * <p>There is intentionally NO non-consultation note loader — the legacy has none
     * (faithful omission confirmed by PatientResource.java survey).
     *
     * @param consultationUid the ULID of the consultation
     * @param ctx             transaction audit context
     * @return LoadResult wrapping the DTO and a created flag
     */
    LoadResult<ClinicalNoteDto> loadOrCreateClinicalNote(String consultationUid,
                                                          TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // GeneralExamination
    // -------------------------------------------------------------------------

    /**
     * UPSERT a general examination for a consultation (saveCG pattern).
     *
     * @param consultationUid the ULID of the consultation
     * @param request         the vital-sign field values (all nullable — CR-INC05-13)
     * @param ctx             transaction audit context
     * @return the saved (upserted) GeneralExaminationDto
     */
    GeneralExaminationDto saveGeneralExamination(String consultationUid,
                                                  GeneralExaminationRequest request,
                                                  TxAuditContext ctx);

    /**
     * Load-or-create the general examination for a consultation (side-effecting GET, CR-INC05-06).
     *
     * <p>Auto-creates an empty persisted row if none exists ({@code created=true}).
     *
     * @param consultationUid the ULID of the consultation
     * @param ctx             transaction audit context
     * @return LoadResult wrapping the DTO and a created flag
     */
    LoadResult<GeneralExaminationDto> loadOrCreateGeneralExaminationByConsultation(
            String consultationUid, TxAuditContext ctx);

    /**
     * UPSERT a general examination for a non-consultation (walk-in, saveCG pattern).
     *
     * @param nonConsultationUid the ULID of the non-consultation
     * @param request            the vital-sign field values (all nullable — CR-INC05-13)
     * @param ctx                transaction audit context
     * @return the saved (upserted) GeneralExaminationDto
     */
    GeneralExaminationDto saveGeneralExaminationForNonConsultation(String nonConsultationUid,
                                                                    GeneralExaminationRequest request,
                                                                    TxAuditContext ctx);

    /**
     * Load-or-create the general examination for a non-consultation (side-effecting GET,
     * CR-INC05-06 — the legacy has a non-consultation exam loader in addition to the
     * consultation loader; confirmed by PatientResource.java survey).
     *
     * <p>Auto-creates an empty persisted row if none exists ({@code created=true}).
     *
     * @param nonConsultationUid the ULID of the non-consultation
     * @param ctx                transaction audit context
     * @return LoadResult wrapping the DTO and a created flag
     */
    LoadResult<GeneralExaminationDto> loadOrCreateGeneralExaminationByNonConsultation(
            String nonConsultationUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // PatientVital staging flow
    // -------------------------------------------------------------------------

    /**
     * Load-or-create the PatientVital for a consultation (side-effecting GET, CR-INC05-06).
     *
     * <p>If a PatientVital already exists for the consultation, return it ({@code created=false}).
     * If none exists, AUTO-CREATE an empty row with status=EMPTY and return it
     * ({@code created=true}) — PatientResource.java:1298-1307.
     *
     * @param consultationUid the ULID of the consultation
     * @param ctx             transaction audit context
     * @return LoadResult wrapping the DTO and a created flag
     */
    LoadResult<PatientVitalDto> loadOrCreateVital(String consultationUid, TxAuditContext ctx);

    /**
     * Nurse submits vital-sign readings for a consultation: status → SUBMITTED.
     *
     * <p>If a PatientVital already exists for the consultation (EMPTY or SUBMITTED), the vital
     * fields are overwritten and status is set to SUBMITTED. If none exists, a new row is
     * created SUBMITTED (resilience — the normal flow goes through loadOrCreate first).
     *
     * @param consultationUid the ULID of the consultation
     * @param request         the vital-sign field values from the nurse
     * @param ctx             transaction audit context
     * @return the updated PatientVitalDto with status=SUBMITTED
     */
    PatientVitalDto submitVital(String consultationUid, VitalsRequest request, TxAuditContext ctx);

    /**
     * Doctor requests the submitted vitals for a consultation.
     *
     * <p>Effect:
     * <ol>
     *   <li>All vital fields from the SUBMITTED {@link com.otapp.hmis.clinical.domain.PatientVital}
     *       are copied into the consultation's {@link com.otapp.hmis.clinical.domain.GeneralExamination}
     *       via the saveCG upsert (one GeneralExamination per consultation — V23 partial UNIQUE).</li>
     *   <li>The PatientVital row is set to ARCHIVED.</li>
     * </ol>
     *
     * <p>Guard: PatientVital for this consultation must exist with status=SUBMITTED; else 422
     * with verbatim legacy message
     * {@code "Vitals already requested or not submitted"} (PatientResource.java:1340).
     *
     * <p><strong>Defect fix (not reproducing a legacy bug):</strong> The legacy dereferences
     * {@code findById(id).get()} with no presence check — a missing consultation causes a raw
     * HTTP 500. This implementation throws a clean
     * {@link com.otapp.hmis.shared.error.NotFoundException} (404) instead. This is a defect fix,
     * not a business rule change.
     *
     * @param consultationUid the ULID of the consultation
     * @param ctx             transaction audit context
     * @return the upserted GeneralExaminationDto with the copied vital fields
     */
    GeneralExaminationDto requestVital(String consultationUid, TxAuditContext ctx);
}
