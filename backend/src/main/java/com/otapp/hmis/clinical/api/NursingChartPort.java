package com.otapp.hmis.clinical.api;

import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Published cross-module write+read seam for the four nursing chart aggregates
 * (PatientNursingChart, PatientNursingCarePlan, PatientNursingProgressNote,
 * PatientDressingChart), consumed by the {@code inpatient} module (inc-07 07b,
 * AC-07B-NCA-01..AC-07B-DRS-08, ADR-0008 §1).
 *
 * <p>The dependency edge is one-directional: {@code inpatient → clinical :: api}.
 * There is NO {@code clinical → inpatient} edge — clinical never calls back into inpatient.
 * This preserves the no-module-cycle constraint (ADR-0008 §1).
 *
 * <p>The implementation ({@code NursingChartPortImpl}) is package-private in
 * {@code clinical.application}. Other modules depend only on this interface from
 * {@code clinical.api} (Spring Modulith named-interface contract).
 *
 * <p><strong>Guard split (mirrors PrescriptionChartPort / SEAM-2 pattern):</strong>
 * The admission-IN-PROCESS gate is evaluated INPATIENT-SIDE (inpatient owns AdmissionStatus)
 * BEFORE the call to any method here. The clinical-side guards (nurse-uid present, context
 * exclusivity, dressing-registered) are enforced inside the implementation.
 *
 * <p>Each chart type has a save + findByAdmission + delete24hWindow method set.
 * The 24-hour delete-only window guard is enforced clinical-side (clinical owns the entity).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>PatientNursingChart guard + save: PatientServiceImpl.java:2593-2643</li>
 *   <li>PatientNursingCarePlan guard + save: PatientServiceImpl.java:2593-2643</li>
 *   <li>PatientNursingProgressNote guard + save: PatientServiceImpl.java:2647-2698</li>
 *   <li>PatientDressingChart guard + save: PatientServiceImpl.java:2078-2245</li>
 *   <li>24h delete guard: PatientResource.java:3135-3138 (nursing_chart/care_plan/progress_note)</li>
 * </ul>
 *
 * <p>inc-07 07b / ADR-0008 §1.
 */
public interface NursingChartPort {

    // =========================================================================
    // PatientNursingChart
    // =========================================================================

    /**
     * Record a nursing observation chart entry for an inpatient admission.
     *
     * <p>Enforces clinical-owned guards: nurse-uid must be present; exactly one context
     * (admissionUid non-null, consultation/nonConsultation rejected with verbatim legacy
     * messages).
     *
     * @param cmd the chart command
     * @param ctx transaction audit context
     * @return the created chart as a {@link NursingChartView}
     */
    NursingChartView recordNursingChart(RecordNursingChartCommand cmd, TxAuditContext ctx);

    /** Read all nursing chart entries for a given admission uid, oldest first. */
    List<NursingChartView> findNursingChartsByAdmission(String admissionUid);

    /**
     * Delete a nursing chart entry within the 24-hour window.
     *
     * @param chartUid the ULID of the chart entry to delete
     * @param ctx      transaction audit context
     * @throws com.otapp.hmis.shared.error.NotFoundException if no entry with that uid exists
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if the 24h window
     *         has elapsed (AC-07B-NCA-07)
     */
    void deleteNursingChart24h(String chartUid, TxAuditContext ctx);

    // =========================================================================
    // PatientNursingCarePlan
    // =========================================================================

    /**
     * Record a nursing care plan entry for an inpatient admission.
     *
     * @param cmd the care plan command
     * @param ctx transaction audit context
     * @return the created care plan as a {@link NursingCarePlanView}
     */
    NursingCarePlanView recordNursingCarePlan(RecordNursingCarePlanCommand cmd, TxAuditContext ctx);

    /** Read all nursing care plans for a given admission uid, oldest first. */
    List<NursingCarePlanView> findNursingCarePlansByAdmission(String admissionUid);

    /**
     * Delete a nursing care plan within the 24-hour window.
     *
     * @param carePlanUid the ULID of the care plan to delete
     * @param ctx         transaction audit context
     */
    void deleteNursingCarePlan24h(String carePlanUid, TxAuditContext ctx);

    // =========================================================================
    // PatientNursingProgressNote
    // =========================================================================

    /**
     * Record a nursing progress note for an inpatient admission.
     *
     * @param cmd the progress note command
     * @param ctx transaction audit context
     * @return the created note as a {@link NursingProgressNoteView}
     */
    NursingProgressNoteView recordProgressNote(RecordProgressNoteCommand cmd, TxAuditContext ctx);

    /** Read all progress notes for a given admission uid, oldest first. */
    List<NursingProgressNoteView> findProgressNotesByAdmission(String admissionUid);

    /**
     * Delete a progress note within the 24-hour window.
     *
     * @param noteUid the ULID of the note to delete
     * @param ctx     transaction audit context
     */
    void deleteProgressNote24h(String noteUid, TxAuditContext ctx);

    // =========================================================================
    // PatientDressingChart
    // =========================================================================

    /**
     * Record a dressing billing chart entry for an inpatient admission.
     *
     * <p>The dressing bill (PROCEDURE kind) is created via {@code BillingCommands.recordClinicalCharge}.
     * The billing engine determines the status trichotomy (UNPAID / COVERED / VERIFIED)
     * based on insurance coverage and admission context (AC-07B-DRS-03..05).
     *
     * <p>The dressing-registered guard (AC-07B-DRS-02) is enforced INPATIENT-SIDE via
     * {@code DressingLookup.isDressing} before calling this method.
     *
     * @param cmd the dressing chart command
     * @param ctx transaction audit context
     * @return the created dressing chart as a {@link DressingChartView}
     */
    DressingChartView recordDressingChart(RecordDressingChartCommand cmd, TxAuditContext ctx);

    /** Read all dressing charts for a given admission uid, oldest first. */
    List<DressingChartView> findDressingChartsByAdmission(String admissionUid);

    /**
     * Delete a dressing chart within the 24-hour window (+ billing reversal).
     *
     * <p>Reversal: if the associated bill has a RECEIVED payment detail, raises a PENDING
     * PatientCreditNote; deletes PatientInvoiceDetail (+ parent invoice if empty); deletes
     * the bill; deletes the chart. (AC-07B-DRS-06).
     *
     * @param chartUid the ULID of the dressing chart to delete
     * @param ctx      transaction audit context
     */
    void deleteDressingChart24h(String chartUid, TxAuditContext ctx);
}
