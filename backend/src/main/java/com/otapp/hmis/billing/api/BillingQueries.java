package com.otapp.hmis.billing.api;

import com.otapp.hmis.billing.domain.BillStatus;

/**
 * Narrow read-only cross-module query surface for the {@code billing} module (inc-06A C4, ITEM2/4).
 *
 * <p>Published in the {@code billing :: api} named interface alongside {@link BillingCommands}.
 * Only the already-published {@link BillStatus} enum crosses the boundary — no new billing domain
 * type is exposed (ADR-0008 §1 honoured).
 *
 * <p><strong>Why this seam exists (ADR-0008 §6 scoped relaxation):</strong>
 * ADR-0008 §6 states the clinical module never reads billing bill-status post-hoc. That principle
 * is RELAXED here NARROWLY for the legacy {@code add_report} bill-gate parity case ONLY
 * (PatientResource.java:3183-3197 radiology, :3381-3395 lab): legacy reads the LIVE
 * {@code PatientBill.status} and admits {@code PAID|COVERED|VERIFIED}. The clinical-local
 * {@code settled} boolean is provably insufficient — it is captured at order-creation time and the
 * cash-PAID→settled propagation is deferred, so a bill paid at the cashier AFTER the order was
 * created still shows {@code settled=false}; a settled-flag gate would wrongly reject an
 * {@code add_report} that legacy allows. Settlement gates (open_consultation, worklist filters)
 * MUST keep using the local settled flag — this seam is for add_report bill-gating only.
 *
 * <p>This is a {@code clinical → billing::api} READ edge; clinical already depends on
 * {@code billing::api} for {@link BillingCommands}, so no new module edge and no cycle
 * (billing does not depend on clinical) — {@code ApplicationModules.verify()} stays green.
 */
public interface BillingQueries {

    /**
     * Read the current status of a bill by its public uid.
     *
     * <p><strong>Scoped to the {@code add_report} bill-gate only</strong> (ADR-0008 §6 Addendum) —
     * settlement gates and worklist filters MUST NOT use this; they use the local settled flag or
     * {@link #worklistAdmits(String, boolean)} respectively.
     *
     * @param billUid the ULID of the bill
     * @return the bill's current {@link BillStatus}
     * @throws com.otapp.hmis.shared.error.NotFoundException if no bill with that uid exists
     */
    BillStatus getBillStatus(String billUid);

    /**
     * Whether the given admission has any outstanding (UNPAID or VERIFIED) bills.
     *
     * <p>Reproduces the legacy bills-cleared discharge gate
     * (PatientResource.java:5342-5357 discharge, :5593-5603 referral, :5851-5882 deceased):
     * legacy rejects the summary if ANY bill linked to the admission's invoices is UNPAID or
     * VERIFIED, across ALL service kinds (ward, consumable, pharmacy, lab, radiology,
     * procedure). Insurance-covered bills are neither UNPAID nor VERIFIED, so insurance
     * patients pass automatically.
     *
     * <p>This scan lives in {@code billing} because {@link PatientBill} and
     * {@link PatientInvoice} are owned here; the {@code inpatient} module cannot enumerate
     * lab/radiology/pharmacy bills directly.
     *
     * <p><strong>TODO(07a/07c):</strong> the {@code admission_uid} linkage column on ward and
     * consumable bills is populated when those bills are created in chunk 07a/07c. Until that
     * column exists and is populated on the billing side this method returns {@code false} for
     * every call — it compiles and satisfies the discharge gate contract but yields no positive
     * results. Once chunk 07a/07c adds {@code PatientBill.admissionUid} and populates it at
     * charge time, this query must be updated to scan by that column.
     *
     * <p>Investigation result (inc-07 chunk P): {@code PatientBill}, {@code PatientInvoice},
     * and {@code PatientInvoiceDetail} carry NO {@code admissionUid} column in the current
     * schema. There is therefore no admission-linkage path in the billing domain yet. The
     * method body returns {@code false} as a safe compile-passing stub until 07a/07c adds
     * the column and the real query.
     *
     * @param admissionUid the loose uid of the admission whose bills are to be checked
     * @return {@code true} if ANY bill linked to this admission is UNPAID or VERIFIED;
     *         {@code false} otherwise (including when no linkage column exists yet — 07a/07c)
     */
    boolean admissionHasOutstandingBills(String admissionUid);

    /**
     * Whether a bill admits dispensing on the pharmacy worklist FILTER (inc-08a, Q1; AC-RX-PRE-03/04).
     *
     * <p>Reproduces the legacy pharmacy-worklist bill-status filter verbatim
     * (PatientResource.java:4347/4364/4381/4410): an outpatient/outsider line is admitted when its
     * bill is {@code PAID} or {@code COVERED}; an inpatient line additionally admits {@code VERIFIED}
     * (inpatient credit/post-pay, NOT insurer-verification — D18). This is a purpose-named seam
     * distinct from {@link #getBillStatus(String)} so the worklist FILTER does not reach through the
     * add_report-scoped read, keeping the ADR-0008 §6 scoping honest. The three-valued
     * PAID/COVERED/VERIFIED logic lives in {@code billing}, where bill status is owned — no billing
     * domain type other than {@link BillStatus} (already published) crosses the boundary.
     *
     * <p>This is a {@code clinical → billing :: api} READ edge; clinical already depends on
     * {@code billing :: api}, so no new module edge and no cycle —
     * {@code ApplicationModules.verify()} stays green.
     *
     * @param billUid   the ULID of the prescription's bill
     * @param inpatient whether the prescription is admission-bound (INPATIENT) — admits VERIFIED too
     * @return {@code true} if the worklist should surface a NOT-GIVEN line linked to this bill;
     *         {@code false} (incl. when the bill is missing) otherwise — a FILTER never throws
     */
    boolean worklistAdmits(String billUid, boolean inpatient);
}
