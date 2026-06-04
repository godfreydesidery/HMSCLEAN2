package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.NonConsultationDto;
import com.otapp.hmis.shared.domain.TxAuditContext;

/**
 * Public intra-module boundary between {@code clinical.web} and {@code clinical.application}
 * for the walk-in (NonConsultation) track (inc-05 C4).
 *
 * <p>The web layer ({@link com.otapp.hmis.clinical.web.NonConsultationController}) cannot
 * reference the package-private {@link WalkInService} directly. This interface is the only
 * public type in {@code clinical.application} that the controller may depend on for walk-in
 * operations. The implementation remains package-private (mirroring
 * {@link ConsultationLifecyclePort} / {@link ConsultationLifecycleService}).
 *
 * <p>Spring wires the package-private impl via component scanning.
 */
public interface WalkInPort {

    /**
     * Get or create an IN_PROCESS walk-in encounter for the patient.
     *
     * <p>If the patient already has an IN_PROCESS NonConsultation, return it (idempotent reuse).
     * Otherwise create a new one IN_PROCESS.
     *
     * <p>This is the primary entry-point called by the C7-C9 order-save paths (lab, radiology,
     * procedure) for OUTSIDER patients. Exposed here so the controller can exercise the logic
     * and the order-save wiring in C7-C9 has a clean API to call.
     *
     * @param patientUid       loose uid of the patient
     * @param visitUid         loose uid of the associated visit
     * @param paymentType      CASH, INSURANCE, or '' (default)
     * @param membershipNo     insurance membership number (empty for CASH)
     * @param insurancePlanUid loose uid of the insurance plan (null for CASH)
     * @param ctx              transaction audit context
     * @return the existing or newly-created IN_PROCESS NonConsultationDto
     */
    NonConsultationDto getOrCreateInProcess(String patientUid, String visitUid,
                                            String paymentType, String membershipNo,
                                            String insurancePlanUid, TxAuditContext ctx);

    /**
     * Sign out an IN_PROCESS walk-in encounter: IN_PROCESS → SIGNED_OUT.
     *
     * <p>Guard: status must be IN_PROCESS; else 422.
     *
     * @param nonConsultationUid the ULID of the NonConsultation
     * @param ctx                transaction audit context
     * @return the updated NonConsultationDto
     */
    NonConsultationDto signOut(String nonConsultationUid, TxAuditContext ctx);

    /**
     * Read a single non-consultation by ULID.
     *
     * @param uid the ULID of the NonConsultation
     * @return the NonConsultationDto
     */
    NonConsultationDto getByUid(String uid);
}
