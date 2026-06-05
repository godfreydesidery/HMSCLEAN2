package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.MedicationAdministrationPort;
import com.otapp.hmis.clinical.api.MedicationAdministrationView;
import com.otapp.hmis.clinical.api.RecordMedicationAdministrationCommand;
import com.otapp.hmis.clinical.domain.MedicationAdministration;
import com.otapp.hmis.clinical.domain.MedicationAdministrationRepository;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionRepository;
import com.otapp.hmis.clinical.domain.PrescriptionStatus;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link MedicationAdministrationPort} (inc-07 07d, CR-07-MAR,
 * ADR-0008 §1) — the closed-loop MAR write+read path.
 *
 * <p>Runs in the caller's transaction ({@code Propagation.REQUIRED}; no {@code @Async}/
 * {@code REQUIRES_NEW}). Mirrors {@link PrescriptionChartPortImpl}'s clinical-side guards
 * (GIVEN prescription + nurse uid) but persists a structured MAR entry rather than a free-text
 * dosing note. The admission IN-PROCESS gate and the route-active validation are enforced
 * inpatient-side before this call.
 *
 * <p>NET-NEW — no legacy citation. MAR ACs are net-new acceptance tests, not golden-master parity.
 * Reuses the verbatim GIVEN-guard messages from the dosing-note path for consistency.
 */
@Service
@RequiredArgsConstructor
class MedicationAdministrationPortImpl implements MedicationAdministrationPort {

    private static final Logger log = LoggerFactory.getLogger(MedicationAdministrationPortImpl.class);

    private static final String AUDIT_TYPE = "clinical.MedicationAdministration";

    /** Verbatim legacy message reused from the dosing-note path — PatientServiceImpl.java:2541. */
    private static final String MSG_PRESCRIPTION_NOT_FOUND = "Medical prescription detail not found";
    /** Verbatim legacy message reused from the dosing-note path — PatientServiceImpl.java:2545. */
    private static final String MSG_NOT_GIVEN = "Prescription not picked from pharmacy";
    /** Nurse-uid guard (the inpatient administration path requires a nurse). */
    private static final String MSG_NO_NURSE = "Nurse information not found";

    private final MedicationAdministrationRepository marRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AuditRecorder auditRecorder;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public MedicationAdministrationView record(RecordMedicationAdministrationCommand cmd,
                                               TxAuditContext ctx) {
        // GIVEN guard: the prescription must exist and have been dispensed (status GIVEN) before
        // an administration can be charted against it.
        Prescription prescription = prescriptionRepository.findByUid(cmd.prescriptionUid())
                .orElseThrow(() -> new NotFoundException(MSG_PRESCRIPTION_NOT_FOUND));
        if (prescription.getStatus() != PrescriptionStatus.GIVEN) {
            throw new InvalidPatientOperationException(MSG_NOT_GIVEN);
        }
        // Nurse-uid guard (the inpatient administration path requires a nurse).
        if (cmd.nurseUid() == null || cmd.nurseUid().isBlank()) {
            throw new InvalidPatientOperationException(MSG_NO_NURSE);
        }
        // The admission-IN-PROCESS gate and the route-active validation are enforced INPATIENT-SIDE
        // before this call (inpatient owns AdmissionStatus and consumes RouteLookup).

        MedicationAdministration mar = MedicationAdministration.create(
                prescription, cmd.admissionUid(), prescription.getPatientUid(), cmd.nurseUid(),
                cmd.routeUid(), cmd.administeredAt(), cmd.doseGiven(), cmd.patientResponse(),
                ctx.dayUid());

        marRepository.save(mar);
        auditRecorder.record(AUDIT_TYPE, mar.getUid(), AuditAction.CREATE, ctx.actorUsername());

        log.debug("MedicationAdministrationPortImpl: saved MAR uid={} prescriptionUid={} admissionUid={}",
                mar.getUid(), cmd.prescriptionUid(), cmd.admissionUid());
        return toView(mar);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MedicationAdministrationView> findByAdmission(String admissionUid) {
        return marRepository.findByAdmissionUidOrderByCreatedAtAsc(admissionUid)
                .stream().map(this::toView).toList();
    }

    private MedicationAdministrationView toView(MedicationAdministration mar) {
        return new MedicationAdministrationView(
                mar.getUid(),
                mar.getPrescription().getUid(),
                mar.getAdmissionUid(),
                mar.getNurseUid(),
                mar.getRouteUid(),
                mar.getAdministeredAt(),
                mar.getDoseGiven(),
                mar.getPatientResponse(),
                mar.getCreatedAt());
    }
}
