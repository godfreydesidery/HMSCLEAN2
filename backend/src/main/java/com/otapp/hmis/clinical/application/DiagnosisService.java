package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.DiagnosisRequest;
import com.otapp.hmis.clinical.application.dto.FinalDiagnosisDto;
import com.otapp.hmis.clinical.application.dto.WorkingDiagnosisDto;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.FinalDiagnosis;
import com.otapp.hmis.clinical.domain.FinalDiagnosisRepository;
import com.otapp.hmis.clinical.domain.WorkingDiagnosis;
import com.otapp.hmis.clinical.domain.WorkingDiagnosisRepository;
import com.otapp.hmis.masterdata.lookup.DiagnosisTypeLookup;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementing working and final diagnosis operations for patient consultations
 * (inc-05 C6, PatientResource.java:1654-1678 / :1782 / :1917-1929).
 *
 * <p><strong>CONSULTATION PATH — duplicate guard (PatientResource.java:1662 / :1782):</strong>
 * If a {Working|Final}Diagnosis already exists for (consultation, diagnosisTypeUid), the
 * service throws {@link InvalidPatientOperationException} with the verbatim legacy message
 * {@code "Duplicate Diagnosis Types is not allowed"}.
 *
 * <p><strong>APPEND semantics:</strong>
 * Multiple distinct diagnosis types per consultation are allowed; only the SAME type is
 * blocked by the dup-guard. Diagnoses are appended — one row per successful save call.
 *
 * <p><strong>Patient uid (ADR-0022 D2):</strong>
 * {@code patientUid} is copied from {@code consultation.getPatientUid()} at save time.
 * The clinical module never queries the registration module to obtain the patient uid.
 *
 * <p><strong>DiagnosisType existence check:</strong>
 * The caller-supplied {@code diagnosisTypeUid} is verified via
 * {@link DiagnosisTypeLookup#existsByUid}. On negative result, a
 * {@link NotFoundException} with the verbatim legacy message
 * {@code "Diagnosis type not found"} is thrown (PatientResource.java:1659).
 *
 * <p><strong>DELETE — hard-delete with clean 404 (defensive improvement):</strong>
 * Legacy does {@code deleteById} without checking existence (PatientResource.java:1917-1929).
 * This implementation first finds by uid and throws 404 if not found — a documented
 * defensive improvement, not a business-rule change.
 *
 * <p><strong>DEFERRED — admission paths:</strong>
 * No admission-scoped endpoints are implemented. The asymmetry (no dup-guard on admission
 * path) is preserved by the absence of a DB unique on (admission_uid, diagnosis_type_uid)
 * (V23 / CR-INC05-07). When the Inpatient/Nursing increment arrives, the admission path
 * simply omits the dup-guard call — no schema change required.
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §2).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>saveWorkingDiagnosis: PatientResource.java:1654-1678</li>
 *   <li>saveFinalDiagnosis: PatientResource.java:1782</li>
 *   <li>delete_working_diagnosis: PatientResource.java:1917-1929</li>
 *   <li>delete_final_diagnosis: PatientResource.java:1917-1929</li>
 *   <li>Duplicate guard message: PatientResource.java:1662, :1782</li>
 *   <li>DiagnosisType not found message: PatientResource.java:1659</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class DiagnosisService implements DiagnosisPort {

    private final ConsultationRepository      consultationRepository;
    private final WorkingDiagnosisRepository  workingDiagnosisRepository;
    private final FinalDiagnosisRepository    finalDiagnosisRepository;
    private final DiagnosisTypeLookup         diagnosisTypeLookup;
    private final AuditRecorder               auditRecorder;
    private final WorkingDiagnosisMapper      workingDiagnosisMapper;
    private final FinalDiagnosisMapper        finalDiagnosisMapper;

    private static final String AUDIT_WORKING = "clinical.WorkingDiagnosis";
    private static final String AUDIT_FINAL   = "clinical.FinalDiagnosis";

    // =========================================================================
    // Working diagnosis
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guards: consultation must exist; diagnosisType must exist; no dup for
     * (consultation, diagnosisTypeUid). On success: append a new row; copy patientUid
     * from the consultation.
     */
    @Override
    @Transactional
    public WorkingDiagnosisDto addWorkingDiagnosis(String consultationUid,
                                                    DiagnosisRequest request,
                                                    TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        requireDiagnosisTypeExists(request.diagnosisTypeUid());
        guardNoDuplicateWorking(consultation, request.diagnosisTypeUid());

        WorkingDiagnosis wd = WorkingDiagnosis.forConsultation(
                consultation,
                request.diagnosisTypeUid(),
                request.description(),
                ctx.dayUid());

        WorkingDiagnosis saved = workingDiagnosisRepository.save(wd);
        auditRecorder.record(AUDIT_WORKING, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return workingDiagnosisMapper.toDto(saved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an empty list for consultations with no diagnoses (not 404 — the
     * consultation must exist but may have zero associated diagnoses).
     */
    @Override
    @Transactional(readOnly = true)
    public List<WorkingDiagnosisDto> listWorkingDiagnoses(String consultationUid,
                                                           TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        return workingDiagnosisMapper.toDtoList(
                workingDiagnosisRepository.findByConsultationOrderByCreatedAtAsc(consultation));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Hard-delete. Throws 404 if the uid does not resolve (defensive improvement
     * over the legacy raw deleteById).
     */
    @Override
    @Transactional
    public void deleteWorkingDiagnosis(String diagnosisUid, TxAuditContext ctx) {
        WorkingDiagnosis wd = workingDiagnosisRepository.findByUid(diagnosisUid)
                .orElseThrow(() -> new NotFoundException(
                        "Working diagnosis not found: " + diagnosisUid));
        workingDiagnosisRepository.delete(wd);
        auditRecorder.record(AUDIT_WORKING, diagnosisUid, AuditAction.DELETE,
                ctx.actorUsername());
    }

    // =========================================================================
    // Final diagnosis
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Guards: consultation must exist; diagnosisType must exist; no dup for
     * (consultation, diagnosisTypeUid). On success: append a new row; copy patientUid
     * from the consultation.
     *
     * <p>Note: the same diagnosisTypeUid may appear in BOTH working AND final diagnoses
     * for the same consultation — the dup-guard is per-table (separate entities).
     */
    @Override
    @Transactional
    public FinalDiagnosisDto addFinalDiagnosis(String consultationUid,
                                                DiagnosisRequest request,
                                                TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        requireDiagnosisTypeExists(request.diagnosisTypeUid());
        guardNoDuplicateFinal(consultation, request.diagnosisTypeUid());

        FinalDiagnosis fd = FinalDiagnosis.forConsultation(
                consultation,
                request.diagnosisTypeUid(),
                request.description(),
                ctx.dayUid());

        FinalDiagnosis saved = finalDiagnosisRepository.save(fd);
        auditRecorder.record(AUDIT_FINAL, saved.getUid(), AuditAction.CREATE,
                ctx.actorUsername());
        return finalDiagnosisMapper.toDto(saved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an empty list for consultations with no final diagnoses.
     */
    @Override
    @Transactional(readOnly = true)
    public List<FinalDiagnosisDto> listFinalDiagnoses(String consultationUid,
                                                       TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        return finalDiagnosisMapper.toDtoList(
                finalDiagnosisRepository.findByConsultationOrderByCreatedAtAsc(consultation));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Hard-delete. Throws 404 if the uid does not resolve.
     */
    @Override
    @Transactional
    public void deleteFinalDiagnosis(String diagnosisUid, TxAuditContext ctx) {
        FinalDiagnosis fd = finalDiagnosisRepository.findByUid(diagnosisUid)
                .orElseThrow(() -> new NotFoundException(
                        "Final diagnosis not found: " + diagnosisUid));
        finalDiagnosisRepository.delete(fd);
        auditRecorder.record(AUDIT_FINAL, diagnosisUid, AuditAction.DELETE,
                ctx.actorUsername());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found"));
    }

    /**
     * Verify the diagnosisTypeUid resolves in the masterdata catalog.
     * Verbatim legacy error: "Diagnosis type not found" (PatientResource.java:1659).
     */
    private void requireDiagnosisTypeExists(String diagnosisTypeUid) {
        if (!diagnosisTypeLookup.existsByUid(diagnosisTypeUid)) {
            throw new NotFoundException("Diagnosis type not found");
        }
    }

    /**
     * Working-diagnosis duplicate guard (PatientResource.java:1662).
     * Verbatim legacy message: "Duplicate Diagnosis Types is not allowed".
     */
    private void guardNoDuplicateWorking(Consultation consultation, String diagnosisTypeUid) {
        if (workingDiagnosisRepository.existsByConsultationAndDiagnosisTypeUid(
                consultation, diagnosisTypeUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate Diagnosis Types is not allowed");
        }
    }

    /**
     * Final-diagnosis duplicate guard (PatientResource.java:1782).
     * Verbatim legacy message: "Duplicate Diagnosis Types is not allowed".
     */
    private void guardNoDuplicateFinal(Consultation consultation, String diagnosisTypeUid) {
        if (finalDiagnosisRepository.existsByConsultationAndDiagnosisTypeUid(
                consultation, diagnosisTypeUid)) {
            throw new InvalidPatientOperationException(
                    "Duplicate Diagnosis Types is not allowed");
        }
    }
}
