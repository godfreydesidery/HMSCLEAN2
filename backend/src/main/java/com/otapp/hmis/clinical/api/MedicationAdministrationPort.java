package com.otapp.hmis.clinical.api;

import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Published cross-module write+read seam for the {@code MedicationAdministration} (MAR) aggregate,
 * consumed by the {@code inpatient} module (inc-07 07d, CR-07-MAR, ADR-0008 §1).
 *
 * <p>The dependency edge is one-directional: {@code inpatient → clinical :: api}. There is NO
 * {@code clinical → inpatient} edge. The implementation
 * ({@code MedicationAdministrationPortImpl}) is package-private in {@code clinical.application}.
 *
 * <p><strong>NET-NEW — no legacy equivalent.</strong> MAR is additive over the free-text
 * dosing-note path ({@link PrescriptionChartPort}); both coexist. Unlike the dosing note there is
 * NO 24-hour delete window — a MAR entry is a closed-loop clinical-safety record (create + read
 * only; correcting an administration is a net-new concern intentionally left out of this scope).
 *
 * <p>Guard split:
 * <ul>
 *   <li>INPATIENT-SIDE (before {@link #record}): admission IN-PROCESS gate; {@code routeUid} is a
 *       registered ACTIVE route (RouteLookup).</li>
 *   <li>CLINICAL-SIDE (here): the prescription exists and is GIVEN; {@code nurseUid} present.</li>
 * </ul>
 */
public interface MedicationAdministrationPort {

    /**
     * Record a closed-loop medication administration against a GIVEN prescription.
     *
     * @param cmd the MAR command (prescription, admission, nurse, route, instant, dose, response)
     * @param ctx transaction audit context (actor, business day, timestamp)
     * @return the created MAR entry as a {@link MedicationAdministrationView}
     * @throws com.otapp.hmis.shared.error.NotFoundException                if the prescription uid
     *         does not exist
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if a clinical-side write
     *         guard fails (prescription not GIVEN, or nurse uid missing)
     */
    MedicationAdministrationView record(RecordMedicationAdministrationCommand cmd, TxAuditContext ctx);

    /**
     * Read all MAR entries bound to a given admission uid, oldest first.
     *
     * @param admissionUid the loose uid of the admission
     * @return MAR projections ordered by creation time ascending (empty if none)
     */
    List<MedicationAdministrationView> findByAdmission(String admissionUid);
}
