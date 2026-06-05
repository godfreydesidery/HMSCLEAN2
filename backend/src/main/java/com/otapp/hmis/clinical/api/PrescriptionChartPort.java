package com.otapp.hmis.clinical.api;

import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Published cross-module write+read seam for the {@code PatientPrescriptionChart} aggregate,
 * consumed by the {@code inpatient} module (inc-07 SEAM-2, ADR-0008 §1).
 *
 * <p>The dependency edge is one-directional: {@code inpatient → clinical :: api}.
 * There is NO {@code clinical → inpatient} edge — clinical never calls back into inpatient.
 * This preserves the no-module-cycle constraint (ADR-0008 §1).
 *
 * <p>The implementation ({@code PrescriptionChartPortImpl}) is package-private in
 * {@code clinical.application}. Other modules depend only on this interface from
 * {@code clinical.api} (Spring Modulith named-interface contract).
 *
 * <p><strong>Deferred implementation:</strong> the full write guards (prescription GIVEN check
 * at PatientServiceImpl.java:2544; exactly-one-encounter check; nurse-uid present check at
 * PatientServiceImpl.java:2564-2577) are built in inc-07 chunk 07b. The stub impl in
 * {@code PrescriptionChartPortImpl} throws {@code UnsupportedOperationException} until then.
 *
 * <p>The 24-hour delete-only window guard is enforced clinical-side because this module owns the
 * {@code PatientPrescriptionChart} entity. The inpatient module must NOT attempt to enforce this
 * guard itself.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>GIVEN guard: PatientServiceImpl.java:2544</li>
 *   <li>admission + nurse guard: PatientServiceImpl.java:2564-2577</li>
 *   <li>Entity shape: PatientPrescriptionChart.java:34-82</li>
 * </ul>
 *
 * <p>inc-07 SEAM-2 / ADR-0008 §1.
 */
public interface PrescriptionChartPort {

    /**
     * Record a drug-administration chart entry for an inpatient admission.
     *
     * <p>Enforces clinical-owned write guards (built in chunk 07b):
     * the linked prescription must be GIVEN (PatientServiceImpl.java:2544),
     * the admission must be IN-PROCESS, and a nurse uid must be supplied
     * (PatientServiceImpl.java:2564-2577).
     *
     * @param cmd the chart command (prescriptionUid, admissionUid, nurseUid, dosing fields)
     * @param ctx transaction audit context (actor, business day, timestamp)
     * @return the created chart entry as a {@link PrescriptionChartView}
     * @throws com.otapp.hmis.shared.error.NotFoundException           if the prescription uid
     *         does not exist
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if a write guard
     *         fails (built in chunk 07b)
     */
    PrescriptionChartView record(RecordPrescriptionChartCommand cmd, TxAuditContext ctx);

    /**
     * Read all chart entries bound to a given admission uid.
     *
     * @param admissionUid the loose uid of the admission
     * @return chart projections ordered by creation time ascending (empty if none)
     */
    List<PrescriptionChartView> findByAdmission(String admissionUid);

    /**
     * Delete a chart entry within the 24-hour edit window.
     *
     * <p>The 24-hour DELETE-only guard is enforced clinical-side (clinical owns the entity).
     * Attempts to delete a chart entry older than 24 hours will be rejected by the
     * implementation (built in chunk 07b).
     *
     * @param chartUid the ULID of the chart entry to delete
     * @param ctx      transaction audit context (actor, business day, timestamp)
     * @throws com.otapp.hmis.shared.error.NotFoundException           if no chart entry with
     *         that uid exists
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if the 24-hour
     *         window has elapsed (built in chunk 07b)
     */
    void delete24hWindow(String chartUid, TxAuditContext ctx);
}
