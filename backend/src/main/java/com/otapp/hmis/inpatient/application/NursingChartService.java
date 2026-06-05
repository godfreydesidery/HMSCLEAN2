package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.clinical.api.DressingChartView;
import com.otapp.hmis.clinical.api.MedicationAdministrationPort;
import com.otapp.hmis.clinical.api.MedicationAdministrationView;
import com.otapp.hmis.clinical.api.NursingCarePlanView;
import com.otapp.hmis.clinical.api.NursingChartPort;
import com.otapp.hmis.clinical.api.NursingChartView;
import com.otapp.hmis.clinical.api.NursingProgressNoteView;
import com.otapp.hmis.clinical.api.PrescriptionChartPort;
import com.otapp.hmis.clinical.api.PrescriptionChartView;
import com.otapp.hmis.clinical.api.RecordDressingChartCommand;
import com.otapp.hmis.clinical.api.RecordMedicationAdministrationCommand;
import com.otapp.hmis.clinical.api.RecordNursingCarePlanCommand;
import com.otapp.hmis.clinical.api.RecordNursingChartCommand;
import com.otapp.hmis.clinical.api.RecordPrescriptionChartCommand;
import com.otapp.hmis.clinical.api.RecordProgressNoteCommand;
import com.otapp.hmis.inpatient.application.dto.DosingNoteRequest;
import com.otapp.hmis.inpatient.application.dto.DressingChartRequest;
import com.otapp.hmis.inpatient.application.dto.MedicationAdministrationRequest;
import com.otapp.hmis.inpatient.application.dto.NursingCarePlanRequest;
import com.otapp.hmis.inpatient.application.dto.NursingChartRequest;
import com.otapp.hmis.inpatient.application.dto.ProgressNoteRequest;
import com.otapp.hmis.inpatient.domain.Admission;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.masterdata.lookup.DressingLookup;
import com.otapp.hmis.masterdata.lookup.RouteLookup;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inpatient orchestration for the nurse-facing charting surface (inc-07 07b).
 *
 * <p>The inpatient module owns the {@code Admission} + its {@code AdmissionStatus}, so it runs
 * the admission-IN-PROCESS gate (the inpatient-side half of the build-spec §3.4 guard split)
 * and the dressing-registered guard, then delegates the PERSIST to the clinical-owned ports
 * ({@link NursingChartPort}, {@link PrescriptionChartPort}) via {@code clinical :: api}. The
 * dependency edge is one-directional ({@code inpatient → clinical :: api}); clinical never calls
 * back, so {@code ApplicationModules.verify()} stays green.
 *
 * <p><strong>Admission-status gate (verbatim legacy, PatientServiceImpl.java:2564-2577):</strong>
 * PENDING → reject "Could not be done. Admission not verified"; IN-PROCESS → proceed; any other
 * status (STOPPED/HELD/SIGNED-OUT) → reject "Could not be done. Patient already signed off".
 *
 * <p>The five charts are the legacy nurse-facing surface: PatientNursingChart,
 * PatientNursingCarePlan, PatientNursingProgressNote, PatientDressingChart (clinical-owned),
 * and the free-text PatientPrescriptionChart dosing note. NO MAR (CR-07-MAR parked). DELETE-only
 * within 24h (clinical-side guard); NO edit path.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>admission-status gate: PatientServiceImpl.java:2564-2577</li>
 *   <li>nursing chart / care plan: PatientServiceImpl.java:2593-2643</li>
 *   <li>progress note: PatientServiceImpl.java:2647-2698</li>
 *   <li>dressing (billing) + dressing-registered guard: PatientServiceImpl.java:2078-2245, :2094</li>
 *   <li>dosing note GIVEN + nurse guard: PatientServiceImpl.java:2540-2577</li>
 * </ul>
 *
 * <p>inc-07 07b.
 */
@Service
@RequiredArgsConstructor
public class NursingChartService {

