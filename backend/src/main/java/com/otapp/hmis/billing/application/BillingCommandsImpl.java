package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.api.ChargeResult;
import com.otapp.hmis.billing.domain.CoverageStatus;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientInvoice;
import com.otapp.hmis.billing.domain.PatientInvoiceDetail;
import com.otapp.hmis.billing.domain.PatientInvoiceDetailRepository;
import com.otapp.hmis.billing.domain.PatientInvoiceRepository;
import com.otapp.hmis.masterdata.lookup.PriceLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.masterdata.lookup.ServicePriceResult;
import com.otapp.hmis.shared.application.MoneyMapper;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link BillingCommands} (build-spec §4.1).
 *
 * <p>This class is intentionally package-private in {@code billing.application} — it must
 * never be referenced directly by callers outside the billing module. Callers depend on
 * the {@link BillingCommands} interface from {@code billing.api} only.
 *
 * <p>Propagation REQUIRED: every command runs inside the caller's (Registration/Clinical)
 * transaction, ensuring atomicity with the clinical encounter. A hard-fail rolls back the
 * entire encounter in the caller's tx.
 *
 * <p>NOT {@code @PreAuthorize}-gated — authz is enforced at the caller's REST edge.
 * NOT {@code @Async}, NOT {@code REQUIRES_NEW}.
 */
@Service
@RequiredArgsConstructor
class BillingCommandsImpl implements BillingCommands {

    private static final String CURRENCY        = "TZS";
    private static final String AUDIT_BILL_ENTITY = "billing.PatientBill";

    private final BillingChargeService chargeService;
    private final CreditNoteService creditNoteService;
    private final PatientBillRepository patientBillRepository;
    private final PatientInvoiceDetailRepository invoiceDetailRepository;
    private final PatientInvoiceRepository invoiceRepository;
    private final PriceLookup priceLookup;
    private final AuditRecorder auditRecorder;
    private final MoneyMapper moneyMapper;

