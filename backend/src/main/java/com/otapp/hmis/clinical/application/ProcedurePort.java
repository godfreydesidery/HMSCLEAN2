package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.ProcedureDto;
import com.otapp.hmis.clinical.application.dto.ProcedureNoteRequest;
import com.otapp.hmis.clinical.application.dto.ProcedureOrderRequest;
import com.otapp.hmis.clinical.application.dto.ProcedureUpdateRequest;
import com.otapp.hmis.clinical.domain.ProcedureStatus;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Public intra-module boundary for the Procedure aggregate lifecycle (inc-05 C9).
 *
 * <p>The web layer ({@link com.otapp.hmis.clinical.web.ProcedureController}) cannot reference
 * the package-private {@link ProcedureService} directly. This interface is the only public type
 * in {@code clinical.application} that the controller may depend on for procedure operations.
 *
 * <p>Mirrors the pattern of {@link LabTestPort} (C7) and {@link RadiologyPort} (C8) with the
 * procedure-specific differences:
 * <ul>
 *   <li>SIMPLER state machine: NO reject endpoint, NO hold endpoint, NO collect endpoint.</li>
 *   <li>add_note replaces verify — it is ACCEPTED → VERIFIED and carries the settlement gate.</li>
 *   <li>update() edits fields without status change (when status == ACCEPTED).</li>
 *   <li>NO attachments (no child attachment table for procedures in V26).</li>
 *   <li>The add_note transition IS the in-method settlement gate (PatientResource.java:3408-3414).</li>
 * </ul>
 *
 * <p>Endpoints exposed: order (consultation + non-consultation), get-by-uid, accept,
 * add-note (→ VERIFIED + settlement gate), update (ACCEPTED, no status change), delete (PENDING),
 * worklist, by-patient, list-by-consultation.
 *
 * <p><strong>NO approve, NO reject, NO hold, NO collect methods.</strong>
 */
public interface ProcedurePort {

    // -------------------------------------------------------------------------
    // Order creation
    // -------------------------------------------------------------------------

    /**
     * Order a clinical procedure on a consultation (outpatient path).
     *
     * <p>Guards: consultation exists; procedureType exists; no duplicate type on this
     * consultation (422). Creates billing charge (kind=PROCEDURE, serviceUid=procedureTypeUid).
     * Returns PENDING order with settled flag set from the charge result.
     *
     * @param consultationUid the ULID of the owning consultation
     * @param request         order details
     * @param ctx             transaction audit context
     * @return the created ProcedureDto (status=PENDING)
     */
    ProcedureDto orderForConsultation(String consultationUid, ProcedureOrderRequest request,
                                      TxAuditContext ctx);

    /**
     * Order a clinical procedure on a non-consultation (OUTSIDER/walk-in path).
     *
     * <p>Guards: non-consultation exists; procedureType exists; no duplicate type on this
     * non-consultation (422). Creates billing charge.
     *
     * @param nonConsultationUid the ULID of the owning non-consultation
     * @param request            order details
     * @param ctx                transaction audit context
     * @return the created ProcedureDto (status=PENDING)
     */
    ProcedureDto orderForNonConsultation(String nonConsultationUid, ProcedureOrderRequest request,
                                         TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    /**
     * Accept: PENDING | REJECTED → ACCEPTED.
     *
     * <p>Guard enforced in service. NO bill re-check.
     * REJECTED → ACCEPTED guard is reproduced verbatim (though REJECTED is unreachable at runtime).
     */
    ProcedureDto accept(String procedureUid, TxAuditContext ctx);

    /**
     * Add procedure note and transition to VERIFIED: ACCEPTED → VERIFIED.
     *
     * <p><strong>THE DISTINCTIVE SETTLEMENT GATE (PatientResource.java:3408-3414):</strong>
     * This transition requires:
     * <ol>
     *   <li>Status == ACCEPTED (else 422 "Please accept the procedure first")</li>
     *   <li>Note non-blank (validated by Bean Validation on the request record)</li>
     *   <li>settled == true (else 422 "Could not add procedure note. Payment not verified")</li>
     * </ol>
     * This is the ONE place in the order family that gates on the settled flag mid-transition.
     */
    ProcedureDto addNote(String procedureUid, ProcedureNoteRequest request, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Update (no status change) — ACCEPTED gate
    // -------------------------------------------------------------------------

    /**
     * Update procedure fields without status change.
     *
     * <p>Allowed when status == ACCEPTED (PatientResource.java:4060-4061). Does NOT require settled.
     * Guard enforced in service.
     */
    ProcedureDto update(String procedureUid, ProcedureUpdateRequest request, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Hard-delete a PENDING procedure order.
     *
     * <p>Only allowed when status == PENDING (else 422).
     * Credit-note for paid bills: DEFERRED (same pattern as LabTest/Radiology).
     *
     * @param procedureUid the ULID of the procedure order to delete
     * @param ctx          transaction audit context
     */
    void delete(String procedureUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** Get a procedure order by ULID. Returns 404 if not found. */
    ProcedureDto getByUid(String procedureUid);

    /**
     * Procedure worklist — settled orders in actionable statuses {PENDING, ACCEPTED}.
     *
     * <p>Outpatient + outsider only (no inpatient procedure worklist in legacy).
     *
     * @param statusFilter optional status filter (null = all actionable statuses)
     * @return the worklist
     */
    List<ProcedureDto> worklist(ProcedureStatus statusFilter);

    /**
     * All procedure orders for a patient (by uid), optionally filtered by status.
     *
     * <p><strong>DEFERRED (CR-INC05-15):</strong> The legacy by-patient query excludes
     * admission-scoped procedures. This method returns all procedures for the patient since
     * no admission procedures exist in C9.
     *
     * @param patientUid   the patient ULID
     * @param statusFilter optional status filter (null = all statuses)
     * @return procedure orders for this patient
     */
    List<ProcedureDto> byPatient(String patientUid, ProcedureStatus statusFilter);

    /**
     * All procedure orders for a consultation, ordered by creation time ascending.
     *
     * @param consultationUid the ULID of the consultation
     * @return procedure orders for this consultation, oldest first
     */
    List<ProcedureDto> listByConsultation(String consultationUid);
}
