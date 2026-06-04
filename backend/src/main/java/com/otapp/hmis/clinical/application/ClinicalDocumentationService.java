package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.ClinicalNoteDto;
import com.otapp.hmis.clinical.application.dto.GeneralExaminationDto;
import com.otapp.hmis.clinical.application.dto.GeneralExaminationRequest;
import com.otapp.hmis.clinical.application.dto.PatientVitalDto;
import com.otapp.hmis.clinical.application.dto.SoapNoteRequest;
import com.otapp.hmis.clinical.application.dto.VitalsRequest;
import com.otapp.hmis.clinical.domain.ClinicalNote;
import com.otapp.hmis.clinical.domain.ClinicalNoteRepository;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.ConsultationRepository;
import com.otapp.hmis.clinical.domain.GeneralExamination;
import com.otapp.hmis.clinical.domain.GeneralExaminationRepository;
import com.otapp.hmis.clinical.domain.NonConsultation;
import com.otapp.hmis.clinical.domain.NonConsultationRepository;
import com.otapp.hmis.clinical.domain.PatientVital;
import com.otapp.hmis.clinical.domain.PatientVitalRepository;
import com.otapp.hmis.clinical.domain.PatientVitalStatus;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementing the clinical documentation operations for SOAP notes, general
 * examinations, and nurse vitals staging (inc-05 C5, PatientResource.java:1298-1598).
 *
 * <p><strong>UPSERT-per-encounter (saveCG pattern — PatientResource.java:1469-1598):</strong>
 * For consultations and non-consultations, exactly one row exists per encounter
 * (enforced by V23 partial UNIQUE indexes). The upsert finds the existing row and
 * OVERWRITES its fields in place; if no row exists, a new row is created.
 *
 * <p><strong>Side-effecting GETs (CR-INC05-06 — REPRODUCE faithfully):</strong>
 * The load-or-create methods auto-persist an empty row on first GET (HTTP 201).
 * The legacy UI relies on this behaviour (blank-note poll) so it is reproduced verbatim.
 *
 * <p><strong>Exactly-one-encounter guard (PatientResource.java:1469-1498):</strong>
 * The saveCG path throws if more than one or zero encounter ids are present. This is
 * enforced here for the consultation/non-consultation write paths with the verbatim
 * legacy messages (admission path is DEFERRED).
 *
 * <p><strong>DEFERRED — admission paths:</strong>
 * The {@code admissionUid} column exists on all three entities but no admission-scoped
 * endpoints or append logic are implemented. The Inpatient/Nursing increment implements these.
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §2).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>saveCG: PatientResource.java:1469-1598</li>
 *   <li>load_clinical_note: PatientResource.java (auto-create empty note)</li>
 *   <li>load_general_examination: PatientResource.java (auto-create empty exam)</li>
 *   <li>load_patient_vitals: PatientResource.java:1298-1307 (auto-create EMPTY vital)</li>
 *   <li>request_patient_vitals: PatientResource.java:1340 (SUBMITTED→ARCHIVED + copy)</li>
 *   <li>exactly-one guard: PatientResource.java:1469-1498</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
class ClinicalDocumentationService implements ClinicalDocumentationPort {

    private final ConsultationRepository     consultationRepository;
    private final NonConsultationRepository  nonConsultationRepository;
    private final ClinicalNoteRepository     clinicalNoteRepository;
    private final GeneralExaminationRepository generalExaminationRepository;
    private final PatientVitalRepository     patientVitalRepository;
    private final AuditRecorder              auditRecorder;
    private final ClinicalNoteMapper         clinicalNoteMapper;
    private final GeneralExaminationMapper   generalExaminationMapper;
    private final PatientVitalMapper         patientVitalMapper;

    private static final String AUDIT_NOTE   = "clinical.ClinicalNote";
    private static final String AUDIT_EXAM   = "clinical.GeneralExamination";
    private static final String AUDIT_VITAL  = "clinical.PatientVital";

    // =========================================================================
    // ClinicalNote
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>UPSERT: find the existing ClinicalNote for this consultation and overwrite its
     * SOAP fields; create a new row if none exists.
     * Exactly-one-encounter guard: the supplied consultationUid must resolve.
     */
    @Override
    @Transactional
    public ClinicalNoteDto saveClinicalNote(String consultationUid,
                                             SoapNoteRequest request,
                                             TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);

