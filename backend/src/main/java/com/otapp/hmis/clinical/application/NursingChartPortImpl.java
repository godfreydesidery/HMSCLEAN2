package com.otapp.hmis.clinical.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.api.DressingChartView;
import com.otapp.hmis.clinical.api.NursingCarePlanView;
import com.otapp.hmis.clinical.api.NursingChartPort;
import com.otapp.hmis.clinical.api.NursingChartView;
import com.otapp.hmis.clinical.api.NursingProgressNoteView;
import com.otapp.hmis.clinical.api.RecordDressingChartCommand;
import com.otapp.hmis.clinical.api.RecordNursingCarePlanCommand;
import com.otapp.hmis.clinical.api.RecordNursingChartCommand;
import com.otapp.hmis.clinical.api.RecordProgressNoteCommand;
import com.otapp.hmis.clinical.domain.PatientDressingChart;
import com.otapp.hmis.clinical.domain.PatientDressingChartRepository;
import com.otapp.hmis.clinical.domain.PatientNursingCarePlan;
import com.otapp.hmis.clinical.domain.PatientNursingCarePlanRepository;
import com.otapp.hmis.clinical.domain.PatientNursingChart;
import com.otapp.hmis.clinical.domain.PatientNursingChartRepository;
import com.otapp.hmis.clinical.domain.PatientNursingProgressNote;
import com.otapp.hmis.clinical.domain.PatientNursingProgressNoteRepository;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link NursingChartPort} (inc-07 07b, ADR-0008 §1).
 *
 * <p>This class is intentionally package-private in {@code clinical.application} — callers
 * depend only on the {@link NursingChartPort} interface from {@code clinical.api}.
 *
 * <p>Runs in the caller's transaction ({@code Propagation.REQUIRED}, no @Async /
 * REQUIRES_NEW). All clinical-side guards are enforced here verbatim.
 *
 * <p>Guard responsibility split (build spec §3.4):
 * <ul>
 *   <li>INPATIENT-SIDE (before calling this port): admission-IN-PROCESS gate;
 *       dressing-registered guard (AC-07B-DRS-02).</li>
 *   <li>CLINICAL-SIDE (here): nurse-uid present guard ('Nurse information not found');
 *       context-exclusivity (admission-only rejects consultation/nonConsultation with verbatim
 *       legacy messages); 24-hour delete window guard.</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Nursing chart save: PatientServiceImpl.java:2593-2643</li>
 *   <li>Care plan save: PatientServiceImpl.java:2593-2643</li>
 *   <li>Progress note save: PatientServiceImpl.java:2647-2698</li>
 *   <li>Dressing save: PatientServiceImpl.java:2078-2245</li>
 *   <li>24h delete guard: PatientResource.java:3135-3138</li>
 * </ul>
 *
 * <p>inc-07 07b / ADR-0008 §1.
 */
@Service
@RequiredArgsConstructor
class NursingChartPortImpl implements NursingChartPort {

    private static final Logger log = LoggerFactory.getLogger(NursingChartPortImpl.class);

    private static final String AUDIT_NURSING_CHART    = "clinical.PatientNursingChart";
    private static final String AUDIT_CARE_PLAN        = "clinical.PatientNursingCarePlan";
    private static final String AUDIT_PROGRESS_NOTE    = "clinical.PatientNursingProgressNote";
    private static final String AUDIT_DRESSING_CHART   = "clinical.PatientDressingChart";

    /** Verbatim legacy message — PatientServiceImpl.java:2595,2651. */
    private static final String MSG_NO_NURSE =
            "Nurse information not found";
    /** Verbatim legacy message — PatientServiceImpl.java:2617,2671. */
    private static final String MSG_OUTPATIENT =
            "Operation not available for outpatients";
    /** Verbatim legacy message — PatientServiceImpl.java:2619,2673. */
    private static final String MSG_OUTSIDER =
            "Operation not available for outsiders";
    /** Verbatim legacy message — PatientResource.java:3135-3138. */
    private static final String MSG_DELETE_24H =
            "Could not delete record. only records not exceeding 24 hours can be deleted";
    /** Standard not-found message reused across all four chart-type deletes. */
    private static final String MSG_NOT_FOUND = "Record not found";

    private final PatientNursingChartRepository       nursingChartRepository;
    private final PatientNursingCarePlanRepository    carePlanRepository;
    private final PatientNursingProgressNoteRepository progressNoteRepository;
    private final PatientDressingChartRepository      dressingChartRepository;
    private final BillingCommands billingCommands;
    private final AuditRecorder auditRecorder;

