package com.otapp.hmis.registration.application;

import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.event.PatientAdmittedEvent;
import com.otapp.hmis.shared.event.PatientDeceasedEvent;
import com.otapp.hmis.shared.event.PatientDischargedEvent;
import com.otapp.hmis.shared.event.PatientInsuranceClearedEvent;
import com.otapp.hmis.shared.event.PatientReferredEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registration-side listener for OPD closure events published by the {@code clinical} module
 * (inc-05 C12 — cross-module event seam).
 *
 * <p><strong>Cross-module event seam design (C12 ADR — no cycle):</strong>
 * <ul>
 *   <li>Event types ({@link PatientDeceasedEvent}, {@link PatientInsuranceClearedEvent}) live in
 *       {@code shared.event} — visible to every module without creating a direct inter-module
 *       compile edge (the {@code shared} module is OPEN — ADR-0014 §1).</li>
 *   <li>{@code clinical} imports ONLY {@code shared} when publishing. It imports no type from
 *       {@code registration}. Therefore no clinical→registration compile edge exists.</li>
 *   <li>{@code registration} imports ONLY {@code shared} event types here. It imports no type
 *       from {@code clinical}. Therefore no registration→clinical edge is added by this listener.
 *       (The existing registration→clinical::api edge, for consultation booking, is unchanged.)</li>
 *   <li>Result: no cycle. {@code ApplicationModules.verify()} stays green.</li>
 * </ul>
 *
 * <p><strong>Transaction phase — {@code BEFORE_COMMIT}:</strong>
 * Both listeners use {@code @TransactionalEventListener(phase = BEFORE_COMMIT)}.
 * This means the listener executes WITHIN the outer transaction (the clinical service's
 * {@code @Transactional} method) immediately before the commit. The Patient mutation
 * therefore commits atomically with the clinical DeceasedNote/ReferralPlan state change:
 * if anything rolls back (e.g. optimistic lock failure on the Patient), the entire
 * operation rolls back, including the clinical note approval. This is the correct atomicity
 * requirement for safety-critical state changes (death recording, insurance clearing).
 *
 * <p><strong>Why not Spring Modulith's {@code @ApplicationModuleListener}?</strong>
 * Spring Modulith's {@code @ApplicationModuleListener} requires an {@code event_publication}
 * table that is not yet bootstrapped in this project. Plain Spring
 * {@code @TransactionalEventListener} achieves the same same-transaction atomicity without
 * requiring that infrastructure. This is documented as a known simplification — if
 * at-least-once delivery (retry on failure) is required in the future, migrating to Spring
 * Modulith's event publication mechanism is the path forward.
 *
 * <p><strong>PHI note:</strong>
 * The patientUid is a ULID (not a name, diagnosis, or financial identifier) and is safe to
 * include in structured log messages at DEBUG level. No PHI/PII is logged here.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Patient.changeType(DECEASED): PatientResource.java (deceased approval sets type=DECEASED)</li>
 *   <li>Patient.changePaymentType(CASH, null, ""): PatientResource.java (referral save clears insurance)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PatientClosureListener {

    private static final Logger log = LoggerFactory.getLogger(PatientClosureListener.class);

    private static final String ENTITY_TYPE  = "registration.Patient";
    private static final String TYPE_NOT_UPDATED = "type not updated";

    private final PatientRepository patientRepository;
    private final AuditRecorder auditRecorder;

    /**
     * Handle the {@link PatientDeceasedEvent}: set {@code Patient.type = DECEASED}.
     *
     * <p>Runs BEFORE_COMMIT in the clinical module's transaction, so the Patient type
     * change is atomic with the DeceasedNote approval.
     *
     * <p>If the patient is not found (e.g. data inconsistency), the event is silently logged
     * at WARN level and the method returns without throwing. The clinical-side state has already
     * been updated; failing here would roll back a correctly completed clinical operation.
     * This is the safest failure mode: the Patient type can be corrected via the
     * {@code change_type} endpoint if needed.
     *
     * @param event the deceased event carrying the patient uid
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPatientDeceased(PatientDeceasedEvent event) {
        log.debug("PatientClosureListener: handling PatientDeceasedEvent for patient uid={}",
                event.patientUid());

        patientRepository.findByUid(event.patientUid()).ifPresentOrElse(
                patient -> {
                    patient.changeType(PatientType.DECEASED);
                    // No explicit save() needed — the Patient is a managed JPA entity
                    // within the outer transaction; dirty-checking will flush the change.
                    // SEC-01: audit the Patient identity mutation with the REAL approving actor.
                    auditRecorder.record(ENTITY_TYPE, patient.getUid(),
                            AuditAction.UPDATE, event.actorUsername());
                    log.debug("PatientClosureListener: Patient {} marked DECEASED", event.patientUid());
                },
                () -> log.warn("PatientClosureListener: patient uid={} not found for deceased event; "
                        + TYPE_NOT_UPDATED, event.patientUid())
        );
    }

    /**
     * Handle the {@link PatientAdmittedEvent}: set {@code Patient.type = INPATIENT}.
     *
     * <p>Runs BEFORE_COMMIT in the inpatient module's doAdmission transaction, so the Patient
     * type change is atomic with the Admission/AdmissionBed creation.
     *
     * <p>Same failure-mode policy as {@link #onPatientDeceased}: WARN-and-return if patient
     * not found — never roll back a completed admission.
     *
     * <p>Legacy citation: PatientServiceImpl.java:1785 — {@code p.setType("INPATIENT")} was
     * inline in doAdmission. Extracted to this event handler to eliminate the
     * inpatient→registration compile cycle (inc-07 07a SEAM-A).
     *
     * @param event the admitted event carrying the patient uid and actor
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPatientAdmitted(PatientAdmittedEvent event) {
        log.debug("PatientClosureListener: handling PatientAdmittedEvent for patient uid={}",
                event.patientUid());

        patientRepository.findByUid(event.patientUid()).ifPresentOrElse(
                patient -> {
                    patient.changeType(PatientType.INPATIENT);
                    auditRecorder.record(ENTITY_TYPE, patient.getUid(),
                            AuditAction.UPDATE, event.actorUsername());
                    log.debug("PatientClosureListener: Patient {} marked INPATIENT", event.patientUid());
                },
                () -> log.warn("PatientClosureListener: patient uid={} not found for admitted event; "
                        + TYPE_NOT_UPDATED, event.patientUid())
        );
    }

    /**
     * Handle the {@link PatientDischargedEvent}: set {@code Patient.type = OUTPATIENT} and
     * clear insurance ({@code paymentType = CASH, insurancePlan = null, membershipNo = ""}).
     *
     * <p>Runs BEFORE_COMMIT in the inpatient module's discharge transaction, so the Patient
     * type reset is atomic with the Admission sign-out.
     *
     * <p>Same failure-mode policy as {@link #onPatientDeceased}: WARN-and-return if patient
     * not found — never roll back a completed discharge.
     *
     * <p>Legacy citation: discharge/referral/deceased summary completion resets Patient.type
     * to OUTPATIENT and clears insurance (inc-07 07a-3 — handler built now, publisher wired
     * in 07a-3).
     *
     * @param event the discharged event carrying the patient uid and actor
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPatientDischarged(PatientDischargedEvent event) {
        log.debug("PatientClosureListener: handling PatientDischargedEvent for patient uid={}",
                event.patientUid());

        patientRepository.findByUid(event.patientUid()).ifPresentOrElse(
                patient -> {
                    patient.changeType(PatientType.OUTPATIENT);
                    patient.changePaymentType(PaymentType.CASH, null, "");
                    auditRecorder.record(ENTITY_TYPE, patient.getUid(),
                            AuditAction.UPDATE, event.actorUsername());
                    log.debug("PatientClosureListener: Patient {} discharged → OUTPATIENT/CASH",
                            event.patientUid());
                },
                () -> log.warn("PatientClosureListener: patient uid={} not found for discharged event; "
                        + TYPE_NOT_UPDATED, event.patientUid())
        );
    }

    /**
     * Handle the {@link PatientReferredEvent}: set {@code Patient.type = OUTPATIENT} ONLY.
     *
     * <p>Referral asymmetry vs discharge (PatientResource.java:5626 vs :5378-5381):
     * the referral sign-out sets patient type=OUTPATIENT but does NOT clear insurance plan
     * or payment type. {@link PatientDischargedEvent} performs the full reset (type+CASH+null);
     * this handler performs the type-only reset.
     *
     * <p>Runs BEFORE_COMMIT in the inpatient module's referral-approve transaction, so the
     * Patient type change is atomic with the Admission sign-out.
     *
     * <p>Same failure-mode policy as {@link #onPatientDeceased}: WARN-and-return if patient
     * not found — never roll back a completed referral sign-out.
     *
     * <p>Legacy citation: PatientResource.java:5626 — referral approval sets patient
     * type=OUTPATIENT only (inc-07 07a-3). Asymmetry with discharge at :5378-5381 documented
     * in {@link PatientReferredEvent}.
     *
     * @param event the referred event carrying the patient uid and actor
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPatientReferred(PatientReferredEvent event) {
        log.debug("PatientClosureListener: handling PatientReferredEvent for patient uid={}",
                event.patientUid());

        patientRepository.findByUid(event.patientUid()).ifPresentOrElse(
                patient -> {
                    patient.changeType(PatientType.OUTPATIENT);
                    // NOTE: intentionally NO changePaymentType call — referral type-only reset
                    // (PatientResource.java:5626 asymmetry vs discharge :5378-5381).
                    auditRecorder.record(ENTITY_TYPE, patient.getUid(),
                            AuditAction.UPDATE, event.actorUsername());
                    log.debug("PatientClosureListener: Patient {} referred → OUTPATIENT (type only)",
                            event.patientUid());
                },
                () -> log.warn("PatientClosureListener: patient uid={} not found for referred event; "
                        + TYPE_NOT_UPDATED, event.patientUid())
        );
    }

    /**
     * Handle the {@link PatientInsuranceClearedEvent}: set {@code Patient.paymentType = CASH},
     * clearing the insurance plan and membership number.
     *
     * <p>Runs BEFORE_COMMIT in the clinical module's transaction, so the insurance clearing
     * is atomic with the ReferralPlan creation and consultation SIGNED_OUT transition.
     *
     * <p>Same failure-mode policy as {@link #onPatientDeceased}: if the patient is not found,
     * log at WARN and return without throwing.
     *
     * @param event the insurance-cleared event carrying the patient uid
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPatientInsuranceCleared(PatientInsuranceClearedEvent event) {
        log.debug("PatientClosureListener: handling PatientInsuranceClearedEvent for patient uid={}",
                event.patientUid());

        patientRepository.findByUid(event.patientUid()).ifPresentOrElse(
                patient -> {
                    patient.changePaymentType(PaymentType.CASH, null, "");
                    // SEC-01: audit the Patient insurance-clear mutation with the REAL approving actor.
                    auditRecorder.record(ENTITY_TYPE, patient.getUid(),
                            AuditAction.UPDATE, event.actorUsername());
                    log.debug("PatientClosureListener: Patient {} insurance cleared (→ CASH)",
                            event.patientUid());
                },
                () -> log.warn("PatientClosureListener: patient uid={} not found for insurance-cleared event; "
                        + "paymentType not updated", event.patientUid())
        );
    }
}