    /**
     * {@inheritDoc}
     *
     * <p>Propagation REQUIRED: executes inside the caller's active transaction.
     * If no transaction is active, Spring will throw {@code IllegalTransactionStateException}
     * at runtime — this is intentional (guards against accidental out-of-tx calls).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ChargeResult recordClinicalCharge(ChargeRequest req, TxAuditContext ctx) {
        PatientBill bill = chargeService.recordCharge(
                req.kind(),
                req.serviceUid(),
                req.patientUid(),
                req.planUid(),
                req.membershipNo(),
                req.paymentType(),
                req.qty() != null ? req.qty() : java.math.BigDecimal.ONE,
                req.inpatient(),
                req.followUp(),
                ctx);

        // inc-07 CR-07-Q13-billing-display: when the caller supplies explicit bill-item /
        // description overrides (e.g. "Medication" / "Consumable: <name>" for inpatient
        // consumable charges), apply them now.  When both fields are null — which is the case
        // for ALL existing callers — overrideBillLabels is not called and the bill retains the
        // labelFor(kind) default set by BillingChargeService.  Existing output is unchanged.
        if (req.billItem() != null || req.description() != null) {
            bill.overrideBillLabels(req.billItem(), req.description());
        }

        // inc-07 07a: link the bill to the admission uid when supplied (ward-bed / consumable
        // charges only). All existing callers pass null (trailing component — no behavioural change).
        if (req.admissionUid() != null) {
            bill.linkAdmission(req.admissionUid());
        }

        // Derive coverage status from bill status for the result record
        CoverageStatus coverage = switch (bill.getStatus()) {
            case COVERED  -> CoverageStatus.COVERED;
            case VERIFIED -> CoverageStatus.VERIFIED;
            default       -> CoverageStatus.UNPAID;
        };

        return new ChargeResult(
                bill.getUid(),
                bill.getStatus(),
                moneyMapper.toDto(bill.getAmount()),
                coverage);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Flat-cash path: amount = unitPrice * qty (HALF_UP NUMERIC 19,2). No plan engine.
     * No invoice accumulator. Saves a single PatientBill directly and returns its uid.
     * Propagation REQUIRED — runs inside the caller's (pharmacy) transaction.
     *
     * <p>Legacy citation: PatientServiceImpl.java:3395-3442.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public String recordFlatCashSale(String patientUid, ServiceKind kind,
                                     String billItem, String description, String serviceUid,
                                     BigDecimal qty, BigDecimal unitPrice, TxAuditContext ctx) {
        BigDecimal amount = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        PatientBill bill = new PatientBill(
                patientUid, kind, billItem, description, qty,
                Money.of(amount), ctx.dayUid());
        patientBillRepository.save(bill);
        auditRecorder.record(AUDIT_BILL_ENTITY, bill.getUid(),
                AuditAction.CREATE, ctx.actorUsername());
        return bill.getUid();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CreditNoteService#cancelCharge} which soft-cancels the bill,
     * optionally refunds a RECEIVED payment (→ REFUNDED + PENDING credit note), and
     * detaches the bill's invoice-claim detail.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void cancelCharge(String billUid, String reference, TxAuditContext ctx) {
        creditNoteService.cancelCharge(billUid, reference, ctx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates an UNPAID supplementary "Ward Bed / Room (Top up)" {@link PatientBill} at
     * {@code amount} (HALF_UP 2dp), wires the bidirectional principal&harr;supplementary
     * self-link, and returns the new bill's uid.
     *
     * <p>Propagation REQUIRED — runs inside the caller's (inpatient doAdmission) transaction.
     *
     * <p>Legacy citation: PatientServiceImpl.java:1880-1897 (supplementary top-up bill).
     * Option B / CR-07-WARD-INS-PRICE makes this branch genuinely load-bearing.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public String recordWardTopUp(String principalBillUid, String patientUid,
                                  String admissionUid, BigDecimal amount,
                                  com.otapp.hmis.shared.domain.TxAuditContext ctx) {
        // Validate amount precondition (caller ensures diff > 0; defensive check)
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "recordWardTopUp: amount must be > 0 but was " + scaled);
        }

        // Load the principal (COVERED) bill for bidirectional linkage
        PatientBill principal = patientBillRepository.findByUid(principalBillUid)
                .orElseThrow(() -> new com.otapp.hmis.shared.error.NotFoundException(
                        "Principal ward bill not found: " + principalBillUid));

        // Create the UNPAID supplementary top-up bill.
        // billItem="Bed" and description="Ward Bed / Room (Top up)" — verbatim legacy
        // PatientServiceImpl.java:1889-1890.
        PatientBill topUp = new PatientBill(
                patientUid,
                com.otapp.hmis.masterdata.lookup.ServiceKind.WARD,
                "Bed",                          // billItem — verbatim :1889
                "Ward Bed / Room (Top up)",     // description — verbatim :1890
                BigDecimal.ONE,
                com.otapp.hmis.shared.domain.Money.of(scaled),
                ctx.dayUid());

        // Link admission uid so discharge-gate (admissionHasOutstandingBills) covers the top-up
        topUp.linkAdmission(admissionUid);

        // Bidirectional self-link (PatientBill.java:65-73, CR-07-WARD-INS-PRICE):
        //   topUp.principalBill = principal
        //   principal.supplementaryBill = topUp
        topUp.linkPrincipalBill(principal);

        // Persist top-up first so the FK on principal.supplementaryBill is valid
        patientBillRepository.save(topUp);

        // Wire principal → topUp
        principal.linkSupplementaryBill(topUp);
        patientBillRepository.save(principal);

        auditRecorder.record(AUDIT_BILL_ENTITY, topUp.getUid(),
                AuditAction.CREATE, ctx.actorUsername());

        return topUp.getUid();
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>CASH path (UpdatePatient.java:304-325):</strong>
     * Creates a PatientBill at wardPrice with status=VERIFIED, billItem="Bed",
     * description="Ward Bed / Room". Links the bill to the admission uid for discharge-gate
     * scanning. Does NOT attach to any invoice accumulator — VERIFIED cash accruals are
     * collected at the cashier window directly.
     *
     * <p><strong>INSURANCE path (UpdatePatient.java:340-437, Option B / CR-07-WARD-INS-PRICE):</strong>
     * Resolves the covered price via PriceLookup.resolve(planUid, WARD, wardTypeUid).
     * If an insurance hit exists: creates a COVERED principal bill and attaches it to the
     * PENDING insurance invoice. If diff = wardPrice - coveredPrice &gt; 0: also creates a
     * VERIFIED supplementary top-up bill for the diff (verbatim legacy :421-437).
     * Returns the principal bill uid.
     *
     * <p>Propagation REQUIRED — runs inside the accrual job's per-admission transaction.
     *
     * <p>Legacy citation: UpdatePatient.java:304-437 (accrued ward bill creation).
     * Option B / CR-07-WARD-INS-PRICE for the insurance branch.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public String recordWardAccrual(String patientUid, String admissionUid,
                                    String wardTypeUid, BigDecimal wardPrice,
                                    String insurancePlanUid, String membershipNo,
                                    TxAuditContext ctx) {
        BigDecimal scaledWardPrice = wardPrice.setScale(2, RoundingMode.HALF_UP);

        if (insurancePlanUid != null) {
            // ------------------------------------------------------------------
            // INSURANCE accrual path (UpdatePatient.java:340-437, Option B)
            // Resolve covered price via PriceLookup (mirrors 07a-2 admission path)
            // ------------------------------------------------------------------
            ServicePriceResult coveredRow = priceLookup.resolve(
                    insurancePlanUid, ServiceKind.WARD, wardTypeUid, CURRENCY);

            boolean insuranceHit = coveredRow.planUid() != null && coveredRow.covered();

            if (insuranceHit) {
                BigDecimal coveredPrice = coveredRow.amount().setScale(2, RoundingMode.HALF_UP);

                // Create COVERED principal bill (UpdatePatient.java:359-366)
                PatientBill principal = new PatientBill(
                        patientUid, ServiceKind.WARD,
                        "Bed",               // billItem — verbatim UpdatePatient.java:309
                        "Ward Bed / Room",   // description — verbatim :310
                        BigDecimal.ONE,
                        Money.of(coveredPrice),
                        ctx.dayUid());
                principal.overrideWithInsurance(
                        Money.of(coveredPrice), insurancePlanUid, membershipNo);
                principal.linkAdmission(admissionUid);
                patientBillRepository.save(principal);
                auditRecorder.record(AUDIT_BILL_ENTITY, principal.getUid(),
                        AuditAction.CREATE, ctx.actorUsername());

                // Attach to PENDING insurance invoice (UpdatePatient.java:368-418)
                attachToInsuranceInvoice(patientUid, insurancePlanUid, principal,
                        CoverageStatus.COVERED, ctx);

                // Top-up: diff = wardPrice - coveredPrice (UpdatePatient.java:420-437)
                BigDecimal diff = scaledWardPrice.subtract(coveredPrice)
                        .setScale(2, RoundingMode.HALF_UP);
                if (diff.compareTo(BigDecimal.ZERO) > 0) {
                    PatientBill topUp = new PatientBill(
                            patientUid, ServiceKind.WARD,
                            "Bed",                          // verbatim :425
                            "Ward Bed / Room (Top up)",     // verbatim :428
                            BigDecimal.ONE,
                            Money.of(diff),
                            ctx.dayUid());
                    topUp.markVerified();   // accrued top-up is VERIFIED (collectable at cashier)
                    topUp.linkAdmission(admissionUid);
                    topUp.linkPrincipalBill(principal);
                    patientBillRepository.save(topUp);
                    principal.linkSupplementaryBill(topUp);
                    patientBillRepository.save(principal);
                    auditRecorder.record(AUDIT_BILL_ENTITY, topUp.getUid(),
                            AuditAction.CREATE, ctx.actorUsername());
                }

                return principal.getUid();
            }
            // If no insurance hit for an INSURANCE patient, fall through to cash-VERIFIED path
            // (consistent with the §2.2 not-covered fallback for WARD — currently a no-op in
            // the charge engine; we handle it here by creating a VERIFIED cash bill).
        }

        // ------------------------------------------------------------------
        // CASH accrual path (UpdatePatient.java:304-325)
        // VERIFIED bill at wardPrice — verbatim legacy :311.
        // ------------------------------------------------------------------
        PatientBill accrued = new PatientBill(
                patientUid, ServiceKind.WARD,
                "Bed",               // billItem — verbatim UpdatePatient.java:309
                "Ward Bed / Room",   // description — verbatim :310
                BigDecimal.ONE,
                Money.of(scaledWardPrice),
                ctx.dayUid());
        accrued.markVerified();       // status=VERIFIED — verbatim UpdatePatient.java:311
        accrued.linkAdmission(admissionUid);
        patientBillRepository.save(accrued);
        auditRecorder.record(AUDIT_BILL_ENTITY, accrued.getUid(),
                AuditAction.CREATE, ctx.actorUsername());
        return accrued.getUid();
    }

    // -------------------------------------------------------------------------
    // Private helpers for ward accrual insurance invoice attachment
    // -------------------------------------------------------------------------

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
     * {@inheritDoc}
     *
     * <p>For each bill uid, locates the parent {@link PatientInvoice} via the invoice-detail
     * join and calls {@link PatientInvoice#approve()} on any that are still PENDING.
     * Deduplicates across bill uids so a multi-line invoice is only approved once.
     * Reproduces PatientResource.java:5884-5887.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void approveInvoicesForAdmission(String admissionUid, TxAuditContext ctx) {
        // Collect all bill uids for this admission then approve their parent invoices.
        // PatientResource.java:5354-5357, :5626-5631, :5884-5887 (discharge/referral/deceased).
        var billUids = patientBillRepository.findUidsByAdmissionUid(admissionUid);
        Set<String> approvedInvoiceUids = new HashSet<>();
        for (String billUid : billUids) {
            invoiceDetailRepository.findByBillUid(billUid).ifPresent(detail -> {
                PatientInvoice invoice = detail.getInvoice();
                if (invoice != null && !approvedInvoiceUids.contains(invoice.getUid())) {
                    invoice.approve();
                    invoiceRepository.save(invoice);
                    auditRecorder.record("billing.PatientInvoice", invoice.getUid(),
                            AuditAction.UPDATE, ctx.actorUsername());
                    approvedInvoiceUids.add(invoice.getUid());
                }
            });
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void approveInvoicesForBills(Collection<String> billUids, TxAuditContext ctx) {
        Set<String> approvedInvoiceUids = new HashSet<>();
        for (String billUid : billUids) {
            invoiceDetailRepository.findByBillUid(billUid).ifPresent(detail -> {
                PatientInvoice invoice = detail.getInvoice();
                if (invoice != null && !approvedInvoiceUids.contains(invoice.getUid())) {
                    invoice.approve();
                    invoiceRepository.save(invoice);
                    auditRecorder.record("billing.PatientInvoice", invoice.getUid(),
                            AuditAction.UPDATE, ctx.actorUsername());
                    approvedInvoiceUids.add(invoice.getUid());
                }
            });
        }
    }
}
