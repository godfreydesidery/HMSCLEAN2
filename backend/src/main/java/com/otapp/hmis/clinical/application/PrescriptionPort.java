package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.IssueRequest;
import com.otapp.hmis.clinical.application.dto.PrescriptionBatchDto;
import com.otapp.hmis.clinical.application.dto.PrescriptionBatchRequest;
import com.otapp.hmis.clinical.application.dto.PrescriptionDto;
import com.otapp.hmis.clinical.application.dto.PrescriptionRequest;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Public intra-module boundary for the Prescription aggregate lifecycle (inc-05 C10).
 *
 * <p>The web layer ({@link com.otapp.hmis.clinical.web.PrescriptionController}) cannot
 * reference the package-private {@link PrescriptionService} directly. This interface is the
 * only public type in {@code clinical.application} that the controller may depend on for
 * prescription operations.
 *
 * <p>Mirrors the pattern of {@link LabTestPort}.
 */
public interface PrescriptionPort {

    // -------------------------------------------------------------------------
    // Prescribe (save_prescription)
    // -------------------------------------------------------------------------

    /**
     * Prescribe a medicine on a consultation (outpatient path).
     *
     * <p>Guards: consultation exists; medicine exists; no duplicate medicine on this
     * consultation; consultation is OUTPATIENT (status IN_PROCESS). Creates billing charge
     * (kind=MEDICINE, serviceUid=medicineUid, qty=prescribed qty).
     * Returns NOT-GIVEN prescription with balance=qty, issued=0, alerts=[].
     *
     * @param consultationUid the ULID of the owning consultation
     * @param request         prescription details
     * @param ctx             transaction audit context
     * @return the created PrescriptionDto (status=NOT-GIVEN, alerts=[])
     */
    PrescriptionDto prescribeForConsultation(String consultationUid,
                                              PrescriptionRequest request,
                                              TxAuditContext ctx);

    /**
     * Prescribe a medicine on a non-consultation (OUTSIDER/walk-in path).
     *
     * <p>Guards: non-consultation exists; medicine exists; no duplicate medicine on this
     * non-consultation (CR-INC05-05 corrected check — NOT the legacy NPE path).
     * Creates billing charge (kind=MEDICINE). Returns NOT-GIVEN prescription.
     *
     * @param nonConsultationUid the ULID of the owning non-consultation
     * @param request            prescription details
     * @param ctx                transaction audit context
     * @return the created PrescriptionDto (status=NOT-GIVEN, alerts=[])
     */
    PrescriptionDto prescribeForNonConsultation(String nonConsultationUid,
                                                 PrescriptionRequest request,
                                                 TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Dispense (issueMedicine)
    // -------------------------------------------------------------------------

    /**
     * Dispense a prescription: NOT-GIVEN → GIVEN.
     *
     * <p>Guards (PatientResource.java:3217-3245):
     * <ul>
     *   <li>Status must be NOT-GIVEN ("not a pending prescription").</li>
     *   <li>issued must be > 0 ("Invalid issue value").</li>
     *   <li>issued must not exceed balance ("Invalid issue value").</li>
     *   <li>issued must equal the full prescribed qty ("You can only issue the prescribed qty").</li>
     *   <li>STOCK check DEFERRED (pharmacy module not yet built).</li>
     * </ul>
     * On success: issued=qty, balance=0, issuePharmacyUid set, status GIVEN, approved_* stamped.
     *
     * @param prescriptionUid the ULID of the prescription to dispense
     * @param request         issue request (issued qty + optional pharmacy uid)
     * @param ctx             transaction audit context
     * @return the updated PrescriptionDto (status=GIVEN)
     */
    PrescriptionDto issueMedicine(String prescriptionUid, IssueRequest request,
                                  TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Hard-delete a NOT-GIVEN prescription.
     *
     * <p>Guard: status must be NOT-GIVEN (else 422 "Only a pending prescription can be deleted").
     * Credit-note for paid bills: DEFERRED (same pattern as LabTest/Radiology/Procedure).
     *
     * @param prescriptionUid the ULID of the prescription to delete
     * @param ctx             transaction audit context
     */
    void delete(String prescriptionUid, TxAuditContext ctx);

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** Get a prescription by ULID. */
    PrescriptionDto getByUid(String prescriptionUid);

    /**
     * All prescriptions for a consultation, ordered by creation time ascending.
     *
     * @param consultationUid the ULID of the consultation
     * @return prescriptions for this consultation, oldest first
     */
    List<PrescriptionDto> listByConsultation(String consultationUid);

    /**
     * All prescriptions for a patient (by uid), ordered by creation time descending.
     *
     * @param patientUid the ULID of the patient
     * @return prescriptions for this patient, newest first
     */
    List<PrescriptionDto> byPatient(String patientUid);

    /**
     * Pharmacy dispense worklist — all NOT-GIVEN prescriptions, ordered oldest first (FIFO).
     *
     * <p>Returns all NOT-GIVEN prescriptions regardless of settled flag (the pharmacist
     * validates payment physically before dispensing).
     *
     * @return pharmacy worklist (NOT-GIVEN prescriptions)
     */
    List<PrescriptionDto> pharmacyWorklist();

    // -------------------------------------------------------------------------
    // Prescription batches
    // -------------------------------------------------------------------------

    /**
     * Add a batch traceability record to a prescription.
     *
     * @param prescriptionUid the ULID of the parent prescription
     * @param request         batch details (no, dates, qty)
     * @param ctx             transaction audit context
     * @return the created PrescriptionBatchDto
     */
    PrescriptionBatchDto addBatch(String prescriptionUid, PrescriptionBatchRequest request,
                                  TxAuditContext ctx);

    /**
     * List all batch records for a prescription, ordered by creation time ascending.
     *
     * @param prescriptionUid the ULID of the prescription
     * @return batches for this prescription, oldest first
     */
    List<PrescriptionBatchDto> listBatches(String prescriptionUid);
}