    // =========================================================================
    // PatientNursingChart
    // =========================================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public NursingChartView recordNursingChart(RecordNursingChartCommand cmd, TxAuditContext ctx) {
        // Nurse-uid guard (PatientServiceImpl.java:2595)
        if (cmd.nurseUid() == null || cmd.nurseUid().isBlank()) {
            throw new InvalidPatientOperationException(MSG_NO_NURSE);
        }
        // Context-exclusivity guard (PatientServiceImpl.java:2616-2619)
        enforceAdmissionOnlyContext(cmd.admissionUid(), cmd.contextType());

        PatientNursingChart chart = PatientNursingChart.create(
                cmd.admissionUid(), cmd.patientUid(), cmd.nurseUid(),
                cmd.feeding(), cmd.changingPosition(), cmd.bedBathing(),
                cmd.randomBloodSugar(), cmd.fullBloodSugar(),
                cmd.drainageOutput(), cmd.fluidIntake(), cmd.urineOutput());

        nursingChartRepository.save(chart);
        auditRecorder.record(AUDIT_NURSING_CHART, chart.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        log.debug("NursingChartPortImpl: saved NursingChart uid={} admissionUid={}",
                chart.getUid(), cmd.admissionUid());
        return toNursingChartView(chart);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NursingChartView> findNursingChartsByAdmission(String admissionUid) {
        return nursingChartRepository
                .findByAdmissionUidOrderByCreatedAtAsc(admissionUid)
                .stream().map(this::toNursingChartView).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteNursingChart24h(String chartUid, TxAuditContext ctx) {
        PatientNursingChart chart = nursingChartRepository.findByUid(chartUid)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        // 24h guard (PatientResource.java:3135-3138)
        long hours = ChronoUnit.HOURS.between(chart.getCreatedAt(), ctx.timestamp());
        if (hours >= 24) {
            throw new InvalidPatientOperationException(MSG_DELETE_24H);
        }
        nursingChartRepository.delete(chart);
        auditRecorder.record(AUDIT_NURSING_CHART, chartUid, AuditAction.DELETE,
                ctx.actorUsername());
    }

    // =========================================================================
    // PatientNursingCarePlan
    // =========================================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public NursingCarePlanView recordNursingCarePlan(RecordNursingCarePlanCommand cmd,
                                                     TxAuditContext ctx) {
        if (cmd.nurseUid() == null || cmd.nurseUid().isBlank()) {
            throw new InvalidPatientOperationException(MSG_NO_NURSE);
        }
        enforceAdmissionOnlyContext(cmd.admissionUid(), cmd.contextType());

        PatientNursingCarePlan plan = PatientNursingCarePlan.create(
                cmd.admissionUid(), cmd.patientUid(), cmd.nurseUid(),
                cmd.nursingDiagnosis(), cmd.expectedOutcome(),
                cmd.implementation(), cmd.evaluation());

        carePlanRepository.save(plan);
        auditRecorder.record(AUDIT_CARE_PLAN, plan.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        log.debug("NursingChartPortImpl: saved NursingCarePlan uid={} admissionUid={}",
                plan.getUid(), cmd.admissionUid());
        return toCarePlanView(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NursingCarePlanView> findNursingCarePlansByAdmission(String admissionUid) {
        return carePlanRepository
                .findByAdmissionUidOrderByCreatedAtAsc(admissionUid)
                .stream().map(this::toCarePlanView).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteNursingCarePlan24h(String carePlanUid, TxAuditContext ctx) {
        PatientNursingCarePlan plan = carePlanRepository.findByUid(carePlanUid)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        long hours = ChronoUnit.HOURS.between(plan.getCreatedAt(), ctx.timestamp());
        if (hours >= 24) {
            throw new InvalidPatientOperationException(MSG_DELETE_24H);
        }
        carePlanRepository.delete(plan);
        auditRecorder.record(AUDIT_CARE_PLAN, carePlanUid, AuditAction.DELETE,
                ctx.actorUsername());
    }

    // =========================================================================
    // PatientNursingProgressNote
    // =========================================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public NursingProgressNoteView recordProgressNote(RecordProgressNoteCommand cmd,
                                                      TxAuditContext ctx) {
        if (cmd.nurseUid() == null || cmd.nurseUid().isBlank()) {
            throw new InvalidPatientOperationException(MSG_NO_NURSE);
        }
        enforceAdmissionOnlyContext(cmd.admissionUid(), cmd.contextType());

        PatientNursingProgressNote note = PatientNursingProgressNote.create(
                cmd.admissionUid(), cmd.patientUid(), cmd.nurseUid(), cmd.note());

        progressNoteRepository.save(note);
        auditRecorder.record(AUDIT_PROGRESS_NOTE, note.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        log.debug("NursingChartPortImpl: saved ProgressNote uid={} admissionUid={}",
                note.getUid(), cmd.admissionUid());
        return toProgressNoteView(note);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NursingProgressNoteView> findProgressNotesByAdmission(String admissionUid) {
        return progressNoteRepository
                .findByAdmissionUidOrderByCreatedAtAsc(admissionUid)
                .stream().map(this::toProgressNoteView).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteProgressNote24h(String noteUid, TxAuditContext ctx) {
        PatientNursingProgressNote note = progressNoteRepository.findByUid(noteUid)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        long hours = ChronoUnit.HOURS.between(note.getCreatedAt(), ctx.timestamp());
        if (hours >= 24) {
            throw new InvalidPatientOperationException(MSG_DELETE_24H);
        }
        progressNoteRepository.delete(note);
        auditRecorder.record(AUDIT_PROGRESS_NOTE, noteUid, AuditAction.DELETE,
                ctx.actorUsername());
    }

    // =========================================================================
    // PatientDressingChart
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Billing trichotomy (PatientServiceImpl.java:2078-2245):
     * <ol>
     *   <li>INSURANCE + ProcedureTypeInsurancePlan covered=true →
     *       {@code recordClinicalCharge(kind=PROCEDURE, paymentType=INSURANCE)} →
     *       COVERED bill under PENDING PatientInvoice (AC-07B-DRS-04).</li>
     *   <li>admission present + no covered plan →
     *       {@code recordClinicalCharge(kind=PROCEDURE, inpatient=true, paymentType=CASH)} →
     *       billing engine emits VERIFIED bill under null-plan PENDING PatientInvoice
     *       (AC-07B-DRS-05).</li>
     *   <li>Cash (no admission, no insurance) →
     *       {@code recordClinicalCharge(kind=PROCEDURE, paymentType=CASH)} →
     *       UNPAID bill (no invoice).</li>
     * </ol>
     *
     * <p>The billing engine owns the status decision; {@code inpatient=true} when
     * admissionUid is non-null triggers the VERIFIED path for uninsured admissions.
     *
     * <p>Bill-line display literals: TODO(CR-07-Q13-billing-display) — {@code ChargeRequest}
     * {@code billItem}/{@code description} are passed but derive to 'Procedure'/'Procedure' in
     * the billing engine until CR-07-Q13-billing-display is approved and the billing engine
     * updated. The amount/status trichotomy is frozen per AC-07B-DRS-03 (PARTIAL).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public DressingChartView recordDressingChart(RecordDressingChartCommand cmd,
                                                 TxAuditContext ctx) {
        boolean isInpatient = cmd.admissionUid() != null && !cmd.admissionUid().isBlank();
        boolean isInsurance = "INSURANCE".equalsIgnoreCase(cmd.paymentType())
                && cmd.insurancePlanUid() != null && !cmd.insurancePlanUid().isBlank();

        PaymentMode paymentMode = isInsurance ? PaymentMode.INSURANCE : PaymentMode.CASH;

        // TODO(CR-07-Q13-billing-display): when billing-display CR is approved, pass:
        //   billItem = "Procedure"
        //   description = "Dressing: " + cmd.procedureTypeName()
        // Until then, null defers to the engine's default derivation (labelFor(PROCEDURE)).
        ChargeRequest chargeRequest = new ChargeRequest(
                cmd.patientUid(),
                isInsurance ? cmd.insurancePlanUid() : null,
                isInsurance ? cmd.membershipNo() : null,
                ServiceKind.PROCEDURE,
                cmd.procedureTypeUid(),
                cmd.qty(),
                paymentMode,
                isInpatient,
                false,           // followUp — not applicable for procedures
                null,            // billItem — TODO(CR-07-Q13-billing-display)
                null,            // description — TODO(CR-07-Q13-billing-display)
                cmd.admissionUid()
        );

        var chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);

        PatientDressingChart chart = PatientDressingChart.create(
                cmd.qty(),
                cmd.paymentType(),
                isInsurance ? cmd.membershipNo() : null,
                chargeResult.billUid(),
                cmd.procedureTypeUid(),
                cmd.admissionUid(),
                cmd.clinicianUid(),
                cmd.nurseUid(),
                isInsurance ? cmd.insurancePlanUid() : null,
                cmd.patientUid());

        dressingChartRepository.save(chart);
        auditRecorder.record(AUDIT_DRESSING_CHART, chart.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        log.debug("NursingChartPortImpl: saved DressingChart uid={} billUid={} status={}",
                chart.getUid(), chargeResult.billUid(), chargeResult.status());
        return toDressingChartView(chart);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DressingChartView> findDressingChartsByAdmission(String admissionUid) {
        return dressingChartRepository
                .findByAdmissionUidOrderByCreatedAtAsc(admissionUid)
                .stream().map(this::toDressingChartView).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteDressingChart24h(String chartUid, TxAuditContext ctx) {
        PatientDressingChart chart = dressingChartRepository.findByUid(chartUid)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        long hours = ChronoUnit.HOURS.between(chart.getCreatedAt(), ctx.timestamp());
        if (hours >= 24) {
            throw new InvalidPatientOperationException(MSG_DELETE_24H);
        }
        // Cancel the associated bill via billing::api (handles reversal of RECEIVED payment,
        // credit note, invoice detail cleanup). AC-07B-DRS-06.
        billingCommands.cancelCharge(chart.getPatientBillUid(), "Canceled dressing", ctx);
        dressingChartRepository.delete(chart);
        auditRecorder.record(AUDIT_DRESSING_CHART, chartUid, AuditAction.DELETE,
                ctx.actorUsername());
    }

    // =========================================================================
    // Mapping helpers
    // =========================================================================

    private NursingChartView toNursingChartView(PatientNursingChart c) {
        return new NursingChartView(
                c.getUid(), c.getAdmissionUid(), c.getNurseUid(),
                c.getFeeding(), c.getChangingPosition(), c.getBedBathing(),
                c.getRandomBloodSugar(), c.getFullBloodSugar(),
                c.getDrainageOutput(), c.getFluidIntake(), c.getUrineOutput(),
                c.getCreatedAt());
    }

    private NursingCarePlanView toCarePlanView(PatientNursingCarePlan p) {
        return new NursingCarePlanView(
                p.getUid(), p.getAdmissionUid(), p.getNurseUid(),
                p.getNursingDiagnosis(), p.getExpectedOutcome(),
                p.getImplementation(), p.getEvaluation(),
                p.getCreatedAt());
    }

    private NursingProgressNoteView toProgressNoteView(PatientNursingProgressNote n) {
        return new NursingProgressNoteView(
                n.getUid(), n.getAdmissionUid(), n.getNurseUid(),
                n.getNote(), n.getCreatedAt());
    }

    private DressingChartView toDressingChartView(PatientDressingChart d) {
        return new DressingChartView(
                d.getUid(), d.getAdmissionUid(), d.getNurseUid(),
                d.getProcedureTypeUid(), d.getPatientBillUid(),
                d.getQty(), d.getPaymentType(), d.getCreatedAt());
    }

    // =========================================================================
    // Context-exclusivity guard helper
    // =========================================================================

    /**
     * Enforces the admission-only context restriction.
     *
     * <p>Verbatim legacy guard sequence (PatientServiceImpl.java:2601-2619):
     * <ol>
     *   <li>contextType == "CONSULTATION" → 422 'Operation not available for outpatients'</li>
     *   <li>contextType == "NON_CONSULTATION" → 422 'Operation not available for outsiders'</li>
     *   <li>admissionUid null/blank (no context at all) → 422 outpatient message as fallback</li>
     * </ol>
     *
     * <p>The inpatient module always passes contextType="ADMISSION" with a non-null admissionUid
     * for the 07b write path. The guards are present to reproduce the legacy rejection behaviour
     * should this port ever be called from a non-admission context.
     *
     * @param admissionUid the admissionUid from the command (may be null)
     * @param contextType  "ADMISSION", "CONSULTATION", or "NON_CONSULTATION" (may be null)
     */
    private void enforceAdmissionOnlyContext(String admissionUid, String contextType) {
        if ("CONSULTATION".equals(contextType)) {
            throw new InvalidPatientOperationException(MSG_OUTPATIENT);
        }
        if ("NON_CONSULTATION".equals(contextType)) {
            throw new InvalidPatientOperationException(MSG_OUTSIDER);
        }
        if (admissionUid == null || admissionUid.isBlank()) {
            // No context at all — legacy treats as outpatient (PatientServiceImpl.java:2613)
            throw new InvalidPatientOperationException(MSG_OUTPATIENT);
        }
    }
}
