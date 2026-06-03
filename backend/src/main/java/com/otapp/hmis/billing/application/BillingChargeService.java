package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.domain.CoverageStatus;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientInvoice;
import com.otapp.hmis.billing.domain.PatientInvoiceDetail;
import com.otapp.hmis.billing.domain.PatientInvoiceRepository;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.masterdata.lookup.PriceLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.masterdata.lookup.ServicePriceResult;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRICING ENGINE — resolve-time billing logic (build-spec §2, SAFETY-CRITICAL).
 *
 * <p>Implements the two-step cash-first then insurance-override algorithm extracted from
 * {@code PatientServiceImpl.java} and documented in build-spec §2.1 and §2.2.
 * Every money computation uses {@code BigDecimal} with {@link RoundingMode#HALF_UP} at
 * scale 2. NO {@code double} anywhere in this class.
 *
 * <h2>Two-step algorithm (build-spec §2.1)</h2>
 * <ol>
 *   <li><b>STEP 1 — always build at CASH first</b> (PatientServiceImpl.java:821-835):
 *       resolve cash price, create PatientBill with status=UNPAID (or VERIFIED if
 *       REGISTRATION and fee==0).</li>
 *   <li><b>STEP 2 — insurance override</b> (PatientServiceImpl.java:842-849):
 *       if paymentType==INSURANCE or inpatient, attempt covered row lookup.
 *       If covered → override bill, attach to PENDING insurance invoice.
 *       Else → apply per-service fallback asymmetry (§2.2).</li>
 * </ol>
 *
 * <h2>Per-service not-covered fallback asymmetry (build-spec §2.2)</h2>
 * <ul>
 *   <li>CONSULTATION → hard-fail 422 {@code PLAN_NOT_AVAILABLE_FOR_CLINIC} (no bill persisted)</li>
 *   <li>LAB_TEST|RADIOLOGY|PROCEDURE|MEDICINE + inpatient → VERIFIED + attach to cash invoice</li>
 *   <li>LAB_TEST|RADIOLOGY|PROCEDURE|MEDICINE + outpatient insured → silent UNPAID cash</li>
 *   <li>REGISTRATION → silent (stays UNPAID or VERIFIED if fee==0)</li>
 *   <li>WARD → top-up plumbing only (selection deferred to inc-06, CR-11)</li>
 * </ul>
 *
 * <p>Called in the caller's transaction ({@link Propagation#MANDATORY}).
 * No {@code @Async}, no {@code REQUIRES_NEW}.
 */
@Service
@RequiredArgsConstructor
class BillingChargeService {

    private static final String CURRENCY = "TZS";

    private final PriceLookup priceLookup;
    private final PatientBillRepository billRepository;
    private final PatientInvoiceRepository invoiceRepository;

    /**
     * Record a charge for one clinical/registration service item.
     *
     * <p>Propagation MANDATORY: must run inside the caller's transaction so that a
     * consultation hard-fail rolls back the entire clinical encounter atomically.
     *
     * @param kind         service category (ServiceKind enum from masterdata::lookup)
     * @param serviceUid   the service entity uid; null only for REGISTRATION
     * @param patientUid   loose ref to the patient
     * @param planUid      loose ref to the insurance plan (null for cash patients)
     * @param membershipNo patient's insurance membership number (null for cash)
     * @param paymentType  requested payment mode
     * @param qty          quantity (must be 1 for non-MEDICINE; multiplied for MEDICINE)
     * @param isInpatient  whether the patient is currently admitted
     * @param ctx          transaction audit context
     * @return the persisted PatientBill
     */
    @Transactional(propagation = Propagation.MANDATORY)
    PatientBill recordCharge(ServiceKind kind, String serviceUid,
                             String patientUid, String planUid, String membershipNo,
                             PaymentMode paymentType, BigDecimal qty,
                             boolean isInpatient, TxAuditContext ctx) {

        // -----------------------------------------------------------------------
        // STEP 1 — always build at CASH first
        // PatientServiceImpl.java:821-835 (lab exemplar; identical for all kinds)
        // -----------------------------------------------------------------------
        ServicePriceResult cashRow = priceLookup.resolve(null, kind, serviceUid, CURRENCY);

        // Medicine: cash price × qty HALF_UP (PatientServiceImpl.java:1534)
        BigDecimal cashAmount = computeAmount(cashRow.amount(), kind, qty);

        String billItem = labelFor(kind);
        String description = billItem;  // default description = label; callers may enrich

        PatientBill bill = new PatientBill(
                patientUid, kind, billItem, description, qty,
                Money.of(cashAmount), ctx.dayUid());

        // REGISTRATION special: regFee==0 → status=VERIFIED (PatientServiceImpl.java:276)
        if (kind == ServiceKind.REGISTRATION && cashAmount.signum() == 0) {
            bill.markVerified();
        }

        // -----------------------------------------------------------------------
        // STEP 2 — insurance override, gated on coverage attempt
        // PatientServiceImpl.java:837
        // -----------------------------------------------------------------------
        boolean coverageAttempt = (paymentType == PaymentMode.INSURANCE) || isInpatient;

        if (coverageAttempt) {
            ServicePriceResult coveredRow = priceLookup.resolve(planUid, kind, serviceUid, CURRENCY);

            // A real insurance hit = resolve returned a PLAN row (plan_uid != null) that is covered.
            // PriceLookup falls back to the CASH row (plan_uid IS NULL) when no covered plan row
            // exists; cash rows are covered=true by convention, so covered() ALONE cannot distinguish
            // a hit from a cash fallback — key on the resolved row actually being a plan row.
            boolean insuranceHit = coveredRow.planUid() != null && coveredRow.covered();

            if (insuranceHit) {
                // ----------------------------------------------------------------
                // Insurance hit — override the cash bill with the plan price
                // PatientServiceImpl.java:842-849 — same 5 fields every kind
                // ----------------------------------------------------------------
                BigDecimal planAmount = computeAmount(coveredRow.amount(), kind, qty);
                bill.overrideWithInsurance(Money.of(planAmount), planUid, membershipNo);

                // Persist bill first so detail FK is valid
                billRepository.save(bill);

                // Attach to the PENDING insurance invoice accumulator
                attachToInsuranceInvoice(patientUid, planUid, bill,
                                         CoverageStatus.COVERED, ctx);
            } else {
                // ----------------------------------------------------------------
                // No covered row — apply per-service fallback asymmetry (§2.2)
                // ----------------------------------------------------------------
                // Persist the cash bill first, then apply fallback
                billRepository.save(bill);
                applyNotCoveredFallback(kind, bill, isInpatient, patientUid, ctx);
            }
        } else {
            // Pure cash path — just persist
            billRepository.save(bill);
        }

        return bill;
    }

    // -------------------------------------------------------------------------
    // §2.2 Per-service not-covered fallback ASYMMETRY (PRESERVE ALL THREE)
    // PatientServiceImpl.java cited per kind
    // -------------------------------------------------------------------------

    private void applyNotCoveredFallback(ServiceKind kind, PatientBill bill,
                                          boolean isInpatient, String patientUid,
                                          TxAuditContext ctx) {
        switch (kind) {
            case CONSULTATION -> {
                // HARD FAIL — PatientServiceImpl.java:599-601
                // The already-persisted cash bill will be rolled back by the tx
                throw new PlanNotAvailableForClinicException();
            }
            case LAB_TEST, RADIOLOGY, PROCEDURE, MEDICINE -> {
                // PatientServiceImpl.java:912-918 (lab exemplar)
                if (isInpatient) {
                    // Inpatient: cash price kept, status=VERIFIED, attach to NULL-plan cash invoice
                    bill.markVerified();
                    billRepository.save(bill);
                    attachToCashInvoice(patientUid, bill, CoverageStatus.VERIFIED, ctx);
                }
                // else: non-admitted insured → bill stays cash UNPAID (silent — no invoice)
                // Already saved above; no further action needed
            }
            case REGISTRATION -> {
                // Silent — PatientServiceImpl.java:321 (no-op; stays UNPAID or VERIFIED-if-free)
            }
            case WARD -> {
                // Ward top-up plumbing exists (self-link columns ready).
                // Selection + guard logic DEFERRED to inc-06 (CR-11).
                // No action in P1.
            }
        }
    }

    // -------------------------------------------------------------------------
    // PENDING-invoice accumulator helpers
    // -------------------------------------------------------------------------

    /**
     * Attach the bill to the single PENDING insurance invoice for (patientUid, planUid).
     * Creates the invoice if it does not exist.
     * PatientServiceImpl.java:342, :631, :871 (creation pattern).
     */
    private void attachToInsuranceInvoice(String patientUid, String planUid,
                                           PatientBill bill, CoverageStatus coverageStatus,
                                           TxAuditContext ctx) {
        PatientInvoice invoice = invoiceRepository
                .findPendingInsuranceInvoice(patientUid, planUid)
                .orElseGet(() -> {
                    PatientInvoice newInvoice = new PatientInvoice(patientUid, planUid, ctx.dayUid());
                    return invoiceRepository.save(newInvoice);
                });

        PatientInvoiceDetail detail = new PatientInvoiceDetail(invoice, bill, coverageStatus);
        invoice.addDetail(detail);
        invoiceRepository.save(invoice);
    }

    /**
     * Attach the bill to the single PENDING cash invoice for the patient (planUid=null).
     * Used for inpatient cash-fallback VERIFIED bills.
     * PatientServiceImpl.java:912-918 (verified fallback → cash invoice).
     */
    private void attachToCashInvoice(String patientUid, PatientBill bill,
                                      CoverageStatus coverageStatus, TxAuditContext ctx) {
        PatientInvoice invoice = invoiceRepository
                .findPendingCashInvoice(patientUid)
                .orElseGet(() -> {
                    PatientInvoice newInvoice = new PatientInvoice(patientUid, null, ctx.dayUid());
                    return invoiceRepository.save(newInvoice);
                });

        PatientInvoiceDetail detail = new PatientInvoiceDetail(invoice, bill, coverageStatus);
        invoice.addDetail(detail);
        invoiceRepository.save(invoice);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Compute the effective amount: for MEDICINE, multiply by qty with HALF_UP at scale 2.
     * For all other kinds, return the price as-is (already a per-service flat fee).
     * PatientServiceImpl.java:1534 (medicine qty multiplier), :1552-1553 (plan price × qty).
     */
    private static BigDecimal computeAmount(BigDecimal price, ServiceKind kind, BigDecimal qty) {
        if (kind == ServiceKind.MEDICINE) {
            return price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Human-readable label for the bill item field (mirrors legacy billItem strings).
     * PatientBill.java:44 — "NA" default; populated from kind at construction.
     */
    private static String labelFor(ServiceKind kind) {
        return switch (kind) {
            case REGISTRATION  -> "Registration";
            case CONSULTATION  -> "Consultation";
            case LAB_TEST      -> "Lab Test";
            case MEDICINE      -> "Medicine";
            case PROCEDURE     -> "Procedure";
            case RADIOLOGY     -> "Radiology";
            case WARD          -> "Bed";
        };
    }
}
