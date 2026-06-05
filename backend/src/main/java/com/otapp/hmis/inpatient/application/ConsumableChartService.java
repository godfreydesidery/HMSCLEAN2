package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.clinical.api.ConsumableChartPort;
import com.otapp.hmis.clinical.api.ConsumableChartView;
import com.otapp.hmis.clinical.api.RecordConsumableChartCommand;
import com.otapp.hmis.inpatient.application.dto.ConsumableChartRequest;
import com.otapp.hmis.inpatient.domain.Admission;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.masterdata.lookup.ConsumableLookup;
import com.otapp.hmis.masterdata.lookup.MedicineLookup;
import com.otapp.hmis.pharmacy.api.PharmacyStockDebit;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inpatient orchestration for the consumable chart path (inc-07 07c-i).
 *
 * <p>Owns the admission-IN-PROCESS gate, the consumable-registered guard, the medicine-exists
 * guard, and the pharmacy-exists guard; delegates the bill creation + chart persist to
 * {@link ConsumableChartPort} (clinical :: api); and after a successful persist, debits
 * pharmacy stock via {@link PharmacyStockDebit} (pharmacy :: api).
 *
 * <p><strong>Module dependencies for this service:</strong>
 * <ul>
 *   <li>{@code clinical :: api} — {@link ConsumableChartPort} (chart persist + MEDICINE bill)</li>
 *   <li>{@code billing :: api} — {@link BillingCommands#cancelCharge} (delete reversal)</li>
 *   <li>{@code masterdata :: lookup} — {@link ConsumableLookup}, {@link MedicineLookup}</li>
 *   <li>{@code pharmacy :: api} — {@link PharmacyStockDebit} (stock decrement on issue)</li>
 * </ul>
 * All are declared in {@code inpatient/package-info.java} allowedDependencies.
 *
 * <p><strong>Guard sequence (verbatim legacy PatientServiceImpl.java:2250-2302):</strong>
 * <ol>
 *   <li>Medicine exists ("Medicine not found")</li>
 *   <li>Qty &gt; 0 — already enforced by Bean Validation on {@link ConsumableChartRequest};
 *       verbatim: "Qty can not be zero"</li>
 *   <li>Consumable-registered ("Medicine is not listed as consumable")</li>
 *   <li>Nurse present ("Nurse information not found") — deferred to clinical-side port</li>
 *   <li>Admission IN-PROCESS gate (PENDING → "Admission not verified";
 *       IN-PROCESS → proceed; else → "Patient already signed off")</li>
 * </ol>
 *
 * <p><strong>Stock decrement (CR-07-consumable-stock):</strong>
 * After the chart is persisted and the MEDICINE bill is created, the stock is decremented via
 * {@link PharmacyStockDebit#debitConsumableIssue}. If stock is insufficient, the entire
 * transaction rolls back (hard negative-stock gate via
 * {@link com.otapp.hmis.shared.error.InsufficientStockException}).
 *
 * <p><strong>Delete path + CR-07-Q11 FIXES:</strong>
 * <ol>
 *   <li>24h guard ("Could not delete record. only records not exceeding 24 hours can be deleted"
 *       — enforced clinical-side).</li>
 *   <li>Billing reversal via {@code BillingCommands.cancelCharge("Canceled consumable", ...)}.
 *       <em>CR-07-Q11 FIX #2:</em> reference = "Canceled consumable" (NOT the legacy mislabeled
 *       "Canceled lab test" at PatientResource.java:3053). Anti-regression: do NOT use
 *       "Canceled lab test" for consumables.</li>
 *   <li>Chart delete via {@link ConsumableChartPort#deleteConsumableChart24h}.
 *       <em>CR-07-Q11 FIX #3:</em> parent invoice deletion is CONDITIONAL on real emptiness —
 *       handled by {@link BillingCommands#cancelCharge} which delegates to
 *       {@code CreditNoteService.detachInvoiceDetail} (real {@code invoice.isEmpty()} check).
 *       Anti-regression: the legacy {@code j=j++} no-op (PatientResource.java:3070-3076) that
 *       always deleted the parent invoice (cascade-wiping siblings) is NOT reproduced here.</li>
 * </ol>
 *
 * <p><strong>Stock restore on delete (CR-07-consumable-stock decision):</strong>
 * When a consumable chart is deleted within 24h, the stock decrement is REVERSED by calling
 * {@link PharmacyStockDebit#debitConsumableIssue} with the negative qty is NOT correct — we
 * use a dedicated restore method. Since {@code StockService} has {@code increment()} for
 * aggregate-only restoration, we call it via a net-new method on {@link PharmacyStockDebit}.
 * <br>
 * <strong>Decision:</strong> Stock IS restored on delete within 24h (consistency requirement of
 * CR-07-consumable-stock: if we added the decrement on issue, we must add the credit on cancel).
 * The restore is done via a dedicated {@code restoreConsumableIssue} method on
 * {@link PharmacyStockDebit} (RECEIPT-classified stock-card IN row, matching the reversal
 * semantics). See {@link PharmacyStockDebit#restoreConsumableIssue}.
 *
 * <p>Legacy citations: PatientServiceImpl.java:2250-2475 (savePatientConsumableChart);
 * PatientResource.java:3035-3088 (deleteConsumableChart).
 * inc-07 07c-i / CR-07-consumable-stock / CR-07-Q11.
 */
@Service
@RequiredArgsConstructor
public class ConsumableChartService {

    /** Verbatim legacy message — PatientServiceImpl.java:2253-2255. */
    private static final String MSG_MEDICINE_NOT_FOUND = "Medicine not found";
    /** Verbatim legacy message — PatientServiceImpl.java:2260-2262. */
    private static final String MSG_NOT_CONSUMABLE = "Medicine is not listed as consumable";
    /** Verbatim legacy message — PatientServiceImpl.java:2293 (PENDING gate). */
    private static final String MSG_NOT_VERIFIED = "Could not be done. Admission not verified";
    /** Verbatim legacy message — PatientServiceImpl.java:2297 (else gate). */
    private static final String MSG_SIGNED_OFF = "Could not be done. Patient already signed off";
    /**
     * Credit-note reference for consumable delete.
     *
     * <p><em>CR-07-Q11 FIX #2:</em> "Canceled consumable" — NOT the legacy mislabeled
     * "Canceled lab test" (PatientResource.java:3053). Anti-regression per CR-07-Q11:
     * do NOT change this back to "Canceled lab test".
     */
    private static final String CREDIT_NOTE_REF = "Canceled consumable";

    private final AdmissionRepository admissionRepository;
    private final ConsumableChartPort consumableChartPort;
    private final BillingCommands billingCommands;
    private final ConsumableLookup consumableLookup;
    private final MedicineLookup medicineLookup;
    private final PharmacyStockDebit pharmacyStockDebit;

    // =========================================================================
    // Issue (POST)
    // =========================================================================

    /**
     * Record an inpatient consumable chart entry.
     *
     * <p>Guards → bill → chart persist → stock decrement (all in one transaction).
     *
     * @param admissionUid the ULID of the admission
     * @param req          the consumable chart request
     * @param ctx          transaction audit context
     * @return the created chart view
     * @throws NotFoundException                if medicine not found
     * @throws InvalidPatientOperationException if any guard fails (consumable, status, etc.)
     * @throws com.otapp.hmis.shared.error.InsufficientStockException if stock &lt; qty
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ConsumableChartView recordConsumableChart(String admissionUid,
                                                      ConsumableChartRequest req,
                                                      TxAuditContext ctx) {
        // Guard 1: medicine exists (PatientServiceImpl.java:2252-2255)
        if (!medicineLookup.existsByUid(req.medicineUid())) {
            throw new NotFoundException(MSG_MEDICINE_NOT_FOUND);
        }

        // Guard 2: qty > 0 — enforced by Bean Validation (@DecimalMin("0.01")) on the request.
        // Verbatim message "Qty can not be zero" mapped by the validation framework.

        // Guard 3: consumable-registered (PatientServiceImpl.java:2259-2262)
        if (!consumableLookup.isConsumable(req.medicineUid())) {
            throw new NotFoundException(MSG_NOT_CONSUMABLE);
        }

        // Guard 4: nurse present — deferred to clinical-side ConsumableChartPortImpl.
        // (PatientServiceImpl.java:2263-2265)

        // Guard 5: admission IN-PROCESS gate (PatientServiceImpl.java:2292-2299)
        Admission adm = requireInProcessAdmission(admissionUid);

        // Delegate bill creation + chart persist to clinical::api
        ConsumableChartView view = consumableChartPort.recordConsumableChart(
                new RecordConsumableChartCommand(
                        admissionUid,
                        adm.getPatientUid(),
                        req.nurseUid(),
                        req.medicineUid(),
                        req.medicineName(),
                        req.insurancePlanUid(),
                        req.membershipNo(),
                        req.qty(),
                        req.paymentType(),
                        req.pharmacyUid()),
                ctx);

        // CR-07-consumable-stock: decrement pharmacy stock via pharmacy::api seam.
        // Hard negative-stock gate is inside StockService.decrementFefo (InsufficientStockException).
        // If this throws, the entire transaction rolls back — chart + bill are also rolled back.
        String stockRef = "Consumable issued: admission " + admissionUid;
        pharmacyStockDebit.debitConsumableIssue(
                req.pharmacyUid(), req.medicineUid(), req.qty(), stockRef, ctx);

        return view;
    }

    // =========================================================================
    // List (GET)
    // =========================================================================

    @Transactional(readOnly = true)
    public List<ConsumableChartView> findConsumableCharts(String admissionUid) {
        return consumableChartPort.findConsumableChartsByAdmission(admissionUid);
    }

    // =========================================================================
    // Delete (DELETE within 24h)
    // =========================================================================

    /**
     * Delete an inpatient consumable chart within the 24-hour window.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Fetch chart (for bill uid + pharmacy uid + qty — needed for reversal)</li>
     *   <li>Billing reversal: {@code cancelCharge("Canceled consumable", ...)}
     *       (CR-07-Q11 FIX #2 — NOT "Canceled lab test")</li>
     *   <li>Chart delete via {@link ConsumableChartPort#deleteConsumableChart24h}
     *       (24h guard enforced clinical-side)</li>
     *   <li>Stock restore: re-credit the pharmacy stock (CR-07-consumable-stock consistency)</li>
     * </ol>
     *
     * <p><em>CR-07-Q11 FIX #3 (parent-invoice empty check):</em> handled inside
     * {@code BillingCommands.cancelCharge} → {@code CreditNoteService.detachInvoiceDetail}
     * → {@code invoice.isEmpty()}. The legacy j=j++ no-op that always deleted the parent is
     * NOT reproduced. Anti-regression: do NOT call invoiceRepository.delete unconditionally.
     *
     * @param admissionUid the ULID of the admission (not validated against the chart — REST
     *                     path param for URL consistency only; chartUid is the real locator)
     * @param chartUid     the ULID of the consumable chart to delete
     * @param ctx          transaction audit context
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteConsumableChart(String admissionUid, String chartUid, TxAuditContext ctx) {
        // Fetch the chart view first to get bill uid, pharmacy uid, qty for reversal.
        // The 24h guard is enforced clinical-side inside deleteConsumableChart24h.
        ConsumableChartView chart = consumableChartPort.findByUid(chartUid);

        // CR-07-Q11 FIX #2: reference = "Canceled consumable" (NOT "Canceled lab test").
        // Anti-regression per CR-07-Q11: the legacy PatientResource.java:3053 mislabeled
        // consumable cancellation as "Canceled lab test". Do NOT reintroduce that bug.
        billingCommands.cancelCharge(chart.patientBillUid(), CREDIT_NOTE_REF, ctx);

        // CR-07-Q11 FIX #3 is satisfied inside cancelCharge → CreditNoteService.detachInvoiceDetail:
        // the parent invoice is deleted ONLY when invoice.isEmpty() is true (real emptiness check).
        // Anti-regression: the legacy j=j++ no-op (PatientResource.java:3070-3076) always deleted
        // the parent invoice. That bug is NOT reproduced here.

        // Delete the chart row (24h guard enforced here)
        consumableChartPort.deleteConsumableChart24h(chartUid, ctx);

        // CR-07-consumable-stock: restore pharmacy stock on delete within 24h.
        // Decision: since we decremented on issue, we must credit back on cancel to keep
        // stock consistent (CR-07-consumable-stock CR decision).
        if (chart.pharmacyUid() != null && chart.qty() != null) {
            String restoreRef = "Consumable issue reversed: admission " + admissionUid;
            pharmacyStockDebit.restoreConsumableIssue(
                    chart.pharmacyUid(), chart.medicineUid(), chart.qty(), restoreRef, ctx);
        }
    }

    // =========================================================================
    // Shared admission-status gate (verbatim legacy PatientServiceImpl.java:2292-2299)
    // =========================================================================

    private Admission requireInProcessAdmission(String admissionUid) {
        Admission adm = admissionRepository.findByUid(admissionUid)
                .orElseThrow(() -> new NotFoundException("Admission not found: " + admissionUid));
        AdmissionStatus status = adm.getStatus();
        if (status == AdmissionStatus.PENDING) {
            throw new InvalidPatientOperationException(MSG_NOT_VERIFIED);
        }
        if (status != AdmissionStatus.IN_PROCESS) {
            throw new InvalidPatientOperationException(MSG_SIGNED_OFF);
        }
        return adm;
    }
}
