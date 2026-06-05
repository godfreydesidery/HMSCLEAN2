package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.PrescriptionChartPort;
import com.otapp.hmis.clinical.api.PrescriptionChartView;
import com.otapp.hmis.clinical.api.RecordPrescriptionChartCommand;
import com.otapp.hmis.clinical.domain.PatientPrescriptionChart;
import com.otapp.hmis.clinical.domain.PatientPrescriptionChartRepository;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.clinical.domain.PrescriptionStatus;
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
 * Package-private implementation of {@link PrescriptionChartPort} (inc-07 SEAM-2 / 07b,
 * ADR-0008 §1) — the free-text inpatient dosing-note write+read+delete path.
 *
 * <p>This class is package-private in {@code clinical.application} — callers depend only on
 * the {@link PrescriptionChartPort} interface from {@code clinical.api}. Runs in the caller's
 * transaction ({@code Propagation.REQUIRED}; no {@code @Async}/{@code REQUIRES_NEW}).
 *
 * <p><strong>Guard responsibility split (build spec §2 seam 2):</strong>
 * <ul>
 *   <li>INPATIENT-SIDE (before calling {@link #record}): the admission-IN-PROCESS gate
 *       (inpatient owns {@code AdmissionStatus}) — PENDING → "Admission not verified",
 *       signed-out → "Patient already signed off" (PatientServiceImpl.java:2564-2577).</li>
 *   <li>CLINICAL-SIDE (here): the GIVEN-prescription guard (prescription must exist and be
 *       {@code GIVEN} — "Prescription not picked from pharmacy", PatientServiceImpl.java:2544-2545)
 *       and the nurse-uid-present guard. Persists the dosing note + the 24h delete-window
 *       guard (clinical owns the entity).</li>
 * </ul>
 *
 * <p>NO MAR. This is the legacy free-text dosing-note path ONLY (Q1 / CR-07-MAR parked):
 * dosage/output/remark free-text, NO route, NO administeredAt, NO patientResponse. DELETE-only
 * within 24h; NO edit path.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>prescription-exists guard: PatientServiceImpl.java:2540-2543 ("Medical prescription
 *       detail not found")</li>
 *   <li>GIVEN guard: PatientServiceImpl.java:2544-2545 ("Prescription not picked from pharmacy")</li>
 *   <li>admission + nurse guard: PatientServiceImpl.java:2564-2577</li>
 *   <li>24h delete guard: PatientResource.java:3135-3138</li>
 *   <li>entity shape: PatientPrescriptionChart.java:34-82</li>
 * </ul>
 *
 * <p>inc-07 07b / ADR-0008 §1.
 */
@Service
@RequiredArgsConstructor
class PrescriptionChartPortImpl implements PrescriptionChartPort {

    private static final Logger log = LoggerFactory.getLogger(PrescriptionChartPortImpl.class);

    private static final String AUDIT_TYPE = "clinical.PatientPrescriptionChart";

    /** Verbatim legacy message — PatientServiceImpl.java:2541. */
    private static final String MSG_PRESCRIPTION_NOT_FOUND = "Medical prescription detail not found";
    /** Verbatim legacy message — PatientServiceImpl.java:2545. */
    private static final String MSG_NOT_GIVEN = "Prescription not picked from pharmacy";
    /** Nurse-uid guard (the inpatient dosing-note path requires a nurse). */
    private static final String MSG_NO_NURSE = "Nurse information not found";
    /** Verbatim legacy message — PatientResource.java:3135-3138. */
    private static final String MSG_DELETE_24H =
            "Could not delete record. only records not exceeding 24 hours can be deleted";
    private static final String MSG_NOT_FOUND = "Record not found";

    private final PatientPrescriptionChartRepository chartRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AuditRecorder auditRecorder;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public PrescriptionChartView record(RecordPrescriptionChartCommand cmd, TxAuditContext ctx) {
        // GIVEN guard (PatientServiceImpl.java:2540-2545): the prescription must exist and have
        // been dispensed by pharmacy (status GIVEN) before a dosing note can be charted.
        Prescription prescription = prescriptionRepository.findByUid(cmd.prescriptionUid())
                .orElseThrow(() -> new NotFoundException(MSG_PRESCRIPTION_NOT_FOUND));
        if (prescription.getStatus() != PrescriptionStatus.GIVEN) {
            throw new InvalidPatientOperationException(MSG_NOT_GIVEN);
        }
        // Nurse-uid guard (inpatient dosing-note path requires a nurse; :2564-2577).
        if (cmd.nurseUid() == null || cmd.nurseUid().isBlank()) {
            throw new InvalidPatientOperationException(MSG_NO_NURSE);
        }
        // The admission-IN-PROCESS gate is enforced INPATIENT-SIDE before this call (the
        // inpatient module owns AdmissionStatus). The command carries the resolved admissionUid.

        // Bug fix (NursingChartIT): patientUid was null — V36 NOT NULL constraint on
        // patient_prescription_charts.patient_uid. Resolve from the prescription's own
        // patient_uid (set at prescribe time, PatientServiceImpl.java:2544 context).
        PatientPrescriptionChart chart = PatientPrescriptionChart.createForAdmission(
                prescription, cmd.admissionUid(), prescription.getPatientUid(), cmd.nurseUid(),
                cmd.dosage(), cmd.output(), cmd.remark());

        chartRepository.save(chart);
        auditRecorder.record(AUDIT_TYPE, chart.getUid(), AuditAction.CREATE, ctx.actorUsername());

        log.debug("PrescriptionChartPortImpl: saved dosing note uid={} prescriptionUid={} admissionUid={}",
                chart.getUid(), cmd.prescriptionUid(), cmd.admissionUid());
        return toView(chart);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionChartView> findByAdmission(String admissionUid) {
        return chartRepository.findByAdmissionUidOrderByCreatedAtAsc(admissionUid)
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void delete24hWindow(String chartUid, TxAuditContext ctx) {
        PatientPrescriptionChart chart = chartRepository.findByUid(chartUid)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        // 24h DELETE-only guard (PatientResource.java:3135-3138).
        long hours = ChronoUnit.HOURS.between(chart.getCreatedAt(), ctx.timestamp());
        if (hours >= 24) {
            throw new InvalidPatientOperationException(MSG_DELETE_24H);
        }
        chartRepository.delete(chart);
        auditRecorder.record(AUDIT_TYPE, chartUid, AuditAction.DELETE, ctx.actorUsername());
    }

    private PrescriptionChartView toView(PatientPrescriptionChart chart) {
        return new PrescriptionChartView(
                chart.getUid(),
                chart.getPrescription().getUid(),
                chart.getDosage(),
                chart.getOutput(),
                chart.getRemark(),
                chart.getNurseUid(),
                chart.getCreatedAt());
    }
}