    /** Verbatim legacy message — PatientServiceImpl.java:2572. */
    private static final String MSG_NOT_VERIFIED = "Could not be done. Admission not verified";
    /** Verbatim legacy message — PatientServiceImpl.java:2576. */
    private static final String MSG_SIGNED_OFF = "Could not be done. Patient already signed off";
    /** Verbatim legacy message — PatientServiceImpl.java:2094 / AC-07B-DRS-02. */
    private static final String MSG_NOT_DRESSING = "Procedure type is not listed as dressing";
    /** Net-new guard (inc-07 07d, CR-07-MAR): route must be a registered ACTIVE route. */
    private static final String MSG_NOT_ROUTE = "Administration route is not listed or is inactive";
    private static final String CONTEXT_ADMISSION = "ADMISSION";

    private final AdmissionRepository admissionRepository;
    private final NursingChartPort nursingChartPort;
    private final PrescriptionChartPort prescriptionChartPort;
    private final MedicationAdministrationPort medicationAdministrationPort;
    private final DressingLookup dressingLookup;
    private final RouteLookup routeLookup;

    // =========================================================================
    // PatientNursingChart
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public NursingChartView recordNursingChart(String admissionUid, NursingChartRequest req,
                                               TxAuditContext ctx) {
        Admission adm = requireInProcessAdmission(admissionUid);
        return nursingChartPort.recordNursingChart(new RecordNursingChartCommand(
                admissionUid, adm.getPatientUid(), req.nurseUid(), CONTEXT_ADMISSION,
                req.feeding(), req.changingPosition(), req.bedBathing(),
                req.randomBloodSugar(), req.fullBloodSugar(),
                req.drainageOutput(), req.fluidIntake(), req.urineOutput()), ctx);
    }

