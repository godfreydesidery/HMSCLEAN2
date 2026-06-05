package com.otapp.hmis.billing.api;

import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.math.BigDecimal;
import java.util.Collection;

/**
 * Cross-module API for recording a clinical charge and related billing mutations
 * (build-spec §4.1, inc-05 adversarial-review F6/F5).
 *
 * <p>Callers (Registration/inc-03, Clinical/inc-05) invoke this inside their own
 * transaction — propagation REQUIRED (caller's tx). NO {@code @Async}, NO
 * {@code REQUIRES_NEW}. Every call is atomic with the clinical encounter.
 *
 * <p>NOT a REST endpoint. NOT {@code @PreAuthorize}-gated. Authorization is enforced
 * once at the caller's REST edge; this in-process Modulith call is trusted.
 *
 * <p>Implementation is package-private in {@code billing.application.BillingCommandsImpl}.
 *
 * <p>Legacy citations: PatientServiceImpl.java:821-849 (charge creation — billing was
 * inline in the clinical service; this interface is the modernised boundary).
 */
public interface BillingCommands {

    /**
     * Record a charge for one clinical/registration service item.
     *
     * <p>Runs the two-step cash-first then insurance-override pricing engine (build-spec §2.1),
     * the per-service not-covered fallback asymmetry (§2.2), and the PENDING-invoice accumulator.
     *
     * @param req the charge request (uids only — no internal ids)
     * @param ctx transaction audit context (dayUid, actor)
     * @return the charge result (billUid, status, amount, coverage)
     */
    ChargeResult recordClinicalCharge(ChargeRequest req, TxAuditContext ctx);

    /**
     * Cancel a clinical charge: soft-cancel the bill (CANCELED), refund any RECEIVED payment
     * (→ REFUNDED) and raise a PENDING credit note, and detach the bill from its insurance claim.
     *
     * <p>Delegates to {@code CreditNoteService.cancelCharge} inside the caller's transaction.
     * Idempotent on the refund side — a second call on an already-CANCELED bill finds no RECEIVED
     * payment detail and creates no second credit note.
     *
     * <p>Reference labels used by clinical callers:
     * <ul>
     *   <li>"Canceled consultation" — cancel_consultation (PatientResource.java:644)</li>
     *   <li>"Freed consultation" — free_consultation child-order cancel</li>
     *   <li>"Deleted prescription" — deletePrescription (clinical parity)</li>
     *   <li>"Canceled lab test" — deleteLabTest (PatientResource.java:2936)</li>
     *   <li>"Canceled radiology" — deleteRadiology (PatientResource.java:3436)</li>
     *   <li>"Canceled procedure" — deleteProcedure (PatientResource.java:3503)</li>
     * </ul>
     *
     * <p>Parameters are Strings only — no billing domain type crosses the module boundary
     * (ADR-0008 §1).
     *
     * @param billUid   the ULID of the bill to cancel
     * @param reference human-readable cause label stamped on the credit note
     * @param ctx       transaction audit context (dayUid, actor)
     */
    void cancelCharge(String billUid, String reference, TxAuditContext ctx);

    /**
     * Record a flat-cash sale charge for one OTC medicine line (Q9 / build-spec §4).
     *
     * <p><strong>Flat-cash path — NOT the plan-pricing engine:</strong> this method constructs a
     * {@code PatientBill} directly at {@code amount = unitPrice * qty} (HALF_UP, NUMERIC 19,2),
     * status=UNPAID, paymentType=CASH, against the GENERAL dummy patient. No plan lookup, no
     * PriceLookup, no invoice accumulator. The bill is immediately saved and its uid returned.
     *
     * <p>Runs inside the caller's (pharmacy) transaction (Propagation.REQUIRED). The returned
     * {@code billUid} is stored on the {@code PharmacySaleOrderDetail.patientBillUid} loose ref.
     *
     * <p>Legacy citation: PatientServiceImpl.java:3395-3442 — OTC bill creation reads
     * {@code medicine.price * qty} directly (no plan engine). This method is the modernised
     * cross-module seam for that inline billing write.
     *
     * @param patientUid  loose uid of the GENERAL dummy patient
     * @param kind        {@link ServiceKind#MEDICINE}
     * @param billItem    legacy bill-item label (e.g. "Medicine Sale")
     * @param description human-readable description (e.g. "Medicine: Paracetamol")
     * @param serviceUid  loose uid of the medicine (stored on the bill for traceability)
     * @param qty         quantity ordered
     * @param unitPrice   cash unit price from {@code medicines.price}
     * @param ctx         transaction audit context
     * @return the uid of the created {@code PatientBill}
     */
    String recordFlatCashSale(String patientUid, ServiceKind kind, String billItem,
                              String description, String serviceUid,
                              BigDecimal qty, BigDecimal unitPrice, TxAuditContext ctx);

