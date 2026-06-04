package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.DiagnosisRequest;
import com.otapp.hmis.clinical.application.dto.FinalDiagnosisDto;
import com.otapp.hmis.clinical.application.dto.WorkingDiagnosisDto;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Public intra-module boundary between {@code clinical.web} and {@code clinical.application}
 * for working and final diagnosis operations (inc-05 C6).
 *
 * <p>The web layer ({@link com.otapp.hmis.clinical.web.DiagnosisController}) cannot reference
 * the package-private {@link DiagnosisService} directly. This interface is the only public
 * type in {@code clinical.application} that the controller may depend on for diagnosis
 * operations (mirrors the {@link ClinicalDocumentationPort} / {@link ConsultationLifecyclePort}
 * pattern).
 *
 * <p>Spring wires the package-private impl via component scanning.
 *
 * <p>Request records live in {@code clinical.application.dto} (not in {@code clinical.web})
 * so the port can reference them without a circular import.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>saveWorkingDiagnosis: PatientResource.java:1654-1678</li>
 *   <li>saveFinalDiagnosis: PatientResource.java:1782</li>
 *   <li>delete_working_diagnosis: PatientResource.java:1917-1929</li>
 *   <li>delete_final_diagnosis: PatientResource.java:1917-1929</li>
 *   <li>load_consultation_working_diagnosis: PatientResource.java (list)</li>
 *   <li>load_consultation_final_diagnosis: PatientResource.java (list)</li>
 * </ul>
 */
public interface DiagnosisPort {

    // -------------------------------------------------------------------------
    // Working diagnosis
    // -------------------------------------------------------------------------

    /**
     * Add a working diagnosis to a consultation (APPEND, consultation path).
     *
     * <p>Guards (PatientResource.java:1654-1678):
     * <ol>
     *   <li>Consultation must exist → 404 {@code "Consultation not found"}.</li>
     *   <li>DiagnosisType uid must exist in the catalog → 404 {@code "Diagnosis type not found"}.</li>
     *   <li>No existing WorkingDiagnosis for (consultation, diagnosisTypeUid) →
     *       422 {@code "Duplicate Diagnosis Types is not allowed"}.</li>
     * </ol>
     *
     * <p>On success: persists a new {@link com.otapp.hmis.clinical.domain.WorkingDiagnosis}
     * row with {@code patientUid} copied from the consultation.
     *
     * @param consultationUid  the ULID of the owning consultation
     * @param request          the diagnosisTypeUid + optional description
     * @param ctx              transaction audit context
     * @return the created WorkingDiagnosisDto
     */
    WorkingDiagnosisDto addWorkingDiagnosis(String consultationUid,
                                             DiagnosisRequest request,
                                             TxAuditContext ctx);

    /**
     * List all working diagnoses for a consultation, ordered by creation time ascending.
     *
     * @param consultationUid the ULID of the consultation
     * @return the list (may be empty); 404 if the consultation does not exist
     */
    List<WorkingDiagnosisDto> listWorkingDiagnoses(String consultationUid, TxAuditContext ctx);

    /**
     * Hard-delete a working diagnosis by ULID.
     *
     * <p>Legacy: {@code deleteById} with no existence check. This implementation adds a
     * clean 404 if the uid is not found (defensive improvement — documented, not a
     * business-rule change).
     *
     * <p>NO soft-delete, NO audit-flag, NO update path (editing = delete + re-add).
     *
     * @param diagnosisUid the ULID of the working diagnosis to delete
     * @param ctx          transaction audit context
     */
    void deleteWorkingDiagnosis(String diagnosisUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Final diagnosis
    // -------------------------------------------------------------------------

    /**
     * Add a final diagnosis to a consultation (APPEND, consultation path).
     *
     * <p>Guards mirror the working-diagnosis path:
     * <ol>
     *   <li>Consultation must exist → 404 {@code "Consultation not found"}.</li>
     *   <li>DiagnosisType uid must exist in the catalog → 404 {@code "Diagnosis type not found"}.</li>
     *   <li>No existing FinalDiagnosis for (consultation, diagnosisTypeUid) →
     *       422 {@code "Duplicate Diagnosis Types is not allowed"}.</li>
     * </ol>
     *
     * <p>Note: a diagnosisTypeUid already used in a WorkingDiagnosis is ALLOWED as a
     * FinalDiagnosis — the dup-guard is per-table (separate entities, separate tables).
     *
     * @param consultationUid  the ULID of the owning consultation
     * @param request          the diagnosisTypeUid + optional description
     * @param ctx              transaction audit context
     * @return the created FinalDiagnosisDto
     */
    FinalDiagnosisDto addFinalDiagnosis(String consultationUid,
                                         DiagnosisRequest request,
                                         TxAuditContext ctx);

    /**
     * List all final diagnoses for a consultation, ordered by creation time ascending.
     *
     * @param consultationUid the ULID of the consultation
     * @return the list (may be empty); 404 if the consultation does not exist
     */
    List<FinalDiagnosisDto> listFinalDiagnoses(String consultationUid, TxAuditContext ctx);

    /**
     * Hard-delete a final diagnosis by ULID.
     *
     * <p>Legacy: {@code deleteById} with no existence check. This implementation adds a
     * clean 404 if the uid is not found (defensive improvement — documented, not a
     * business-rule change).
     *
     * <p>NO soft-delete, NO audit-flag, NO update path (editing = delete + re-add).
     *
     * @param diagnosisUid the ULID of the final diagnosis to delete
     * @param ctx          transaction audit context
     */
    void deleteFinalDiagnosis(String diagnosisUid, TxAuditContext ctx);
}