    @Transactional(readOnly = true)
    public List<NursingChartView> findNursingCharts(String admissionUid) {
        return nursingChartPort.findNursingChartsByAdmission(admissionUid);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteNursingChart(String chartUid, TxAuditContext ctx) {
        nursingChartPort.deleteNursingChart24h(chartUid, ctx);
    }

    // =========================================================================
    // PatientNursingCarePlan
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public NursingCarePlanView recordCarePlan(String admissionUid, NursingCarePlanRequest req,
                                              TxAuditContext ctx) {
        Admission adm = requireInProcessAdmission(admissionUid);
        return nursingChartPort.recordNursingCarePlan(new RecordNursingCarePlanCommand(
                admissionUid, adm.getPatientUid(), req.nurseUid(), CONTEXT_ADMISSION,
                req.nursingDiagnosis(), req.expectedOutcome(),
                req.implementation(), req.evaluation()), ctx);
    }

    @Transactional(readOnly = true)
    public List<NursingCarePlanView> findCarePlans(String admissionUid) {
        return nursingChartPort.findNursingCarePlansByAdmission(admissionUid);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteCarePlan(String carePlanUid, TxAuditContext ctx) {
        nursingChartPort.deleteNursingCarePlan24h(carePlanUid, ctx);
    }

    // =========================================================================
    // PatientNursingProgressNote
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public NursingProgressNoteView recordProgressNote(String admissionUid, ProgressNoteRequest req,
                                                      TxAuditContext ctx) {
        Admission adm = requireInProcessAdmission(admissionUid);
        return nursingChartPort.recordProgressNote(new RecordProgressNoteCommand(
                admissionUid, adm.getPatientUid(), req.nurseUid(), CONTEXT_ADMISSION,
                req.note()), ctx);
    }

    @Transactional(readOnly = true)
    public List<NursingProgressNoteView> findProgressNotes(String admissionUid) {
        return nursingChartPort.findProgressNotesByAdmission(admissionUid);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteProgressNote(String noteUid, TxAuditContext ctx) {
        nursingChartPort.deleteProgressNote24h(noteUid, ctx);
    }

    // =========================================================================
    // PatientDressingChart (BILLING record)
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public DressingChartView recordDressingChart(String admissionUid, DressingChartRequest req,
                                                 TxAuditContext ctx) {
        Admission adm = requireInProcessAdmission(admissionUid);
        // Dressing-registered guard (inpatient-side, AC-07B-DRS-02 / PatientServiceImpl.java:2094).
        if (!dressingLookup.isDressing(req.procedureTypeUid())) {
            throw new InvalidPatientOperationException(MSG_NOT_DRESSING);
        }
        return nursingChartPort.recordDressingChart(new RecordDressingChartCommand(
                admissionUid, adm.getPatientUid(), req.nurseUid(), req.clinicianUid(),
                req.insurancePlanUid(), req.membershipNo(), req.procedureTypeUid(),
                req.procedureTypeName(), req.qty(), req.paymentType()), ctx);
    }

    @Transactional(readOnly = true)
    public List<DressingChartView> findDressingCharts(String admissionUid) {
        return nursingChartPort.findDressingChartsByAdmission(admissionUid);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteDressingChart(String chartUid, TxAuditContext ctx) {
        nursingChartPort.deleteDressingChart24h(chartUid, ctx);
    }

    // =========================================================================
    // PatientPrescriptionChart (free-text dosing note — Q1, NOT MAR)
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public PrescriptionChartView recordDosingNote(String admissionUid, DosingNoteRequest req,
                                                  TxAuditContext ctx) {
        requireInProcessAdmission(admissionUid);
        // The GIVEN-prescription + nurse guards are enforced clinical-side in the port.
        return prescriptionChartPort.record(new RecordPrescriptionChartCommand(
                req.prescriptionUid(), admissionUid, req.nurseUid(),
                req.dosage(), req.output(), req.remark()), ctx);
    }

    @Transactional(readOnly = true)
    public List<PrescriptionChartView> findDosingNotes(String admissionUid) {
        return prescriptionChartPort.findByAdmission(admissionUid);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteDosingNote(String chartUid, TxAuditContext ctx) {
        prescriptionChartPort.delete24hWindow(chartUid, ctx);
    }

    // =========================================================================
    // MedicationAdministration (MAR) — NET-NEW closed-loop record (inc-07 07d, CR-07-MAR)
    //
    // Additive over the free-text dosing note above; both coexist. Inpatient-side guards:
    // the admission-IN-PROCESS gate + the route-is-registered-ACTIVE guard (RouteLookup).
    // The GIVEN-prescription + nurse-present guards are enforced clinical-side in the port.
    // No delete path — a MAR entry is a closed-loop clinical-safety record (create + read only).
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public MedicationAdministrationView recordMedicationAdministration(
            String admissionUid, MedicationAdministrationRequest req, TxAuditContext ctx) {
        requireInProcessAdmission(admissionUid);
        // Route guard (inpatient-side): the route must be a registered ACTIVE administration route.
        if (!routeLookup.isActiveRoute(req.routeUid())) {
            throw new InvalidPatientOperationException(MSG_NOT_ROUTE);
        }
        return medicationAdministrationPort.record(new RecordMedicationAdministrationCommand(
                req.prescriptionUid(), admissionUid, req.nurseUid(), req.routeUid(),
                req.administeredAt(), req.doseGiven(), req.patientResponse()), ctx);
    }

    @Transactional(readOnly = true)
    public List<MedicationAdministrationView> findMedicationAdministrations(String admissionUid) {
        return medicationAdministrationPort.findByAdmission(admissionUid);
    }

    // =========================================================================
    // Shared admission-status gate (verbatim legacy PatientServiceImpl.java:2564-2577)
    // =========================================================================

    private Admission requireInProcessAdmission(String admissionUid) {
        Admission adm = admissionRepository.findByUid(admissionUid)
                .orElseThrow(() -> new NotFoundException("Admission not found: " + admissionUid));
        AdmissionStatus status = adm.getStatus();
        if (status == AdmissionStatus.PENDING) {
            throw new InvalidPatientOperationException(MSG_NOT_VERIFIED);
        }
        if (status != AdmissionStatus.IN_PROCESS) {
            // STOPPED / HELD / SIGNED-OUT — patient already signed off.
            throw new InvalidPatientOperationException(MSG_SIGNED_OFF);
        }
        return adm;
    }
}