    /**
     * Record an UNPAID supplementary "Ward Bed / Room (Top up)" bill for the insurance top-up
     * delta (cash ward price minus covered plan price) and wire the bidirectional
     * principal&harr;supplementary self-link on {@code PatientBill}.
     *
     * <p><strong>Option B / CR-07-WARD-INS-PRICE — genuinely load-bearing top-up:</strong>
     * When {@code PriceLookup.resolve(planUid, WARD, wardTypeUid)} returns a covered price
     * that is <em>less than</em> the WardType cash price, the patient owes the difference at the
     * cashier. This method creates the UNPAID top-up bill for that difference:
     * <ul>
     *   <li>billItem = {@code "Bed"} (verbatim legacy PatientServiceImpl.java:1889)</li>
     *   <li>description = {@code "Ward Bed / Room (Top up)"} (verbatim legacy :1890)</li>
     *   <li>paymentType = {@code CASH} (the top-up is collected from the patient at the cashier)</li>
     *   <li>amount = {@code diff} (NUMERIC 19,2 HALF_UP; {@code diff = cashPrice - coveredPrice > 0})</li>
     *   <li>status = {@code UNPAID}</li>
     *   <li>{@code admission_uid} linked so the discharge-gate includes this bill
     *       ({@link BillingQueries#admissionHasOutstandingBills})</li>
     *   <li>Bidirectional link: {@code supplementaryBill.principalBill = principalBill} (the COVERED
     *       bill) AND {@code principalBill.supplementaryBill = supplementaryBill} (the new top-up)
     *       using the existing self-ref columns on {@link com.otapp.hmis.billing.domain.PatientBill}
     *       (PatientBill.java:65-73).</li>
     * </ul>
     *
     * <p>Runs inside the caller's (inpatient) transaction (Propagation.REQUIRED). The returned uid
     * is stored on {@code AdmissionBed.patientBillUid} so the
     * {@link com.otapp.hmis.inpatient.application.AdmissionSettlementListener} fires when the top-up
     * is paid — activating the admission to IN-PROCESS and occupying the bed.
     *
     * <p><strong>Parameters are Strings/BigDecimal only — no billing domain type crosses the module
     * boundary (ADR-0008 §1).</strong>
     *
     * <p>Legacy citation: PatientServiceImpl.java:1880-1897 (supplementary bill creation, top-up
     * branch). Option B / CR-07-WARD-INS-PRICE makes this branch genuinely load-bearing (the legacy
     * top-up guard was always-false dead code — see docs/delivery/increments/07-inpatient-discovery/
     * 06-AMBIGUITY-WARD-INSURANCE-PRICE.md).
     *
     * @param principalBillUid  uid of the COVERED principal ward bill (created by
     *                          {@link #recordClinicalCharge} for the insurance hit)
     * @param patientUid        loose uid of the patient
     * @param admissionUid      loose uid of the owning admission (linked for discharge gate)
     * @param amount            the top-up amount ({@code cashPrice - coveredPrice}, must be &gt; 0;
     *                          caller ensures this precondition)
     * @param ctx               transaction audit context (dayUid, actor, timestamp)
     * @return the uid of the newly created UNPAID supplementary top-up {@code PatientBill}
     */
    String recordWardTopUp(String principalBillUid, String patientUid,
                           String admissionUid, java.math.BigDecimal amount,
                           com.otapp.hmis.shared.domain.TxAuditContext ctx);

    /**
     * Approve all PENDING invoices whose details contain any of the supplied bill uids.
     *
     * <p>Used by {@code ClosureService.approveDeceased} to reproduce the legacy
     * PatientResource.java:5884-5887 invoice-APPROVED side-effect: on death approval all
     * outstanding consultation invoices are transitioned to APPROVED.
     *
     * <p>Parameters are Strings only — no billing domain type crosses the module boundary
     * (ADR-0008 §1).
     *
     * @param billUids the collection of bill ULIDs whose parent invoices should be approved
     * @param ctx      transaction audit context (dayUid, actor)
     */
    void approveInvoicesForBills(Collection<String> billUids, TxAuditContext ctx);
}