        Optional<ClinicalNote> existing = clinicalNoteRepository.findByConsultation(consultation);

        ClinicalNote note;
        boolean created;
        if (existing.isPresent()) {
            note = existing.get();
            note.updateSoap(
                    request.mainComplain(),
                    request.presentIllnessHistory(),
                    request.pastMedicalHistory(),
                    request.familyAndSocialHistory(),
                    request.drugsAndAllergyHistory(),
                    request.reviewOfOtherSystems(),
                    request.physicalExamination(),
                    request.managementPlan());
            created = false;
        } else {
            note = ClinicalNote.forConsultation(consultation, ctx.dayUid());
            note.updateSoap(
                    request.mainComplain(),
                    request.presentIllnessHistory(),
                    request.pastMedicalHistory(),
                    request.familyAndSocialHistory(),
                    request.drugsAndAllergyHistory(),
                    request.reviewOfOtherSystems(),
                    request.physicalExamination(),
                    request.managementPlan());
            created = true;
        }

        ClinicalNote saved = clinicalNoteRepository.save(note);
        auditRecorder.record(AUDIT_NOTE, saved.getUid(),
                created ? AuditAction.CREATE : AuditAction.UPDATE,
                ctx.actorUsername());
        return clinicalNoteMapper.toDto(saved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Side-effecting GET: auto-creates an empty persisted note if none exists
     * (CR-INC05-06 — faithful reproduction of legacy load_clinical_note_by_consultation_id).
     */
    @Override
    @Transactional
    public LoadResult<ClinicalNoteDto> loadOrCreateClinicalNote(String consultationUid,
                                                                 TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);

        Optional<ClinicalNote> existing = clinicalNoteRepository.findByConsultation(consultation);
        if (existing.isPresent()) {
            return new LoadResult<>(clinicalNoteMapper.toDto(existing.get()), false);
        }

        // Auto-create an empty note (CR-INC05-06: blank-note creation on first GET)
        ClinicalNote note = ClinicalNote.forConsultation(consultation, ctx.dayUid());
        ClinicalNote saved = clinicalNoteRepository.save(note);
        auditRecorder.record(AUDIT_NOTE, saved.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return new LoadResult<>(clinicalNoteMapper.toDto(saved), true);
    }

    // =========================================================================
    // GeneralExamination
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>UPSERT: find the existing GeneralExamination for this consultation and overwrite
     * its vital fields; create a new row if none exists.
     */
    @Override
    @Transactional
    public GeneralExaminationDto saveGeneralExamination(String consultationUid,
                                                         GeneralExaminationRequest request,
                                                         TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);
        return upsertExamForConsultation(consultation, request, ctx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Side-effecting GET: auto-creates an empty GeneralExamination if none exists
     * (CR-INC05-06 — faithful reproduction of load_general_examination_by_consultation_id).
     */
    @Override
    @Transactional
    public LoadResult<GeneralExaminationDto> loadOrCreateGeneralExaminationByConsultation(
            String consultationUid, TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);

        Optional<GeneralExamination> existing =
                generalExaminationRepository.findByConsultation(consultation);
        if (existing.isPresent()) {
            return new LoadResult<>(generalExaminationMapper.toDto(existing.get()), false);
        }

        GeneralExamination ge = GeneralExamination.forConsultation(consultation, ctx.dayUid());
        GeneralExamination saved = generalExaminationRepository.save(ge);
        auditRecorder.record(AUDIT_EXAM, saved.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return new LoadResult<>(generalExaminationMapper.toDto(saved), true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>UPSERT: find the existing GeneralExamination for this non-consultation and overwrite
     * its vital fields; create a new row if none exists.
     */
    @Override
    @Transactional
    public GeneralExaminationDto saveGeneralExaminationForNonConsultation(
            String nonConsultationUid, GeneralExaminationRequest request, TxAuditContext ctx) {
        NonConsultation nc = requireNonConsultation(nonConsultationUid);

        Optional<GeneralExamination> existing =
                generalExaminationRepository.findByNonConsultation(nc);

        GeneralExamination ge;
        boolean created;
        if (existing.isPresent()) {
            ge = existing.get();
            ge.updateVitals(
                    request.pressure(), request.temperature(), request.pulseRate(),
                    request.weight(), request.height(), request.bodyMassIndex(),
                    request.bodyMassIndexComment(), request.bodySurfaceArea(),
                    request.saturationOxygen(), request.respiratoryRate(), request.description());
            created = false;
        } else {
            ge = GeneralExamination.forNonConsultation(nc, ctx.dayUid());
            ge.updateVitals(
                    request.pressure(), request.temperature(), request.pulseRate(),
                    request.weight(), request.height(), request.bodyMassIndex(),
                    request.bodyMassIndexComment(), request.bodySurfaceArea(),
                    request.saturationOxygen(), request.respiratoryRate(), request.description());
            created = true;
        }

        GeneralExamination saved = generalExaminationRepository.save(ge);
        auditRecorder.record(AUDIT_EXAM, saved.getUid(),
                created ? AuditAction.CREATE : AuditAction.UPDATE,
                ctx.actorUsername());
        return generalExaminationMapper.toDto(saved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Side-effecting GET: auto-creates an empty GeneralExamination for the non-consultation
     * if none exists (CR-INC05-06 — faithful reproduction of
     * load_general_examination_by_non_consultation_id).
     */
    @Override
    @Transactional
    public LoadResult<GeneralExaminationDto> loadOrCreateGeneralExaminationByNonConsultation(
            String nonConsultationUid, TxAuditContext ctx) {
        NonConsultation nc = requireNonConsultation(nonConsultationUid);

        Optional<GeneralExamination> existing =
                generalExaminationRepository.findByNonConsultation(nc);
        if (existing.isPresent()) {
            return new LoadResult<>(generalExaminationMapper.toDto(existing.get()), false);
        }

        GeneralExamination ge = GeneralExamination.forNonConsultation(nc, ctx.dayUid());
        GeneralExamination saved = generalExaminationRepository.save(ge);
        auditRecorder.record(AUDIT_EXAM, saved.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return new LoadResult<>(generalExaminationMapper.toDto(saved), true);
    }

    // =========================================================================
    // PatientVital staging flow
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Side-effecting GET: auto-creates an EMPTY PatientVital if none exists
     * (CR-INC05-06 — faithful reproduction of PatientResource.java:1298-1307).
     */
    @Override
    @Transactional
    public LoadResult<PatientVitalDto> loadOrCreateVital(String consultationUid,
                                                          TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);

        Optional<PatientVital> existing =
                patientVitalRepository.findByConsultation(consultation);
        if (existing.isPresent()) {
            return new LoadResult<>(patientVitalMapper.toDto(existing.get()), false);
        }

        PatientVital pv = PatientVital.forConsultation(consultation, ctx.dayUid());
        PatientVital saved = patientVitalRepository.save(pv);
        auditRecorder.record(AUDIT_VITAL, saved.getUid(), AuditAction.CREATE, ctx.actorUsername());
        return new LoadResult<>(patientVitalMapper.toDto(saved), true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>If no PatientVital exists yet (nurse skipped the load-or-create step), a new row is
     * created directly as SUBMITTED. If an existing row is present (EMPTY or SUBMITTED), its
     * fields are overwritten and status transitions to SUBMITTED.
     */
    @Override
    @Transactional
    public PatientVitalDto submitVital(String consultationUid,
                                       VitalsRequest request,
                                       TxAuditContext ctx) {
        Consultation consultation = requireConsultation(consultationUid);

        PatientVital pv = patientVitalRepository.findByConsultation(consultation)
                .orElseGet(() -> PatientVital.forConsultation(consultation, ctx.dayUid()));

        pv.submitVitals(
                request.pressure(), request.temperature(), request.pulseRate(),
                request.weight(), request.height(), request.bodyMassIndex(),
                request.bodyMassIndexComment(), request.bodySurfaceArea(),
                request.saturationOxygen(), request.respiratoryRate(), request.description());

        PatientVital saved = patientVitalRepository.save(pv);
        auditRecorder.record(AUDIT_VITAL, saved.getUid(), AuditAction.UPDATE, ctx.actorUsername());
        return patientVitalMapper.toDto(saved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Guard: PatientVital for the consultation must exist with status=SUBMITTED.
     * If the vital does not exist, or its status is not SUBMITTED, throw 422 with the
     * verbatim legacy message (PatientResource.java:1340).
     *
     * <p>On success:
     * <ol>
     *   <li>Copy all vital fields from the PatientVital into the consultation's
     *       GeneralExamination via the upsert helper (one row per consultation — V23 partial
     *       UNIQUE ensures no duplicate is created).</li>
     *   <li>Archive the PatientVital (status → ARCHIVED).</li>
     * </ol>
     *
     * <p>Design choice — persisting the copied vitals:
     * The legacy built a transient GeneralExamination object but the effective result was that
     * the consultation's exam was populated. This implementation persists the exam via the
     * standard consultation upsert (one row per consultation, consistent with saveCG).
     * This is documented as an improvement over the legacy transient approach (the data is
     * now durably stored in the consultation's GeneralExamination row).
     */
    @Override
    @Transactional
    public GeneralExaminationDto requestVital(String consultationUid, TxAuditContext ctx) {
        // Defect fix (not reproducing a legacy bug): use clean NotFoundException (404) instead
        // of the raw NoSuchElementException (500) the legacy produces on missing consultation.
        Consultation consultation = requireConsultation(consultationUid);

        // Guard: PatientVital must exist with status=SUBMITTED (verbatim legacy message)
        PatientVital pv = patientVitalRepository
                .findByConsultationAndStatus(consultation, PatientVitalStatus.SUBMITTED)
                .orElseThrow(() -> new InvalidPatientOperationException(
                        "Vitals already requested or not submitted"));

        // Copy vital fields into the consultation's GeneralExamination (upsert)
        GeneralExaminationRequest examRequest = new GeneralExaminationRequest(
                pv.getPressure(),
                pv.getTemperature(),
                pv.getPulseRate(),
                pv.getWeight(),
                pv.getHeight(),
                pv.getBodyMassIndex(),
                pv.getBodyMassIndexComment(),
                pv.getBodySurfaceArea(),
                pv.getSaturationOxygen(),
                pv.getRespiratoryRate(),
                pv.getDescription());

        GeneralExaminationDto examDto = upsertExamForConsultation(consultation, examRequest, ctx);

        // Archive the PatientVital
        pv.archive();
        patientVitalRepository.save(pv);
        auditRecorder.record(AUDIT_VITAL, pv.getUid(), AuditAction.UPDATE, ctx.actorUsername());

        return examDto;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Consultation requireConsultation(String uid) {
        return consultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + uid));
    }

    private NonConsultation requireNonConsultation(String uid) {
        return nonConsultationRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("NonConsultation not found: " + uid));
    }

    /**
     * Shared UPSERT helper for consultation-bound GeneralExamination (used by both
     * {@code saveGeneralExamination} and {@code requestVital} copy path).
     *
     * <p>Finds the existing exam for the consultation and overwrites its fields, or creates
     * a new row if none exists. Writes one audit record.
     */
    private GeneralExaminationDto upsertExamForConsultation(Consultation consultation,
                                                              GeneralExaminationRequest request,
                                                              TxAuditContext ctx) {
        Optional<GeneralExamination> existing =
                generalExaminationRepository.findByConsultation(consultation);

        GeneralExamination ge;
        boolean created;
        if (existing.isPresent()) {
            ge = existing.get();
            ge.updateVitals(
                    request.pressure(), request.temperature(), request.pulseRate(),
                    request.weight(), request.height(), request.bodyMassIndex(),
                    request.bodyMassIndexComment(), request.bodySurfaceArea(),
                    request.saturationOxygen(), request.respiratoryRate(), request.description());
            created = false;
        } else {
            ge = GeneralExamination.forConsultation(consultation, ctx.dayUid());
            ge.updateVitals(
                    request.pressure(), request.temperature(), request.pulseRate(),
                    request.weight(), request.height(), request.bodyMassIndex(),
                    request.bodyMassIndexComment(), request.bodySurfaceArea(),
                    request.saturationOxygen(), request.respiratoryRate(), request.description());
            created = true;
        }

        GeneralExamination saved = generalExaminationRepository.save(ge);
        auditRecorder.record(AUDIT_EXAM, saved.getUid(),
                created ? AuditAction.CREATE : AuditAction.UPDATE,
                ctx.actorUsername());
        return generalExaminationMapper.toDto(saved);
    }
}
