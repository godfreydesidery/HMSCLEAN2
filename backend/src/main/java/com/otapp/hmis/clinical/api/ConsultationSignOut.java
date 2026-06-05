package com.otapp.hmis.clinical.api;

import com.otapp.hmis.shared.domain.TxAuditContext;

/**
 * Published write seam: sign out a patient's open OPD consultations on inpatient admission.
 *
 * <p>When a patient is admitted to a ward (inpatient module), any PENDING or IN_PROCESS
 * outpatient consultations must be transitioned to SIGNED_OUT. This seam is the module
 * boundary through which the {@code inpatient} module triggers that side-effect without
 * importing the {@code clinical.domain} or {@code clinical.application} packages.
 *
 * <p><strong>Legacy citation:</strong> {@code PatientServiceImpl.java:1950-1963} — in the
 * no-top-up insurance admit-activation branch (diff &le; 0), the legacy code calls
 * {@code consultationRepository.findAllByPatientAndStatusIn(patient, ["PENDING", "IN-PROCESS"])}
 * and sets each consultation's status to "SIGNED-OUT" before setting the admission status to
 * "IN-PROCESS" and the ward bed status to "OCCUPIED".
 *
 * <p><strong>CASH path:</strong> {@code PatientBillResource.java:352-364} — paying the ward-bed
 * bill also calls {@code consultationRepository.findAllByPatientAndStatus(patient, "IN-PROCESS")}
 * (IN_PROCESS only; PENDING not included) and signs those out. The cash-path sign-out is wired
 * via {@link #signOutInProcessConsultations(String, TxAuditContext)}, called by
 * {@code AdmissionSettlementListener} on the {@code BEFORE_COMMIT} phase of the billing
 * payment transaction.
 *
 * <p><strong>Module boundary (ADR-0008 §6, ADR-0022 D5):</strong>
 * <ul>
 *   <li>This interface is published in {@code clinical.api} — accessible to {@code inpatient}
 *       which already declares {@code clinical::api} as an allowed dependency.</li>
 *   <li>Clinical owns the write; inpatient calls down through this port. Same direction as
 *       {@link PrescriptionChartPort} — no new architectural decision.</li>
 *   <li>{@code ApplicationModules.verify()} remains green: no reverse edge is introduced.</li>
 * </ul>
 *
 * <p><strong>Transaction contract:</strong>
 * Both methods use {@code Propagation.REQUIRED} — they join the caller's transaction.
 * <ul>
 *   <li>{@link #signOutOpenConsultations} joins the {@code AdmissionService.doAdmission}
 *       transaction (insurance no-top-up activate-at-admit branch).</li>
 *   <li>{@link #signOutInProcessConsultations} joins the billing payment transaction
 *       via {@code AdmissionSettlementListener} (BEFORE_COMMIT phase).</li>
 * </ul>
 *
 * <p>Implementation is package-private in {@code clinical.application.ConsultationSignOutImpl}.
 */
public interface ConsultationSignOut {

    /**
     * Sign out (status → SIGNED_OUT) all PENDING and IN_PROCESS consultations for the patient.
     *
     * <p>Reproduces the legacy admit-activation side-effect at
     * {@code PatientServiceImpl.java:1951-1958}: when the no-top-up insurance path activates
     * the admission IN-PROCESS at admit time, any open OPD consultations are signed out first.
     * The sign-out order matches the legacy: consultations signed out, then admission
     * IN-PROCESS, then bed OCCUPIED (PatientServiceImpl.java:1958-1962).
     *
     * <p>Runs in the caller's transaction ({@code Propagation.REQUIRED}).
     * Each consultation that is transitioned is individually audited via {@link
     * com.otapp.hmis.shared.audit.AuditRecorder}.
     *
     * @param patientUid the ULID of the patient being admitted
     * @param ctx        the transaction audit context (dayUid, actor, timestamp)
     */
    void signOutOpenConsultations(String patientUid, TxAuditContext ctx);

    /**
     * Sign out (status → SIGNED_OUT) all IN_PROCESS consultations for the patient.
     *
     * <p>Reproduces the cash-payment-driven sign-out at
     * {@code PatientBillResource.java:353-364}: when paying the ward-bed CASH bill activates
     * the admission IN-PROCESS, only IN_PROCESS (not PENDING) consultations are signed out.
     * The legacy {@code findAllByPatientAndStatus(patient, "IN-PROCESS")} call confirms
     * that the CASH path uses a single-status fetch — narrower than the insurance path.
     *
     * <p>Runs in the caller's transaction ({@code Propagation.REQUIRED}).
     * Each consultation that is transitioned is individually audited.
     *
     * @param patientUid the ULID of the patient whose admission has just been activated
     * @param ctx        the transaction audit context (actor will be {@code null} for the
     *                   settlement listener path — audit records the system actor)
     */
    void signOutInProcessConsultations(String patientUid, TxAuditContext ctx);
}
