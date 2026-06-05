package com.otapp.hmis.clinical.api;

import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;

/**
 * Published cross-module write+read seam for the {@code PatientConsumableChart} aggregate
 * (inc-07 07c-i, ADR-0008 §1).
 *
 * <p>The dependency edge is one-directional: {@code inpatient → clinical :: api}.
 * There is NO {@code clinical → inpatient} edge — clinical never calls back. No cycle.
 *
 * <p>The implementation ({@code ConsumableChartPortImpl}) is package-private in
 * {@code clinical.application}.
 *
 * <p><strong>Guard split:</strong> The admission-IN-PROCESS gate and the
 * consumable-registered guard are evaluated INPATIENT-SIDE before calling any method here.
 * The clinical-side guard (nurse-uid present) is enforced inside the implementation.
 * The billing charge is created CLINICAL-SIDE inside {@code recordConsumableChart}
 * (kind=MEDICINE, billItem="Medication", description="Consumable: <name>").
 *
 * <p>The stock decrement (CR-07-consumable-stock) is handled INPATIENT-SIDE via
 * {@link com.otapp.hmis.pharmacy.api.PharmacyStockDebit} AFTER the chart persist succeeds.
 * If stock is insufficient, the transaction rolls back before hitting the stock debit.
 *
 * <p>The 24-hour delete window guard is enforced clinical-side (clinical owns the entity).
 * The billing reversal (cancelCharge via billing::api) is the caller's responsibility:
 * the inpatient orchestrator calls {@code BillingCommands.cancelCharge("Canceled consumable")}
 * and then calls {@link #deleteConsumableChart24h} to remove the chart row.
 *
 * <p>Legacy citation: PatientServiceImpl.java:2250-2475 (savePatientConsumableChart);
 * PatientResource.java:3035-3085 (deleteConsumableChart).
 * inc-07 07c-i / CR-07-consumable-stock / CR-07-Q13-billing-display.
 */
public interface ConsumableChartPort {

    /**
     * Persist a consumable chart entry and create the MEDICINE bill (trichotomy: UNPAID /
     * COVERED / VERIFIED per inpatient billing engine).
     *
     * <p>Clinical-side guard: nurse-uid must be non-null.
     *
     * <p>Bill literals (CR-07-Q13-billing-display APPROVED):
     * {@code billItem="Medication"}, {@code description="Consumable: <medicineName>"}.
     *
     * @param cmd the consumable chart command
     * @param ctx transaction audit context (dayUid, actor, timestamp)
     * @return the created chart as a {@link ConsumableChartView}
     */
    ConsumableChartView recordConsumableChart(RecordConsumableChartCommand cmd, TxAuditContext ctx);

    /** Read all consumable charts for a given admission uid, oldest first. */
    List<ConsumableChartView> findConsumableChartsByAdmission(String admissionUid);

    /**
     * Delete a consumable chart entry within the 24-hour window.
     *
     * <p>ONLY deletes the chart row. The billing reversal (cancelCharge) is handled by the
     * inpatient orchestrator ({@code ConsumableChartService}) BEFORE calling this method.
     * The stock restore is also handled by the inpatient orchestrator AFTER calling this method.
     *
     * @param chartUid the ULID of the chart entry to delete
     * @param ctx      transaction audit context
     * @throws com.otapp.hmis.shared.error.NotFoundException if no entry with that uid exists
     * @throws com.otapp.hmis.shared.error.InvalidPatientOperationException if the 24h window
     *         has elapsed (verbatim: "Could not delete record. only records not exceeding
     *         24 hours can be deleted")
     */
    void deleteConsumableChart24h(String chartUid, TxAuditContext ctx);

    /**
     * Retrieve a consumable chart by its uid (for the delete path to get the bill uid and qty).
     *
     * @param chartUid the ULID of the chart
     * @return the chart view
     * @throws com.otapp.hmis.shared.error.NotFoundException if not found
     */
    ConsumableChartView findByUid(String chartUid);
}
